package com.hackathon.recall.extract

import com.hackathon.recall.model.DocType
import java.time.LocalDate

/** A candidate expiry date with ±1 line of context, as shown to the LLM when rules can't decide. */
data class ExpiryCandidate(
    val date: LocalDate,
    val line: Int,
    val context: String,
    val keywordAdjacent: Boolean,
)

sealed interface ExpiryDecision {
    data class Picked(val date: LocalDate, val source: String) : ExpiryDecision
    data class NeedsChoice(val candidates: List<ExpiryCandidate>) : ExpiryDecision
    data object None : ExpiryDecision
}

/**
 * Expiry pick (brief §4). If exactly one distinct date sits next to an expiry keyword, it wins
 * (source "rule"). If several do, or none do on an expiry-bearing document type, the caller asks the
 * LLM to choose a candidate index ([NeedsChoice]); [fallback] is the rule used when no LLM is available.
 */
object ExpiryPicker {
    private val IC = setOf(RegexOption.IGNORE_CASE)

    private val POSITIVE = listOf(
        "valid\\s*(?:till|upto|up\\s*to|until|thru|through)", "validity", "expir(?:y|es|ing|ation|ed)",
        "(?<![a-z])exp(?:\\.|(?![a-z]))", "due\\s*date", "pay(?:able)?\\s*by", "last\\s*date",
        "renew(?:al)?", "(?:policy|cover|coverage)\\s*(?:end|expiry)", "end\\s*date",
        "समाप्ति", "वैधता", "तक\\s*(?:वैध|मान्य)", "नवीनीकरण", "देय\\s*तिथि", "अंतिम\\s*तिथि",
        "గడువు", "చెల్లుబాటు", "పునరుద్ధరణ", "ముగింపు", "చివరి\\s*తేదీ",
    ).map { Regex(it, IC) }

    private val NEGATIVE = listOf(
        "date\\s*of\\s*birth", "(?<![a-z])dob(?![a-z])", "birth", "issue(?:d)?", "valid\\s*from",
        "(?<![a-z])from(?![a-z])", "start\\s*date", "commencement", "inception",
        "(?:bill|invoice|statement|print(?:ed)?|generated)\\s*(?:date|on)",
        "date\\s*of\\s*(?:admission|discharge|registration|reg)",
        "जन्म", "जारी", "प्रारंभ", "పుట్టిన", "జారీ", "ప్రారంభ",
    ).map { Regex(it, IC) }

    private val RANGE_CONNECTOR = Regex("^\\s*(?:to|till|until|upto|up\\s*to|[-–—~]|से|నుండి|నుంచి)\\s*$", IC)
    private val PERIOD_WORD = Regex(
        "period|valid|cover|insurance|policy|tenure|term|अवधि|मान्य|बीमा|కాలం|కాలపరిమితి|చెల్లుబాటు|బీమా", IC,
    )

    fun pick(lines: List<String>, dates: List<ParsedDate>, docType: DocType?): ExpiryDecision {
        if (docType != null && !docType.hasExpiry && docType != DocType.OTHER_DOCUMENT) return ExpiryDecision.None
        if (dates.isEmpty()) return ExpiryDecision.None
        val norm = lines.map { DateParser.normalizeLine(it) }
        val rangeRole = rangeRoles(norm, dates, requirePeriodWord = docType?.hasExpiry != true)

        val candidates = dates.map { d ->
            val adjacent = rangeRole[d] ?: keywordAdjacent(norm, dates, d)
            ExpiryCandidate(d.endDate, d.line, context(norm, d.line), adjacent)
        }
        val positive = candidates.filter { it.keywordAdjacent }.distinctBy { it.date }
        return when {
            positive.size == 1 -> ExpiryDecision.Picked(positive[0].date, "rule")
            positive.size > 1 -> ExpiryDecision.NeedsChoice(positive)
            docType?.hasExpiry == true -> ExpiryDecision.NeedsChoice(candidates.distinctBy { it.date }.take(8))
            else -> ExpiryDecision.None
        }
    }

    /**
     * Rule used when the LLM is unavailable: among keyword-adjacent candidates, the earliest date that
     * is today or later (remind early rather than late), else the latest past one. No keyword, no pick.
     */
    fun fallback(decision: ExpiryDecision.NeedsChoice, today: LocalDate): LocalDate? {
        val positive = decision.candidates.filter { it.keywordAdjacent }.map { it.date }
        if (positive.isEmpty()) return null
        return positive.filter { !it.isBefore(today) }.minOrNull() ?: positive.max()
    }

    /**
     * "01/04/2025 to 31/03/2026": the end date is the expiry and the start is not. On document types
     * without a known expiry, the line (or the one above) must also mention a period or validity.
     */
    private fun rangeRoles(lines: List<String>, dates: List<ParsedDate>, requirePeriodWord: Boolean): Map<ParsedDate, Boolean> {
        val roles = HashMap<ParsedDate, Boolean>()
        for ((lineIx, onLine) in dates.filter { it.precision == DatePrecision.DAY }.groupBy { it.line }) {
            val sorted = onLine.sortedBy { it.start }
            val line = lines[lineIx]
            val periodContext = PERIOD_WORD.containsMatchIn(line) ||
                (lineIx > 0 && PERIOD_WORD.containsMatchIn(lines[lineIx - 1]))
            if (requirePeriodWord && !periodContext) continue
            for (i in 0 until sorted.size - 1) {
                val a = sorted[i]
                val b = sorted[i + 1]
                if (RANGE_CONNECTOR.matches(line.substring(a.end, b.start)) && b.date.isAfter(a.date)) {
                    roles[a] = false
                    roles[b] = true
                }
            }
        }
        return roles
    }

    private fun keywordAdjacent(lines: List<String>, dates: List<ParsedDate>, d: ParsedDate): Boolean {
        val line = lines[d.line]
        // Nearest keyword before the date on the same line decides.
        val before = line.substring(0, d.start)
        val datesBefore = dates.filter { it.line == d.line && it.end <= d.start }
        val lastPos = POSITIVE.maxOfOrNull { lastEnd(it, before) } ?: -1
        val lastNeg = NEGATIVE.maxOfOrNull { lastEnd(it, before) } ?: -1
        val prevDateEnd = datesBefore.maxOfOrNull { it.end } ?: -1
        if (lastPos > lastNeg && lastPos > prevDateEnd) return true
        if (lastNeg >= 0 && lastNeg >= lastPos) return false
        if (lastPos >= 0 && lastPos > lastNeg) return false // keyword belongs to an earlier date
        // Label on the line above (or below) with no date of its own.
        for (adj in listOf(d.line - 1, d.line + 1)) {
            if (adj !in lines.indices || dates.any { it.line == adj }) continue
            val l = lines[adj]
            if (POSITIVE.any { it.containsMatchIn(l) } && NEGATIVE.none { it.containsMatchIn(l) }) return true
        }
        return false
    }

    private fun lastEnd(re: Regex, s: String): Int = re.findAll(s).lastOrNull()?.range?.last?.plus(1) ?: -1

    private fun context(lines: List<String>, line: Int): String =
        (maxOf(0, line - 1)..minOf(lines.lastIndex, line + 1)).joinToString(" | ") { lines[it].trim() }
}
