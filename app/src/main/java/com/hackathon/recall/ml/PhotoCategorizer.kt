package com.hackathon.recall.ml

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.PhotoCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.exp

/** assets/photo_labels.json, written by tools/photo_labels.py from the same SigLIP2 checkpoint. */
@Serializable
data class PhotoLabels(
    val checkpoint: String,
    @SerialName("logit_scale") val logitScale: Float,
    @SerialName("logit_bias") val logitBias: Float,
    val labels: List<Label>,
) {
    @Serializable
    data class Label(val category: String, val text: String, val vector: List<Float>)
}

/** Faces found in a photo: how many, and how much of the frame the largest one fills (0..1). */
data class Faces(val count: Int, val largestArea: Float)

/**
 * Files every gallery photo under one personal category (Screenshots, Selfies, People, Food, Trips &
 * places, Bills, Documents, Other). SigLIP2 zero-shot does the looking: each category is described a
 * few ways and its probability is the sum over its descriptions. What the app already knows for
 * certain wins over the picture: a saved document keeps its checked type, a photo in a Screenshots
 * folder is a screenshot, and a person category needs a face.
 */
class PhotoCategorizer(labels: PhotoLabels) {
    private val categories = labels.labels.map { PhotoCategory.fromDb(it.category) ?: PhotoCategory.OTHER }
    private val vectors = labels.labels.map { VectorMath.l2Normalize(it.vector.toFloatArray()) }
    private val scale = labels.logitScale

    /** Probability per category for an L2-normalised SigLIP2 image vector (sums to 1). */
    fun probabilities(image: FloatArray): Map<PhotoCategory, Float> {
        val logits = vectors.map { scale * VectorMath.dot(image, it) }
        val max = logits.max()
        val exps = logits.map { exp((it - max).toDouble()) }
        val total = exps.sum()
        val out = HashMap<PhotoCategory, Float>()
        exps.forEachIndexed { i, e -> out.merge(categories[i], (e / total).toFloat(), Float::plus) }
        return out
    }

    data class Decision(val category: PhotoCategory, val confidence: Float)

    companion object {
        /** Below this the picture is too ambiguous to file anywhere but Other. */
        const val MIN_CONFIDENCE = 0.4f
        /** Face detection only runs when a person category is at least this likely. */
        const val FACE_CHECK_MIN = 0.25f
        /** Three or more faces is a group, whatever SigLIP2 thought. */
        private const val GROUP_FACES = 3

        val BILL_TYPES = setOf(DocType.RECEIPT_INVOICE, DocType.PAYMENT_SCREENSHOT, DocType.UTILITY_BILL, DocType.HOSPITAL_BILL)
        private val PERSON = setOf(PhotoCategory.SELFIE, PhotoCategory.PEOPLE)

        fun needsFaceCheck(probs: Map<PhotoCategory, Float>): Boolean =
            PERSON.sumOf { (probs[it] ?: 0f).toDouble() } >= FACE_CHECK_MIN

        /**
         * [savedType] is the effective (≥ 80 % or user-confirmed) type of the document this photo was
         * saved as, or null when it was not saved; an uncertain document is still a document.
         * [faces] is null when face detection did not run.
         */
        fun decide(
            savedAsDocument: Boolean,
            savedType: DocType?,
            savedConfidence: Float,
            relativePath: String?,
            probs: Map<PhotoCategory, Float>,
            faces: Faces?,
        ): Decision {
            if (savedAsDocument) {
                val bill = savedType in BILL_TYPES
                return Decision(if (bill) PhotoCategory.BILLS else PhotoCategory.DOCUMENTS, if (bill) savedConfidence else 1f)
            }
            if (relativePath?.lowercase()?.contains("screenshot") == true) return Decision(PhotoCategory.SCREENSHOT, 1f)
            if (probs.isEmpty()) return Decision(PhotoCategory.OTHER, 0f)

            val person = PERSON.sumOf { (probs[it] ?: 0f).toDouble() }.toFloat()
            val best = probs.maxBy { it.value }
            val picked: Decision = when {
                best.key !in PERSON || faces == null -> Decision(best.key, best.value)
                faces.count == 0 -> {
                    // No face: not a selfie or people photo, so take the likeliest of the rest.
                    val rest = probs.filterKeys { it !in PERSON }
                    val alt = rest.maxBy { it.value }
                    Decision(alt.key, if (person < 1f) alt.value / (1f - person) else 0f)
                }
                faces.count >= GROUP_FACES -> Decision(PhotoCategory.PEOPLE, person)
                else -> Decision(best.key, person)
            }
            // The gate already said this is not a document, so a document-looking photo is filed as Other.
            if (picked.category == PhotoCategory.DOCUMENTS) return Decision(PhotoCategory.OTHER, picked.confidence)
            return if (picked.confidence < MIN_CONFIDENCE) Decision(PhotoCategory.OTHER, picked.confidence) else picked
        }
    }
}
