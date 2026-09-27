package com.hackathon.recall.extract

import com.hackathon.recall.model.EntityKind

/** An entity found in OCR text. [start]/[end] are offsets into the digit-normalized text. */
data class ExtractedEntity(
    val kind: EntityKind,
    val value: String,
    val masked: String,
    val start: Int,
    val end: Int,
)

/**
 * Deterministic entity extraction (brief §4). Regex is authoritative for these kinds; every Aadhaar
 * candidate must pass Verhoeff. Input should already be digit-normalized (see [DigitNormalizer]).
 */
object EntityExtractor {
    private const val SEP = "[ \\u00A0-]{0,2}"

    /** 4-4-4 digits, not part of a longer digit run (for example a 16-digit VID). */
    val AADHAAR = Regex("(?<!\\d)(?<!\\d[ \\u00A0-]{1,2})([2-9]\\d{3})$SEP(\\d{4})$SEP(\\d{4})(?!$SEP\\d)")
    val PAN = Regex("(?<![A-Z0-9])([A-Z]{5}[0-9]{4}[A-Z])(?![A-Z0-9])")
    private const val STATES =
        "AN|AP|AR|AS|BR|CH|CG|DD|DL|DN|GA|GJ|HR|HP|JK|JH|KA|KL|LA|LD|MP|MH|MN|ML|MZ|NL|OD|OR|PY|PB|RJ|SK|TN|TS|TG|TR|UP|UK|UA|WB"
    val VEHICLE = Regex("(?<![A-Z0-9])($STATES)[ .-]?(\\d{1,2})[ .-]?([A-Z]{1,3})[ .-]?(\\d{4})(?![A-Z0-9])")
    val VEHICLE_BH = Regex("(?<![A-Z0-9])(\\d{2})[ .-]?BH[ .-]?(\\d{4})[ .-]?([A-Z]{1,2})(?![A-Z0-9])")
    val IFSC = Regex("(?<![A-Z0-9])([A-Z]{4}0[A-Z0-9]{6})(?![A-Z0-9])")
    private val POLICY = Regex(
        "(?:policy|पॉलिसी|पालिसी|పాలసీ)\\s*(?:no\\.?|number|num\\.?|#|नंबर|संख्या|सं\\.|నంబర్|సంఖ్య)\\s*[:.#-]*\\s*([A-Z0-9][A-Z0-9/-]{5,29})",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT_CURRENCY = Regex(
        "(?:₹|(?<![A-Za-z])Rs\\.?|(?<![A-Za-z])INR|रु\\.?|రూ\\.?)\\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)",
    )
    private val AMOUNT_KEYWORD = Regex(
        "(?<![A-Za-z])(?:net\\s*pay|net\\s*salary|gross\\s*(?:pay|salary|earnings)?|total(?:\\s*amount)?|amount\\s*(?:payable|due|paid)?|sum\\s*(?:insured|assured)|premium|balance)\\s*[:=-]?\\s*([0-9]{1,3}(?:,[0-9]{2,3})+(?:\\.[0-9]{1,2})?|[0-9]{3,}(?:\\.[0-9]{1,2})?)(?![0-9])",
        RegexOption.IGNORE_CASE,
    )
    val PHONE = Regex("(?<![\\d+])(?:\\+91[\\s-]?|0)?([6-9]\\d{4}[\\s-]?\\d{5})(?!\\d)")
    val ABHA_NUMBER = Regex("(?<!\\d)(\\d{2})[ -](\\d{4})[ -](\\d{4})[ -](\\d{4})(?!\\d)")
    private val ABHA_ADDRESS = Regex("(?<![A-Za-z0-9._])([A-Za-z0-9._]{3,})@(abdm|sbx)(?![A-Za-z])", RegexOption.IGNORE_CASE)

    fun extract(normalizedText: String): List<ExtractedEntity> {
        val out = ArrayList<ExtractedEntity>()
        val t = normalizedText

        for (m in AADHAAR.findAll(t)) {
            val digits = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
            if (Verhoeff.isValidAadhaar(digits)) {
                out += ExtractedEntity(EntityKind.AADHAAR, digits, maskAadhaar(digits), m.range.first, m.range.last + 1)
            }
        }
        for (m in ABHA_NUMBER.findAll(t)) {
            val digits = m.groupValues.drop(1).joinToString("")
            out += ExtractedEntity(EntityKind.ABHA, digits, "XX-XXXX-XXXX-" + digits.takeLast(4), m.range.first, m.range.last + 1)
        }
        for (m in ABHA_ADDRESS.findAll(t)) {
            val v = m.value.lowercase()
            out += ExtractedEntity(EntityKind.ABHA, v, v, m.range.first, m.range.last + 1)
        }
        for (m in PAN.findAll(t)) {
            val v = m.groupValues[1]
            out += ExtractedEntity(EntityKind.PAN, v, "XXXXXX" + v.takeLast(4), m.range.first, m.range.last + 1)
        }
        for (m in VEHICLE.findAll(t)) {
            val v = m.groupValues.drop(1).joinToString("")
            out += ExtractedEntity(EntityKind.VEHICLE_NO, v, v, m.range.first, m.range.last + 1)
        }
        for (m in VEHICLE_BH.findAll(t)) {
            val v = m.groupValues[1] + "BH" + m.groupValues[2] + m.groupValues[3]
            out += ExtractedEntity(EntityKind.VEHICLE_NO, v, v, m.range.first, m.range.last + 1)
        }
        for (m in IFSC.findAll(t)) {
            val v = m.groupValues[1]
            out += ExtractedEntity(EntityKind.IFSC, v, v, m.range.first, m.range.last + 1)
        }
        for (m in POLICY.findAll(t)) {
            val v = m.groupValues[1].uppercase()
            if (v.count { it.isDigit() } >= 3) {
                val g = m.groups[1]!!
                out += ExtractedEntity(EntityKind.POLICY_NO, v, v, g.range.first, g.range.last + 1)
            }
        }
        val amountSpans = ArrayList<IntRange>()
        for (re in listOf(AMOUNT_CURRENCY, AMOUNT_KEYWORD)) {
            for (m in re.findAll(t)) {
                val g = m.groups[1]!!
                if (amountSpans.any { it.first <= g.range.last && g.range.first <= it.last }) continue
                amountSpans += g.range
                val v = g.value.replace(",", "")
                out += ExtractedEntity(EntityKind.AMOUNT, v, v, g.range.first, g.range.last + 1)
            }
        }
        val taken = out.filter { it.kind == EntityKind.AADHAAR || it.kind == EntityKind.ABHA }.map { it.start until it.end }
        for (m in PHONE.findAll(t)) {
            if (taken.any { it.first <= m.range.last && m.range.first <= it.last }) continue
            val digits = m.groupValues[1].filter { it.isDigit() }
            out += ExtractedEntity(EntityKind.PHONE, digits, "XXXXXX" + digits.takeLast(4), m.range.first, m.range.last + 1)
        }
        return out.sortedBy { it.start }
    }

    fun maskAadhaar(digits: String): String = "XXXX XXXX " + digits.takeLast(4)

    /**
     * [text] with every Verhoeff-valid Aadhaar number replaced by its masked form, for anything shown
     * as free text (chat answers): the full number stays only inside the document itself. Other
     * 12-digit numbers (account numbers) fail the checksum and are left as they are.
     */
    fun maskAadhaarIn(text: String): String = AADHAAR.replace(text) { m ->
        val digits = DigitNormalizer.normalize(m.groupValues[1] + m.groupValues[2] + m.groupValues[3])
        if (Verhoeff.isValidAadhaar(digits)) maskAadhaar(digits) else m.value
    }

    /** All Verhoeff-valid Aadhaar numbers in [text] (digits only). */
    fun aadhaarNumbers(text: String): List<String> =
        AADHAAR.findAll(DigitNormalizer.normalize(text))
            .map { it.groupValues[1] + it.groupValues[2] + it.groupValues[3] }
            .filter { Verhoeff.isValidAadhaar(it) }
            .toList()
}
