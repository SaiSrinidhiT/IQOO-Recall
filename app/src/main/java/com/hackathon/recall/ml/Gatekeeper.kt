package com.hackathon.recall.ml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp

/** assets/siglip_labels.json, written by tools/siglip_labels.py from the same checkpoint AI Hub exported. */
@Serializable
data class SiglipLabels(
    val checkpoint: String,
    @SerialName("logit_scale") val logitScale: Float,
    @SerialName("logit_bias") val logitBias: Float,
    val labels: List<Label>,
) {
    @Serializable
    data class Label(val text: String, @SerialName("is_document") val isDocument: Boolean, val vector: List<Float>)
}

/**
 * SigLIP2 document gatekeeper (brief §3.1): a document if the top label is a document label and the
 * margin between the best document label and the best non-document label clears the calibrated
 * threshold. [Verdict.uncertain] marks images near the threshold, which still get OCR when they are
 * screenshots or come from Downloads / WhatsApp Documents.
 */
class Gatekeeper(labels: SiglipLabels, private val margin: Float, private val uncertainBand: Float) {
    private val names = labels.labels.map { it.text }
    private val isDoc = labels.labels.map { it.isDocument }
    private val vectors = labels.labels.map { l -> VectorMath.l2Normalize(l.vector.toFloatArray()) }
    private val scale = labels.logitScale
    private val bias = labels.logitBias

    data class Verdict(
        val isDocument: Boolean,
        val uncertain: Boolean,
        val topLabel: String,
        val margin: Float,
        /** Softmax over document labels only, used as the doc-type hint. */
        val docLabelProbs: Map<String, Float>,
    )

    fun judge(image: FloatArray): Verdict {
        val sims = vectors.map { VectorMath.dot(image, it) }
        val top = sims.indices.maxBy { sims[it] }
        val bestDoc = sims.indices.filter { isDoc[it] }.maxOf { sims[it] }
        val bestNon = sims.indices.filter { !isDoc[it] }.maxOf { sims[it] }
        val m = bestDoc - bestNon
        val docIdx = sims.indices.filter { isDoc[it] }
        val logits = docIdx.map { scale * sims[it] + bias }
        val maxLogit = logits.max()
        val exps = logits.map { exp((it - maxLogit).toDouble()) }
        val total = exps.sum()
        val probs = docIdx.mapIndexed { k, i -> names[i] to (exps[k] / total).toFloat() }.toMap()
        return Verdict(
            isDocument = isDoc[top] && m >= margin,
            uncertain = abs(m - margin) < uncertainBand,
            topLabel = names[top],
            margin = m,
            docLabelProbs = probs,
        )
    }
}
