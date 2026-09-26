package com.hackathon.recall.ocr

import kotlinx.serialization.Serializable

/** Axis-aligned box in pixels of the OCR'd bitmap (or normalized to [0,1] when stored). */
@Serializable
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width: Float get() = r - l
    val height: Float get() = b - t

    fun union(o: Box) = Box(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))

    fun inflate(dx: Float, dy: Float) = Box(l - dx, t - dy, r + dx, b + dy)

    fun normalized(w: Int, h: Int) = Box(l / w, t / h, r / w, b / h)

    fun scaled(w: Int, h: Int) = Box(l * w, t * h, r * w, b * h)

    /** Fraction of the smaller box's height that overlaps vertically: are they on the same text row? */
    fun verticalOverlap(o: Box): Float {
        val overlap = minOf(b, o.b) - maxOf(t, o.t)
        return if (overlap <= 0) 0f else overlap / minOf(height, o.height).coerceAtLeast(1e-6f)
    }
}

@Serializable
data class OcrWord(val text: String, val box: Box, val confidence: Float = 1f)

@Serializable
data class OcrLine(val text: String, val box: Box, val words: List<OcrWord>, val confidence: Float = 1f)

/** One page's OCR. [engine] records which engine produced it ("mlkit", "tesseract", "mlkit+tesseract"). */
@Serializable
data class OcrResult(val lines: List<OcrLine>, val width: Int, val height: Int, val engine: String) {
    val text: String get() = lines.joinToString("\n") { it.text }

    /** Same layout with boxes normalized to [0,1], for storage. */
    fun normalized(): OcrResult = copy(
        lines = lines.map { l ->
            l.copy(box = l.box.normalized(width, height), words = l.words.map { it.copy(box = it.box.normalized(width, height)) })
        },
    )

    companion object {
        val EMPTY = OcrResult(emptyList(), 1, 1, "none")
    }
}
