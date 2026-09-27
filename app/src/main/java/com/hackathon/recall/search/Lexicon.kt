package com.hackathon.recall.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer

/** assets/rule_lexicon.json: multilingual and romanized terms for the rule-based fallback (brief §6.4). */
@Serializable
data class Lexicon(
    @SerialName("doc_types") val docTypes: Map<String, List<String>>,
    val templates: Map<String, List<String>>,
    @SerialName("intent_words") val intentWords: Map<String, List<String>>,
    @SerialName("language_hints") val languageHints: Map<String, Map<String, Double>>,
    @SerialName("relative_dates") val relativeDates: Map<String, List<String>>,
    /** Small-talk vocabulary by kind (greeting, thanks, help, bye): routes a message to chat, not search. */
    @SerialName("chat_words") val chatWords: Map<String, List<String>> = emptyMap(),
    /** Gallery category (PhotoCategory name) → words that ask for those photos. */
    @SerialName("photo_categories") val photoCategories: Map<String, List<String>> = emptyMap(),
    /** Phrases that reject the previous reply ("that's not it", "wrong one"), keyed just "correction". */
    @SerialName("correction_words") val correctionWords: Map<String, List<String>> = emptyMap(),
    /** "before" cues rule out the document named after them ("except Aadhaar"); "after" cues the one before ("Aadhaar kakunda"). */
    @SerialName("negation_words") val negationWords: Map<String, List<String>> = emptyMap(),
    /** DocAction code → verbs asking for it ("email my salary slip" → share). */
    @SerialName("action_words") val actionWords: Map<String, List<String>> = emptyMap(),
    @SerialName("order_words") val orderWords: Map<String, List<String>> = emptyMap(),
    /** "what documents do I have", "show everything": a list of everything, not a search. */
    @SerialName("overview_words") val overviewWords: Map<String, List<String>> = emptyMap(),
    /** Pronouns, ordinals and follow-up verbs that point at the previous reply's documents. */
    @SerialName("follow_up_words") val followUpWords: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        fun parse(json: String): Lexicon = LlmJson.json.decodeFromString(serializer(), json)
    }
}

/** Shared text normalization for query matching: NFC, digits, lowercase, no ZWJ/ZWNJ, punctuation → space. */
object QueryText {
    private val SPACES = Regex("\\s+")
    private val PUNCT_TYPES = setOf(
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
    ).map { it.toInt() }.toSet()

    fun normalize(s: String): String {
        val t = Normalizer.normalize(com.hackathon.recall.extract.DigitNormalizer.normalize(s), Normalizer.Form.NFC).lowercase()
        val sb = StringBuilder(t.length)
        for (c in t) {
            when {
                c == '\u200C' || c == '\u200D' -> {}
                c != '@' && Character.getType(c) in PUNCT_TYPES -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(SPACES, " ").trim()
    }

    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
        Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()

    fun isLatin(term: String): Boolean = term.all { it.code < 0x0250 }

    /** Optimal-string-alignment (Damerau) edit distance, capped: returns cap + 1 once it is exceeded. */
    fun editDistance(a: String, b: String, cap: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                rowMin = minOf(rowMin, v)
            }
            if (rowMin > cap) return cap + 1
        }
        return d[a.length][b.length]
    }
}
