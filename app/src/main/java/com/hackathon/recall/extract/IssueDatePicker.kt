package com.hackathon.recall.extract

import com.hackathon.recall.model.DocType
import java.time.LocalDate

/**
 * Picks `issued_on`, the date recency rules use (for example "3 salary slips from the last 3 months").
 * A date next to an issue or period keyword wins. Otherwise monthly documents take their latest
 * month-precision date, and everything else takes the latest date that isn't in the future.
 */
object IssueDatePicker {
    private val KEYWORD = Regex(
        "issue|issued|dated|date of issue|bill date|invoice date|statement date|report date|pay\\s*period|for the month|salary for|month of|" +
            "payslip for|pay slip for|statement period|generated on|जारी|दिनांक|माह|తేదీ|నెల",
        RegexOption.IGNORE_CASE,
    )
    private val MONTHLY = setOf(DocType.SALARY_SLIP, DocType.BANK_STATEMENT, DocType.UTILITY_BILL)

    fun pick(lines: List<String>, dates: List<ParsedDate>, type: DocType, expiry: LocalDate?, today: LocalDate): LocalDate? {
        val usable = dates.filter { it.endDate != expiry && !it.date.isAfter(today.plusDays(1)) }
        if (usable.isEmpty()) return null
        val keyed = usable.filter { d ->
            val line = lines.getOrNull(d.line).orEmpty()
            KEYWORD.containsMatchIn(line.substring(0, minOf(line.length, d.start))) ||
                (d.line > 0 && KEYWORD.containsMatchIn(lines[d.line - 1]) && dates.none { it.line == d.line - 1 })
        }
        if (type in MONTHLY) {
            // A statement period "01/08 to 31/08" or a pay month: the latest keyed date is the month it covers.
            (keyed.ifEmpty { usable.filter { it.precision == DatePrecision.MONTH } }).maxByOrNull { it.date }?.let { return it.date }
        }
        keyed.maxByOrNull { it.date }?.let { return it.date }
        return usable.maxByOrNull { it.date }?.date
    }
}
