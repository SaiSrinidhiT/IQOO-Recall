package com.hackathon.recall.ml

import android.content.Context
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * A TFLite model on LiteRT 1.4.2, tried on the Hexagon NPU (Qualcomm QNN delegate, HTP backend), then
 * the GPU delegate, then CPU (XNNPACK). A backend only counts once a warm-up inference succeeds on it,
 * so [backend] is the compute unit that actually ran, not the one requested (brief F11).
 * The interpreter is not thread-safe: callers serialize access (see [run]).
 */
class LiteRtModel private constructor(
    private val interpreter: Interpreter,
    val backend: Backend,
    private val delegate: AutoCloseable?,
    /** Why faster backends were skipped, for the Benchmark screen. */
    val fallbackReasons: List<String>,
) : Closeable {
    /** Interpreter creation plus warm-up inference, on the backend that succeeded. */
    var loadMs: Long = 0
        private set

    val inputs: List<Tensor> = (0 until interpreter.inputTensorCount).map { interpreter.getInputTensor(it) }
    val outputs: List<Tensor> = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it) }

    /** Runs one inference. [inputs] are direct buffers in tensor order; returns one buffer per output. */
    @Synchronized
    fun run(inputs: Array<ByteBuffer>): List<ByteBuffer> {
        val buffers = outputs.map { allocate(it.numBytes()) }
        val out = HashMap<Int, Any>()
        buffers.forEachIndexed { i, b -> out[i] = b }
        inputs.forEach { it.rewind() }
        interpreter.runForMultipleInputsOutputs(Array<Any>(inputs.size) { inputs[it] }, out)
        buffers.forEach { it.rewind() }
        return buffers
    }

    override fun close() {
        interpreter.close()
        delegate?.close()
    }

    companion object {
        private const val TAG = "LiteRtModel"

        fun allocate(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        /** Float view of an output tensor, dequantizing 8/16-bit outputs with the tensor's parameters. */
        fun toFloats(t: Tensor, b: ByteBuffer): FloatArray {
            b.rewind()
            return when (t.dataType()) {
                DataType.FLOAT32 -> FloatArray(t.numElements()).also { b.asFloatBuffer().get(it) }
                DataType.UINT8 -> {
                    val q = t.quantizationParams()
                    FloatArray(t.numElements()) { ((b.get().toInt() and 0xFF) - q.zeroPoint) * q.scale }
                }
                DataType.INT8 -> {
                    val q = t.quantizationParams()
                    FloatArray(t.numElements()) { (b.get() - q.zeroPoint) * q.scale }
                }
                DataType.INT16 -> {
                    val q = t.quantizationParams()
                    val sb = b.asShortBuffer()
                    FloatArray(t.numElements()) { (sb.get() - q.zeroPoint) * q.scale }
                }
                else -> error("unsupported output type ${t.dataType()} for ${t.name()}")
            }
        }

        /** Buffer for a float input tensor, quantizing when the model expects 8-bit input. */
        fun floatInput(t: Tensor, values: FloatArray): ByteBuffer {
            require(values.size == t.numElements()) { "input ${t.name()} needs ${t.numElements()} values, got ${values.size}" }
            val b = allocate(t.numBytes())
            when (t.dataType()) {
                DataType.FLOAT32 -> b.asFloatBuffer().put(values)
                DataType.UINT8 -> {
                    val q = t.quantizationParams()
                    for (v in values) b.put((v / q.scale + q.zeroPoint).toInt().coerceIn(0, 255).toByte())
                }
                DataType.INT8 -> {
                    val q = t.quantizationParams()
                    for (v in values) b.put((v / q.scale + q.zeroPoint).toInt().coerceIn(-128, 127).toByte())
                }
                else -> error("unsupported input type ${t.dataType()} for ${t.name()}")
            }
            b.rewind()
            return b
        }

        fun intInput(t: Tensor, values: IntArray): ByteBuffer {
            val b = allocate(t.numBytes())
            when (t.dataType()) {
                DataType.INT32 -> b.asIntBuffer().put(values)
                DataType.INT64 -> b.asLongBuffer().put(LongArray(values.size) { values[it].toLong() })
                else -> error("expected an integer input for ${t.name()}, got ${t.dataType()}")
            }
            b.rewind()
            return b
        }

        /**
         * Loads [file] on the first backend in [order] whose [warmup] run succeeds.
         * @throws IllegalStateException listing why each backend failed.
         */
        fun load(context: Context, file: File, order: List<Backend>, warmup: (LiteRtModel) -> Unit): LiteRtModel {
            val buffer = FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }
            val reasons = ArrayList<String>()
            for (backend in order) {
                val start = System.nanoTime()
                var delegate: AutoCloseable? = null
                var model: LiteRtModel? = null
                try {
                    val options = Interpreter.Options()
                    when (backend) {
                        Backend.NPU -> {
                            val d = QnnDelegate(qnnOptions(context, file))
                            delegate = d
                            options.addDelegate(d)
                        }
                        Backend.GPU -> {
                            val d = GpuDelegate()
                            delegate = d
                            options.addDelegate(d)
                        }
                        Backend.CPU -> options.setNumThreads(4).setUseXNNPACK(true)
                        Backend.UNAVAILABLE -> continue
                    }
                    val m = LiteRtModel(Interpreter(buffer, options), backend, delegate, reasons.toList())
                    model = m
                    warmup(m)
                    m.loadMs = (System.nanoTime() - start) / 1_000_000
                    Log.i(TAG, "${file.name} loaded on $backend in ${m.loadMs} ms")
                    return m
                } catch (t: Throwable) {
                    reasons += "$backend: ${t.javaClass.simpleName}: ${t.message}"
                    Log.w(TAG, "${file.name} failed on $backend: ${t.message}")
                    model?.close() ?: delegate?.close()
                }
            }
            throw IllegalStateException("${file.name} could not run on any backend: ${reasons.joinToString("; ")}")
        }

        private fun qnnOptions(context: Context, model: File) = QnnDelegate.Options().apply {
            setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
            // The DSP loads the Hexagon skel library from this directory (native libs are extracted).
            setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
            setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
            setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16)
            // Cache the compiled HTP graph so later cold starts skip on-device graph preparation.
            setCacheDir(File(context.cacheDir, "qnn").apply { mkdirs() }.absolutePath)
            setModelToken("${model.name}-${model.length()}")
        }
    }
}
