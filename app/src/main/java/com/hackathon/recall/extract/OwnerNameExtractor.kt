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

    /**
     * Field labels and address grammar, not place names: "Ongole" is rejected because it sits beside
     * "VTC"/"District"/"S/O", the same way "Guntur" or "Kanpur" would be. Aadhaar's own back-of-card
     * labels (VTC, PO, District, State, Pin code) are all here.
     */
    private val ADDRESS_MARKER = Regex(
        "(?<![a-z])(?:address|add?r|c/o|s/o|d/o|w/o|vtc|p\\.?o\\.?(?![a-z])|post\\s*office|village|town|mandal|taluk|tehsil|" +
            "dist(?:rict)?|sub\\s*district|state|pin\\s*-?\\s*code|pincode|street|road|lane|cross|nagar|colony|" +
            "h\\.?\\s*no|house\\s*no|door\\s*no|flat|plot|sector|landmark|near|opp(?:osite)?|behind)(?![a-z])" +
            "|पता|मकान|गली|जिला|गाँव|गांव|राज्य|चिरुनामा|చిరునామా|జిల్లా|మండలం|గ్రామం|వీధి|ఇంటి\\s*నంబర్",
        RegexOption.IGNORE_CASE,
    )
    private val ADDRESS_LABEL = Regex("(?<![a-z])address(?![a-z])|पता|చిరునామా", RegexOption.IGNORE_CASE)
    private const val ADDRESS_BLOCK_LINES = 6

    /**
     * Only document types where "whose is this" is a real question. The catch-all `|name` alternative
     * in [LABELLED] matches any "Name: X" line, which a food-delivery receipt or payment screenshot
     * prints just as often as an ID card — running it on those produced garbage owners (a restaurant
     * name, a customer's name from someone else's order) that had nothing to do with the vault's owner.
     */
    private val OWNER_RELEVANT_TYPES = setOf(
        DocType.AADHAAR, DocType.PAN, DocType.DRIVING_LICENCE, DocType.VEHICLE_RC, DocType.PASSPORT, DocType.VOTER_ID,
        DocType.HEALTH_ID_ABHA, DocType.HEALTH_INSURANCE, DocType.VEHICLE_INSURANCE, DocType.LIFE_INSURANCE,
        DocType.SALARY_SLIP, DocType.BANK_STATEMENT, DocType.EMPLOYMENT_LETTER, DocType.ITR_FORM16,
        DocType.RENT_AGREEMENT, DocType.PROPERTY_PAPER, DocType.LOAN_SANCTION_EMI,
        DocType.MEDICAL_REPORT, DocType.HOSPITAL_BILL, DocType.PRESCRIPTION, DocType.UTILITY_BILL, DocType.RESUME,
    )

    fun extract(lines: List<String>, type: DocType?): String? {
        if (type !in OWNER_RELEVANT_TYPES) return null
        val address = addressLines(lines)
        if (type == DocType.AADHAAR || type == DocType.HEALTH_ID_ABHA || type == DocType.VOTER_ID) {
            aboveDobLine(lines, address)?.let { return it }
        }
        for ((i, line) in lines.withIndex()) {
            if (i in address) continue
            if (NAME_ONLY_LABEL.matches(line) && i + 1 < lines.size && i + 1 !in address) {
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

    /**
     * Line numbers that sit inside an address block, where place names live ("Ongole" is a town, but
     * nothing about the string says so). Detects the block by its *structure* — field labels, S/O-style
     * prefixes, a "Pin code" label — never by a list of places, so any town in any state is caught by
     * the company it keeps. Deliberately errs towards rejecting: a missed name is one tap to fix on the
     * document, while a wrong one quietly becomes a person in the vault.
     */
    private fun addressLines(lines: List<String>): Set<Int> {
        val marked = lines.indices.filterTo(HashSet()) { ADDRESS_MARKER.containsMatchIn(lines[it]) }
        // "Address:" opens a block that runs on for several lines, not just the one it labels.
        lines.forEachIndexed { i, line ->
            if (ADDRESS_LABEL.containsMatchIn(line)) marked += (i..minOf(lines.lastIndex, i + ADDRESS_BLOCK_LINES))
        }
        // An address wraps across lines, so a marked line pulls in its immediate neighbours.
        return marked.flatMapTo(HashSet()) { listOf(it - 1, it, it + 1) }.filterTo(HashSet()) { it in lines.indices }
    }

    /** Aadhaar-style cards print the English name on the line just above the DOB line. */
    private fun aboveDobLine(lines: List<String>, address: Set<Int>): String? {
        val dob = lines.indexOfFirst { DOB_LINE.containsMatchIn(it) }
        if (dob <= 0) return null
        for (i in dob - 1 downTo maxOf(0, dob - 2)) {
            if (i in address) continue
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
