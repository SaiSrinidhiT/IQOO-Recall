package com.hackathon.recall.search

import com.hackathon.recall.model.Lang

/**
 * Picks the answer language: native script first (Telugu or Devanagari characters), then weighted
 * romanized hint words ("cheyyi" → Telugu, "dikhao" → Hindi), else English.
 */
class LanguageDetector(private val hints: Map<String, Map<String, Double>>) {
    fun detect(text: String): Lang {
        var telugu = 0
        var devanagari = 0
        text.codePoints().forEach { cp ->
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.TELUGU -> telugu++
                Character.UnicodeScript.DEVANAGARI -> devanagari++
                else -> {}
            }
        }
        if (telugu > 0 && telugu >= devanagari) return Lang.TE
        if (devanagari > 0) return Lang.HI

        val words = QueryText.normalize(text).split(' ').filter { it.isNotEmpty() }
        fun score(lang: String) = words.sumOf { hints[lang]?.get(it) ?: 0.0 }
        val te = score("te")
        val hi = score("hi")
        return when {
            te >= 1.5 && te >= hi -> Lang.TE
            hi >= 1.5 -> Lang.HI
            else -> Lang.EN
        }
    }
}
