package com.hackathon.recall.search

import com.hackathon.recall.extract.DateParser
import com.hackathon.recall.extract.DatePrecision
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.PhotoCategory
import java.time.LocalDate
import java.time.YearMonth

/**
 * Rule-based query understanding for when Qwen is unavailable or fails twice (brief §6.4): a
 * multilingual keyword dictionary with typo tolerance, relative/explicit date parsing, and script- or
 * hint-word-based answer language.
 */
class RuleFallbackParser(private val lexicon: Lexicon) {
    private val languageDetector = LanguageDetector(lexicon.languageHints)
    private val lastNMonths = Regex(
        "(?:last|past|pichle|pichhle|gata|gatha|poyina|పోయిన|गत|पिछले|గత)\\s+(\\d{1,2})\\s+(?:months?|mahine|nelalu|నెలలు|महीने|महीनों)",
    )

    private data class Hit(val key: String, val start: Int, val end: Int)

    fun parse(query: String, today: LocalDate): QueryIntent {
        val q = QueryText.normalize(query)
        val lang = languageDetector.detect(query)
        val docTypeHits = longestHits(match(q, lexicon.docTypes))
        val templateHits = longestHits(match(q, lexicon.templates))
        val docTypes = docTypeHits.mapNotNull { DocType.parse(it.key) }.distinct()
        val template = templateHits.firstOrNull()?.key
        fun has(intent: String) = match(q, mapOf(intent to lexicon.intentWords[intent].orEmpty())).isNotEmpty()
        val noDocument = docTypes.isEmpty() && template == null
        // Photos only when no document is named: "restaurant bill screenshot" is still a document search.
        val photoCategory = if (noDocument) photoCategoryOf(q) else null
        // Small talk only when nothing else in the message asks for a document; "hi, show my aadhaar" is a find.
        val smallTalk = noDocument && photoCategory == null && !has("reminders") && !has("pack") && !has("question") &&
            (chatKindOf(q) != null || q.none { it.isLetterOrDigit() })

        val kind = when {
            smallTalk -> IntentKind.CHAT
            photoCategory != null -> IntentKind.PHOTOS
            has("reminders") && template == null -> IntentKind.REMINDERS
            template != null -> IntentKind.PACK
            has("pack") && docTypes.isNotEmpty() -> IntentKind.FIND
            has("question") -> IntentKind.QUESTION
            else -> IntentKind.FIND
        }
        val (from, to) = dateRange(q, query, today)
        // Nomic is English-centric: lead with English labels for what we recognised, then the raw query.
        val english = buildList {
            template?.let { add(it.replace('_', ' ')) }
            docTypes.forEach { add(it.labelEn.lowercase()) }
            add(query.trim())
        }.joinToString(" ")
        return QueryIntent(
            kind = kind,
            template = template,
            docTypes = docTypes,
            queryEn = english,
            dateFrom = from,
            dateTo = to,
            language = lang,
            question = if (kind == IntentKind.QUESTION) query.trim() else null,
            source = "rules",
            photoCategory = photoCategory,
        )
    }

    private fun photoCategoryOf(normalized: String): PhotoCategory? =
        longestHits(match(normalized, lexicon.photoCategories)).firstNotNullOfOrNull { PhotoCategory.fromDb(it.key) }

    /** True for a short reply that rejects the previous answer ("that's not it", "wrong one"), not a fresh request. */
    fun isCorrection(query: String): Boolean = match(QueryText.normalize(query), lexicon.correctionWords).isNotEmpty()

    fun detectLanguage(query: String) = languageDetector.detect(query)

    /** "greeting", "thanks", "help" or "bye" when the message contains small talk of that kind, else null. */
    fun chatKind(query: String): String? = chatKindOf(QueryText.normalize(query))

    private fun chatKindOf(normalized: String): String? = longestHits(match(normalized, lexicon.chatWords)).firstOrNull()?.key

    private fun match(q: String, table: Map<String, List<String>>): List<Hit> {
        val hits = ArrayList<Hit>()
        val tokens = tokenSpans(q)
        for ((key, terms) in table) {
            for (term in terms) {
                val t = QueryText.normalize(term)
                if (t.isEmpty()) continue
                val latin = QueryText.isLatin(t)
                var from = 0
                while (true) {
                    val i = q.indexOf(t, from)
                    if (i < 0) break
                    val leftOk = i == 0 || !QueryText.isWordChar(q[i - 1])
                    // Indic words take case suffixes (ఇన్సూరెన్స్\u200Cని, बीमे का): only Latin terms need a right boundary.
                    val rightOk = !latin || i + t.length == q.length || !QueryText.isWordChar(q[i + t.length])
                    if (leftOk && rightOk) hits += Hit(key, i, i + t.length)
                    from = i + 1
                }
                if (latin && ' ' !in t && t.length >= 5) {
                    val cap = if (t.length >= 8) 2 else 1
                    for ((tok, s) in tokens) {
                        if (QueryText.isLatin(tok) && tok != t && QueryText.editDistance(tok.removeSuffix("s"), t, cap) <= cap) {
                            hits += Hit(key, s, s + tok.length)
                        }
                    }
                }
            }
        }
        return hits
    }

    /** Drops hits contained in a longer hit ("car insurance" beats the "insurance" inside it). */
    private fun longestHits(hits: List<Hit>): List<Hit> =
        hits.filter { h -> hits.none { o -> o !== h && o.start <= h.start && o.end >= h.end && (o.end - o.start) > (h.end - h.start) } }
            .sortedBy { it.start }

    private fun tokenSpans(q: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var i = 0
        while (i < q.length) {
            if (q[i] == ' ') {
                i++
                continue
            }
            val start = i
            while (i < q.length && q[i] != ' ') i++
            out += q.substring(start, i) to start
        }
        return out
    }

    private fun dateRange(q: String, raw: String, today: LocalDate): Pair<LocalDate?, LocalDate?> {
        lastNMonths.find(q)?.let { m ->
            val n = m.groupValues[1].toInt().coerceIn(1, 60)
            return today.minusMonths(n.toLong()) to today
        }
        val rel = lexicon.relativeDates.entries.firstOrNull { (_, terms) ->
            terms.any { t -> QueryText.normalize(t).let { it.isNotEmpty() && q.contains(it) } }
        }?.key
        when (rel) {
            "this_month" -> return today.withDayOfMonth(1) to today
            "last_month" -> YearMonth.from(today).minusMonths(1).let { return it.atDay(1) to it.atEndOfMonth() }
            "last_3_months" -> return today.minusMonths(3) to today
            "last_6_months" -> return today.minusMonths(6) to today
            "this_year" -> return today.withDayOfYear(1) to today
            "last_year" -> today.minusYears(1).let { return it.withDayOfYear(1) to it.withMonth(12).withDayOfMonth(31) }
        }
        val parsed = DateParser.parseLine(raw, 0, today).firstOrNull() ?: return null to null
        return if (parsed.precision == DatePrecision.MONTH) parsed.date to parsed.endDate else parsed.date to parsed.date
    }
}
