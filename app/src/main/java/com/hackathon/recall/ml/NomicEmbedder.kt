package com.hackathon.recall.ml

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nomic Embed Text v1.5 (Qualcomm AI Hub export: input_tokens + attention_masks, int32 [1, 128]).
 * If the graph returns per-token states they are mean-pooled over the mask; the result is always
 * L2-normalized. Callers add the task prefix ("search_document: " / "search_query: ").
 */
class NomicEmbedder(private val model: LiteRtModel, val tokenizer: WordPieceTokenizer) : EmbeddingModel<String> {
    private val idsIndex: Int
    private val maskIndex: Int
    private val outShape: IntArray = model.outputs[0].shape()

    init {
        val names = model.inputs.map { it.name().lowercase() }
        maskIndex = names.indexOfFirst { "mask" in it }.takeIf { it >= 0 } ?: 1
        idsIndex = if (maskIndex == 0) 1 else 0
        require(model.inputs[idsIndex].shape().last() == tokenizer.maxLength) {
            "model expects ${model.inputs[idsIndex].shape().contentToString()} tokens, tokenizer pads to ${tokenizer.maxLength}"
        }
        Log.i("NomicEmbedder", "inputs=${model.inputs.map { it.name() + it.shape().contentToString() }} output=${outShape.contentToString()}")
    }

    /** "pooled" when the graph already returns one vector, "mean-pooled" when we pool token states. */
    val pooling: String get() = if (outShape.size == 3) "mean-pooled [${outShape.joinToString("×")}]" else "pooled [${outShape.joinToString("×")}]"

    override val backend: Backend get() = model.backend

    @Volatile
    override var lastLatencyMs: Long = 0
        private set

    override suspend fun embed(input: String): FloatArray = withContext(Dispatchers.Default) {
        val start = System.nanoTime()
        val enc = tokenizer.encode(input)
        val buffers = Array(2) { i ->
            if (i == idsIndex) LiteRtModel.intInput(model.inputs[i], enc.ids) else LiteRtModel.intInput(model.inputs[i], enc.mask)
        }
        val out = model.run(buffers)
        val raw = LiteRtModel.toFloats(model.outputs[0], out[0])
        val pooled = if (outShape.size == 3) VectorMath.meanPool(raw, outShape[1], outShape[2], enc.mask) else raw
        lastLatencyMs = (System.nanoTime() - start) / 1_000_000
        Metrics.record("nomic.embed", lastLatencyMs.toDouble())
        VectorMath.l2Normalize(pooled)
    }

    fun warmup() {
        val enc = tokenizer.encode("search_query: warm up")
        model.run(Array(2) { i -> LiteRtModel.intInput(model.inputs[i], if (i == idsIndex) enc.ids else enc.mask) })
    }

    companion object {
        const val DOC_PREFIX = "search_document: "
        const val QUERY_PREFIX = "search_query: "
    }
}
