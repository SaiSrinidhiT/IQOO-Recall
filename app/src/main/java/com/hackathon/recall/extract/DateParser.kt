package com.hackathon.recall.extract

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth

enum class DatePrecision { DAY, MONTH }

/**
 * A date found in one OCR line. Offsets refer to the NFC-normalized, digit-normalized line.
 * [ambiguous]: both DD/MM and MM/DD were possible; DD/MM (the Indian default) was used.
 * [monthFirst]: parsed as MM/DD because DD/MM was impossible (for example 03/25/2026).
 */
data class ParsedDate(
    val date: LocalDate,
    val precision: DatePrecision,
    val line: Int,
    val start: Int,
    val end: Int,
    val raw: String,
    val ambiguous: Boolean = false,
    val monthFirst: Boolean = false,
) {
    /** Last day this date covers: the day itself, or the month's last day for month precision. */
    val endDate: LocalDate
        get() = if (precision == DatePrecision.MONTH) YearMonth.from(date).atEndOfMonth() else date
}

/**
 * Parses DD/MM/YYYY (default), DD-MM-YY, DD.MM.YY, YYYY-MM-DD, DD-MMM-YYYY, "12 March 2026",
 * "March 12, 2026", "March 2026", "03/2026", with month names in English, Hindi and Telugu, and
 * Devanagari or Telugu digits.
 */
object DateParser {
    private val MONTHS: Map<String, Int> = listOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sept" to 9, "sep" to 9,
        "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
        "जनवरी" to 1, "फ़रवरी" to 2, "फरवरी" to 2, "मार्च" to 3, "अप्रैल" to 4, "अप्रेल" to 4,
        "मई" to 5, "जून" to 6, "जुलाई" to 7, "अगस्त" to 8, "सितंबर" to 9, "सितम्बर" to 9,
        "अक्टूबर" to 10, "अक्तूबर" to 10, "नवंबर" to 11, "नवम्बर" to 11, "दिसंबर" to 12, "दिसम्बर" to 12,
        "జనవరి" to 1, "ఫిబ్రవరి" to 2, "మార్చి" to 3, "ఏప్రిల్" to 4, "మే" to 5, "జూన్" to 6,
        "జూలై" to 7, "జులై" to 7, "ఆగస్టు" to 8, "ఆగష్టు" to 8, "ఆగస్ట్" to 8,
        "సెప్టెంబర్" to 9, "సెప్టెంబరు" to 9, "అక్టోబర్" to 10, "అక్టోబరు" to 10,
        "నవంబర్" to 11, "నవంబరు" to 11, "డిసెంబర్" to 12, "డిసెంబరు" to 12,
    ).associate { (name, month) -> nfc(name).lowercase() to month }

    private val MONTH_ALT = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private const val LM = "\\p{L}\\p{M}"
    private val IC = setOf(RegexOption.IGNORE_CASE)

    private val DMY_NAMED = Regex(
        "(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-/.,]*($MONTH_ALT)\\.?(?![$LM])[\\s\\-/.,']*(\\d{4}|\\d{2})(?!\\d)", IC,
    )
    private val MDY_NAMED = Regex(
        "(?<![$LM])($MONTH_ALT)\\.?(?![$LM])[\\s\\-/.,]*(\\d{1,2})(?:st|nd|rd|th)?,?[\\s\\-/.,]+(\\d{4})(?!\\d)", IC,
    )
    private val NUM_DMY = Regex("(?<![\\d/.\\-])(\\d{1,2})([/.\\-])(\\d{1,2})\\2(\\d{4}|\\d{2})(?![\\d/.\\-]*\\d)")
    private val NUM_ISO = Regex("(?<![\\d/.\\-])(\\d{4})([/.\\-])(\\d{1,2})\\2(\\d{1,2})(?![\\d/.\\-]*\\d)")
    private val MY_NAMED = Regex("(?<![$LM])($MONTH_ALT)\\.?(?![$LM])[\\s\\-/.,]*(\\d{4}|'\\d{2})(?!\\d)", IC)
    private val NUM_MY = Regex("(?<![\\d/.\\-])(0?[1-9]|1[0-2])([/\\-])(\\d{4})(?![\\d/.\\-]*\\d)")

    fun parse(lines: List<String>, today: LocalDate): List<ParsedDate> =
        lines.flatMapIndexed { i, line -> parseLine(line, i, today) }

    fun parseLine(rawLine: String, lineIndex: Int, today: LocalDate): List<ParsedDate> {
        val line = normalizeLine(rawLine)
        val hits = ArrayList<Pair<Int, ParsedDate>>() // priority (lower wins) to date

        fun add(priority: Int, m: MatchResult, date: LocalDate?, precision: DatePrecision, ambiguous: Boolean = false, monthFirst: Boolean = false) {
            if (date == null || date.year !in 1900..2100) return
            hits += priority to ParsedDate(date, precision, lineIndex, m.range.first, m.range.last + 1, m.value, ambiguous, monthFirst)
        }

        for (m in DMY_NAMED.findAll(line)) {
            val (d, mon, y) = m.destructured
            add(0, m, date(expandYear(y.toInt(), today), month(mon), d.toInt()), DatePrecision.DAY)
        }
        for (m in MDY_NAMED.findAll(line)) {
            val (mon, d, y) = m.destructured
            add(1, m, date(y.toInt(), month(mon), d.toInt()), DatePrecision.DAY)
        }
        for (m in NUM_DMY.findAll(line)) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[3].toInt()
            val y = expandYear(m.groupValues[4].toInt(), today)
            when {
                b in 1..12 && a in 1..31 -> add(2, m, date(y, b, a), DatePrecision.DAY, ambiguous = a in 1..12 && a != b)
                a in 1..12 && b in 13..31 -> add(2, m, date(y, a, b), DatePrecision.DAY, monthFirst = true)
            }
        }
        for (m in NUM_ISO.findAll(line)) {
            add(3, m, date(m.groupValues[1].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt()), DatePrecision.DAY)
        }
        for (m in MY_NAMED.findAll(line)) {
            val year = m.groupValues[2].removePrefix("'").toInt()
            add(4, m, date(expandYear(year, today), month(m.groupValues[1]), 1), DatePrecision.MONTH)
        }
        for (m in NUM_MY.findAll(line)) {
            add(5, m, date(m.groupValues[3].toInt(), m.groupValues[1].toInt(), 1), DatePrecision.MONTH)
        }
        return resolveOverlaps(hits)
    }

    /** Keeps non-overlapping matches, preferring longer spans and then higher-priority patterns. */
    private fun resolveOverlaps(hits: List<Pair<Int, ParsedDate>>): List<ParsedDate> {
        val ordered = hits.sortedWith(compareBy({ -(it.second.end - it.second.start) }, { it.first }, { it.second.start }))
        val kept = ArrayList<ParsedDate>()
        for ((_, d) in ordered) {
            if (kept.none { it.start < d.end && d.start < it.end }) kept += d
        }
        return kept.sortedBy { it.start }
    }

    fun normalizeLine(line: String): String = nfc(DigitNormalizer.normalize(line))

    private fun nfc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC)

    private fun month(name: String): Int = MONTHS[nfc(name).lowercase()] ?: 0

    private fun date(y: Int, m: Int, d: Int): LocalDate? =
        try {
            if (m == 0) null else LocalDate.of(y, m, d)
        } catch (_: DateTimeException) {
            null
        }

    /** Two-digit years: up to 30 years ahead of today map to 20xx, the rest to 19xx. */
    fun expandYear(y: Int, today: LocalDate): Int {
        if (y >= 100) return y
        val pivot = today.year % 100 + 30
        return if (y <= pivot) 2000 + y else 1900 + y
    }
}
