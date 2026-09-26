package com.hackathon.recall.ml

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SigLIP2 vision tower (google/siglip2-base-patch16-224, exported by Qualcomm AI Hub). Only the image
 * encoder runs on the phone; label text vectors are precomputed by tools/siglip_labels.py.
 * Layout (NCHW or NHWC) is read from the input tensor. [inputRange] is "zero_one" (AI Hub convention,
 * normalization inside the graph) or "minus_one_one" (HF processor: mean 0.5, std 0.5); the on-device
 * parity check against Python decides which is right (docs/DECISIONS.md).
 */
class SiglipVision(private val model: LiteRtModel, private val inputRange: String) : EmbeddingModel<Bitmap> {
    private val input = model.inputs[0]
    private val shape = input.shape()
    private val nchw = shape.size == 4 && shape[1] == 3
    private val size = if (nchw) shape[2] else shape[1]

    override val backend: Backend get() = model.backend

    @Volatile
    override var lastLatencyMs: Long = 0
        private set

    override suspend fun embed(input: Bitmap): FloatArray = withContext(Dispatchers.Default) {
        val start = System.nanoTime()
        val pixels = preprocess(input)
        val out = model.run(arrayOf(LiteRtModel.floatInput(this@SiglipVision.input, pixels)))
        val vector = VectorMath.l2Normalize(LiteRtModel.toFloats(model.outputs[0], out[0]))
        lastLatencyMs = (System.nanoTime() - start) / 1_000_000
        Metrics.record("siglip.embed", lastLatencyMs.toDouble())
        vector
    }

    private fun preprocess(src: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val argb = IntArray(size * size)
        scaled.getPixels(argb, 0, size, 0, 0, size, size)
        if (scaled !== src) scaled.recycle()
        val out = FloatArray(3 * size * size)
        val plane = size * size
        for (i in argb.indices) {
            val p = argb[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val (rr, gg, bb) = if (inputRange == "minus_one_one") Triple(r * 2 - 1, g * 2 - 1, b * 2 - 1) else Triple(r, g, b)
            if (nchw) {
                out[i] = rr
                out[plane + i] = gg
                out[2 * plane + i] = bb
            } else {
                out[3 * i] = rr
                out[3 * i + 1] = gg
                out[3 * i + 2] = bb
            }
        }
        return out
    }

    /** Warm-up input: a mid-grey image. */
    fun warmup() {
        model.run(arrayOf(LiteRtModel.floatInput(input, FloatArray(input.numElements()) { 0.5f })))
    }
}
