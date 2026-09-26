package com.hackathon.recall.extract

/**
 * Maps Devanagari (०-९), Telugu (౦-౯) and full-width digits to ASCII. The mapping is one char to one
 * char, so offsets into the normalized string still line up with the OCR text and its boxes.
 */
object DigitNormalizer {
    fun normalize(text: String): String {
        var changed = false
        val out = CharArray(text.length) { i ->
            val c = text[i]
            val mapped = when (c) {
                in '०'..'९' -> '0' + (c - '०')
                in '౦'..'౯' -> '0' + (c - '౦')
                in '０'..'９' -> '0' + (c - '０')
                else -> c
            }
            if (mapped != c) changed = true
            mapped
        }
        return if (changed) String(out) else text
    }
}
