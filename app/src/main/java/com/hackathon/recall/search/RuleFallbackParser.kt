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
        val template = templateHits.firstOrNull()?.key
        fun has(intent: String) = match(q, mapOf(intent to lexicon.intentWords[intent].orEmpty())).isNotEmpty()

        // "Everything except Aadhaar", "not my PAN, my Aadhaar": a document named after a negation is ruled
        // out, not asked for. A correction ("this is not the salary slip") still wants that type, so it is
        // not treated as a negation.
        val negated = if (isCorrection(query)) emptySet() else negatedHits(q, docTypeHits)
        val wanted = docTypeHits.filterNot { it in negated }.mapNotNull { DocType.parse(it.key) }.distinct()
        val docTypes = wanted
        val excludeTypes = negated.mapNotNull { DocType.parse(it.key) }.distinct().filterNot { it in wanted }
        val action = actionOf(q)
        val order = if (match(q, lexicon.orderWords.filterKeys { it == "oldest" }, fuzzy = false).isNotEmpty()) SortOrder.OLDEST else SortOrder.LATEST
        val listAll = match(q, lexicon.overviewWords, fuzzy = false).isNotEmpty() || (docTypes.isEmpty() && excludeTypes.isNotEmpty())

        val noDocument = docTypes.isEmpty() && template == null && excludeTypes.isEmpty() && !listAll
        // Photos only when no document is named: "restaurant bill screenshot" is still a document search.
        val photoCategory = if (noDocument) photoCategoryOf(q) else null
        val chat = chatKindOf(q)
        // Questions about the world ("what's the weather") or about the app ("is my data safe") are
        // answered in words even though they contain question words; searching documents for them was
        // how unrelated cards ended up in replies.
        val aboutAppOrWorld = noDocument && photoCategory == null && (chat == "privacy" || chat == "offtopic")
        // Small talk only when nothing else in the message asks for a document; "hi, show my aadhaar" is a find.
        val smallTalk = noDocument && photoCategory == null && action == null && !has("reminders") && !has("pack") && !has("question") &&
            (chat != null || q.none { it.isLetterOrDigit() })

        val kind = when {
            smallTalk || aboutAppOrWorld -> IntentKind.CHAT
            // "How do I apply for a passport" without a saved one to show: general advice the app can't give offline.
            noDocument && photoCategory == null && action == DocAction.ADVICE -> IntentKind.CHAT
            photoCategory != null -> IntentKind.PHOTOS
            listAll -> IntentKind.FIND
            // "Email my expired PAN", "delete my salary slip": a verb about a document, shown with guidance.
            action != null && docTypes.isNotEmpty() -> IntentKind.FIND
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
            excludeTypes = excludeTypes,
            action = action,
            order = order,
            listAll = listAll,
        )
    }

    /** What the user wants done beyond seeing it. Delete and edit win over share: they are the riskier asks to miss. */
    private fun actionOf(q: String): DocAction? {
        fun has(code: String) = match(q, mapOf(code to lexicon.actionWords[code].orEmpty()), fuzzy = false).isNotEmpty()
        return listOf(DocAction.DELETE, DocAction.EDIT, DocAction.SHARE, DocAction.ADVICE).firstOrNull { has(it.code) }
    }

    /**
     * Document hits ruled out by a negation: a "before" cue ("except", "not") at most two words ahead of
     * the name, or an "after" cue ("kakunda", "ke alawa") right after it, with no other document named in
     * between ("not my PAN, my Aadhaar" rules out only the PAN).
     */
    private fun negatedHits(q: String, hits: List<Hit>): Set<Hit> {
        if (hits.isEmpty()) return emptySet()
        val tokenStarts = tokenSpans(q).map { it.second }
        fun wordsBetween(from: Int, to: Int) = tokenStarts.count { it in from until to }
        fun docBetween(from: Int, to: Int) = hits.any { it.start in from until to }
        val before = match(q, mapOf("b" to lexicon.negationWords["before"].orEmpty()), fuzzy = false)
        val after = match(q, mapOf("a" to lexicon.negationWords["after"].orEmpty()), fuzzy = false)
        return hits.filter { h ->
            before.any { c -> c.end <= h.start && wordsBetween(c.end, h.start) <= 2 && !docBetween(c.end, h.start) } ||
                after.any { c -> c.start >= h.end && wordsBetween(h.end, c.start) <= 1 && !docBetween(h.end, c.start) }
        }.toSet()
    }

    /** Words in a message that point back at the previous reply. See [FollowUp]. */
    data class FollowUpCue(
        val reference: Boolean,
        val attribute: Boolean,
        val older: Boolean,
        val ownerSwitch: Boolean,
        /** 0, 1, 2 for first/second/third, -1 for the last one. */
        val ordinal: Int?,
        val action: DocAction?,
    ) {
        val any: Boolean get() = reference || attribute || older || ownerSwitch || ordinal != null || action != null
    }

    fun followUpCue(query: String): FollowUpCue {
        val q = QueryText.normalize(query)
        fun has(key: String) = match(q, mapOf(key to lexicon.followUpWords[key].orEmpty()), fuzzy = false).isNotEmpty()
        val ordinal = when {
            has("first") -> 0
            has("second") -> 1
            has("third") -> 2
            has("last") -> -1
            else -> null
        }
        // "sai's?", "and my wife's?": a bare possessive swaps the person, keeps the document type.
        val possessive = POSSESSIVE.matches(query.trim())
        return FollowUpCue(has("reference"), has("attribute"), has("older"), has("owner_switch") || possessive, ordinal, actionOf(q))
    }

    private fun photoCategoryOf(normalized: String): PhotoCategory? =
        longestHits(match(normalized, lexicon.photoCategories)).firstNotNullOfOrNull { PhotoCategory.fromDb(it.key) }

    /** True for a short reply that rejects the previous answer ("that's not it", "wrong one"), not a fresh request. */
    fun isCorrection(query: String): Boolean {
        val q = QueryText.normalize(query)
        // A bare "no" right after a reply rejects it; inside a sentence ("I have no passport") it doesn't.
        return q in BARE_NO || match(q, lexicon.correctionWords, fuzzy = false).isNotEmpty()
    }

    fun detectLanguage(query: String) = languageDetector.detect(query)

    /** "greeting", "thanks", "help" or "bye" when the message contains small talk of that kind, else null. */
    fun chatKind(query: String): String? = chatKindOf(QueryText.normalize(query))

    private fun chatKindOf(normalized: String): String? = longestHits(match(normalized, lexicon.chatWords)).firstOrNull()?.key

    /** [fuzzy] allows one or two typos in longer single words; off for function words ("other" ≠ "mother"). */
    private fun match(q: String, table: Map<String, List<String>>, fuzzy: Boolean = true): List<Hit> {
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
                if (fuzzy && latin && ' ' !in t && t.length >= 5) {
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
        DateParser.parseLine(raw, 0, today).firstOrNull()?.let { parsed ->
            return if (parsed.precision == DatePrecision.MONTH) parsed.date to parsed.endDate else parsed.date to parsed.date
        }
        return bareMonth(q, today) ?: (null to null)
    }

    /**
     * "salary slip from march", "march 2025": the whole month, and without a year the most recent one
     * that has started (in September, "march" is this March; in February, last March). "may" counts only
     * with a year or after "in/from/of/for", since "may I see my PAN" is not a date.
     */
    private fun bareMonth(q: String, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val m = MONTH.find(q) ?: return null
        val name = m.groupValues[1]
        val year = m.groupValues[2].toIntOrNull()
        // "may" ("may I see…") and abbreviations ("Jan Dhan passbook") are dates only with a year or a preposition.
        val needsCue = name == "may" || name !in FULL_MONTHS
        if (needsCue && year == null && !Regex("(?:in|from|of|for|during|since) ${Regex.escape(name)}(?![a-z])").containsMatchIn(q)) return null
        val month = MONTHS.indexOfFirst { name.startsWith(it) } + 1
        if (month == 0) return null
        val y = year ?: if (month > today.monthValue) today.year - 1 else today.year
        val ym = YearMonth.of(y, month)
        return ym.atDay(1) to ym.atEndOfMonth()
    }

    private companion object {
        val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        val BARE_NO = setOf("no", "nope", "nah", "no no", "nahi", "nahin", "kaadu", "kadu", "illa", "नहीं", "नही", "కాదు")
        val FULL_MONTHS = setOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        val MONTH = Regex("(?<![a-z])(jan(?:uary)?|feb(?:ruary)?|march|mar|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)(?![a-z])(?:\\s+((?:19|20)\\d{2}))?")
        val POSSESSIVE = Regex("(?i)^(?:and\\s+|what about\\s+)?(?:my\\s+)?[\\p{L}]+['’]s\\s*\\??$")
    }
}
