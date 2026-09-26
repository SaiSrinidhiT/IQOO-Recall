package com.hackathon.recall.extract

import com.hackathon.recall.model.DocType

/**
 * Best-effort owner name, so the user can filter by person (for example "mother's insurance").
 * Returns null rather than guessing when no labelled name or known card layout is found.
 */
object OwnerNameExtractor {
    private val LABELLED = Regex(
        "(?<![A-Za-z'])(name\\s*of\\s*(?:the\\s*)?(?:employee|insured|patient|policy\\s*holder|proposer|account\\s*holder|applicant|customer)" +
            "|employee\\s*name|insured(?:\\s*(?:person|name))?|policy\\s*holder(?:'?s)?(?:\\s*name)?|proposer(?:'?s)?(?:\\s*name)?" +
            "|patient(?:'?s)?\\s*name|account\\s*holder(?:\\s*name)?|customer\\s*name|holder'?s?\\s*name|name)" +
            "\\s*[:\\-]\\s*((?:(?:mr|mrs|ms|miss|dr|shri|smt|kum)\\.?\\s+)?[A-Za-z][A-Za-z.' ]{1,40})",
        RegexOption.IGNORE_CASE,
    )
    private val EXCLUDED_LABEL = Regex(
        "father|husband|mother|nominee|bank|company|employer|hospital|doctor|branch|insurer|guardian|spouse|son|daughter|wife|product|plan|scheme|file|user",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_ONLY_LABEL = Regex("^\\s*(?:नाम\\s*/\\s*)?name\\s*:?\\s*$", RegexOption.IGNORE_CASE)
    private val DOB_LINE = Regex("(?<![a-z])dob(?![a-z])|year of birth|date of birth|जन्म|పుట్టిన", RegexOption.IGNORE_CASE)
    private val LATIN_NAME_LINE = Regex("^[A-Za-z][A-Za-z.' ]{2,40}$")
    private val TRAILING_NOISE = Regex(
        "\\s+(?:dob|date|age|gender|sex|male|female|emp|employee|id|code|no|designation|s/o|d/o|w/o)\\b.*$",
        RegexOption.IGNORE_CASE,
    )
    private val HONORIFIC = Regex("^(?:mr|mrs|ms|miss|dr|shri|smt|kum)\\.?\\s+", RegexOption.IGNORE_CASE)
    private val NOT_NAMES = setOf("government of india", "income tax department", "male", "female", "unique identification authority of india")

    fun extract(lines: List<String>, type: DocType?): String? {
        if (type == DocType.AADHAAR || type == DocType.HEALTH_ID_ABHA || type == DocType.VOTER_ID) {
            aboveDobLine(lines)?.let { return it }
        }
        for ((i, line) in lines.withIndex()) {
            if (NAME_ONLY_LABEL.matches(line) && i + 1 < lines.size) {
                clean(lines[i + 1])?.let { return it }
            }
            for (m in LABELLED.findAll(line)) {
                val label = m.groupValues[1]
                val prefix = line.substring(maxOf(0, m.range.first - 20), m.range.first)
                if (EXCLUDED_LABEL.containsMatchIn(label) || EXCLUDED_LABEL.containsMatchIn(prefix)) continue
                clean(m.groupValues[2])?.let { return it }
            }
        }
        return null
    }

    /** Aadhaar-style cards print the English name on the line just above the DOB line. */
    private fun aboveDobLine(lines: List<String>): String? {
        val dob = lines.indexOfFirst { DOB_LINE.containsMatchIn(it) }
        if (dob <= 0) return null
        for (i in dob - 1 downTo maxOf(0, dob - 2)) {
            val l = lines[i].trim()
            if (LATIN_NAME_LINE.matches(l)) clean(l)?.let { return it }
        }
        return null
    }

    private fun clean(raw: String): String? {
        var s = raw.trim().replace(TRAILING_NOISE, "").replace(HONORIFIC, "").replace(Regex("\\s+"), " ").trim(' ', '.', '\'')
        if (s.isEmpty() || s.any { it.isDigit() }) return null
        val words = s.split(' ')
        if (words.size > 5 || s.length < 3 || s.lowercase() in NOT_NAMES) return null
        s = words.joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        return s
    }
}
