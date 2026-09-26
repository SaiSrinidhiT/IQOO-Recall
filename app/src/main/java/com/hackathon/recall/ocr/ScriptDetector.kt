package com.hackathon.recall.ocr

/** Which scripts appear in OCR text, and how much of it is useful (letters and digits). */
object ScriptDetector {
    enum class Script(val code: String) { LATIN("latin"), DEVANAGARI("devanagari"), TELUGU("telugu") }

    fun counts(text: String): Map<Script, Int> {
        val counts = HashMap<Script, Int>()
        text.codePoints().forEach { cp ->
            val s = when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> Script.LATIN
                Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
                Character.UnicodeScript.TELUGU -> Script.TELUGU
                else -> null
            }
            if (s != null && Character.isLetter(cp)) counts[s] = (counts[s] ?: 0) + 1
        }
        return counts
    }

    /** Scripts with at least 3 letters and 5% of all letters, most frequent first. */
    fun scripts(text: String): List<Script> {
        val c = counts(text)
        val total = c.values.sum().coerceAtLeast(1)
        return c.entries.filter { it.value >= 3 && it.value * 20 >= total }.sortedByDescending { it.value }.map { it.key }
    }

    fun usefulChars(text: String): Int = text.count { it.isLetterOrDigit() }
}
