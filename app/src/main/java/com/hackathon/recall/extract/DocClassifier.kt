package com.hackathon.recall.extract

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.DocTypeSource
import com.hackathon.recall.model.EntityKind
import java.text.Normalizer

data class Classification(
    val type: DocType,
    val confidence: Float,
    val source: DocTypeSource,
    /** True when rules and SigLIP could not decide and the LLM should choose (brief §5 step 3). */
    val needsLlm: Boolean,
    val scores: Map<DocType, Double>,
)

/**
 * Document-type classification, steps 1 and 2 of brief §5: deterministic OCR rules, then SigLIP2
 * hints combined with keyword scores. Step 3 (Qwen) and step 4 (user correction) happen outside.
 */
object DocClassifier {
    private class Rule(val pattern: Regex, val weight: Double)

    private fun en(words: String, w: Double) =
        Rule(Regex("(?<![a-z])(?:$words)(?![a-z])", RegexOption.IGNORE_CASE), w)

    private fun any(words: String, w: Double) = Rule(Regex(words, RegexOption.IGNORE_CASE), w)

    private val RULES: Map<DocType, List<Rule>> = mapOf(
        DocType.AADHAAR to listOf(
            en("aadhaa?r|aadhar", 3.0), any("आधार|ఆధార్", 3.0),
            en("uidai|unique identification authority", 3.0), en("enrol?ment\\s*no", 2.0),
            any("government of india|भारत सरकार|భారత ప్రభుత్వం", 1.0), en("vid", 1.0),
            any("year of birth|(?<![a-z])dob(?![a-z])|जन्म तिथि|పుట్టిన తేదీ", 0.5),
        ),
        DocType.PAN to listOf(
            any("permanent account number|स्थायी लेखा संख्या", 4.0),
            any("income tax department|आयकर विभाग|ఆదాయపు పన్ను శాఖ", 2.5), en("pan", 1.0),
        ),
        DocType.DRIVING_LICENCE to listOf(
            any("driving licen[cs]e|ड्राइविंग लाइसेंस|డ్రైవింగ్ లైసెన్స్", 4.0),
            any("transport department|परिवहन विभाग|రవాణా శాఖ", 1.5),
            en("cov|class of vehicle|lmv|mcwg|non-transport", 1.5), en("dl\\s*no", 2.0),
        ),
        DocType.VEHICLE_RC to listOf(
            en("certificate of registration|registration certificate|rc", 3.0), en("chassis", 2.0),
            en("engine\\s*(?:no|number)", 1.5), en("registering authority|regn\\.?\\s*no|date of reg(?:istration)?", 2.0),
            en("maker|seating capacity|fuel", 0.5),
        ),
        DocType.PASSPORT to listOf(
            any("passport|पासपोर्ट|పాస్\u200Cపోర్ట్|పాస్పోర్ట్", 3.0), any("republic of india|भारत गणराज्य", 1.5),
            any("p<ind", 4.0), en("place of issue|nationality", 1.0),
        ),
        DocType.VOTER_ID to listOf(
            any("election commission|निर्वाचन आयोग|ఎన్నికల సంఘం", 4.0), en("elector|electoral|epic", 2.0),
            any("(?<![a-z])voter|मतदाता|ఓటరు", 2.0),
        ),
        DocType.HEALTH_ID_ABHA to listOf(
            any("(?<![a-z])abha(?![a-z])|ayushman bharat health account|आभा", 4.0), any("@abdm|@sbx", 3.0),
            en("health id|national health authority|ndhm|abdm", 2.0),
        ),
        DocType.HEALTH_INSURANCE to listOf(
            any("health insurance|mediclaim|family floater|स्वास्थ्य बीमा|ఆరోగ్య బీమా", 3.0), en("sum insured", 2.0),
            en("hospitali[sz]ation|cashless|tpa|network hospital", 1.5),
            en("policy\\s*(?:no|number|period|schedule)|policy\\s*holder|insured", 1.0), en("premium", 0.5),
            any("बीमा|బీమా", 1.0),
        ),
        DocType.VEHICLE_INSURANCE to listOf(
            en("motor insurance|private car|two wheeler|vehicle insurance|package policy|motor policy|liability only|third party", 3.0),
            en("idv|insured declared value", 3.0), en("chassis|engine\\s*no|registration\\s*no", 1.0),
            en("policy\\s*(?:no|number|period|schedule)", 1.0), any("वाहन बीमा|వాహన బీమా", 3.0),
        ),
        DocType.LIFE_INSURANCE to listOf(
            en("life insurance|lic|term (?:plan|insurance)|jeevan|endowment", 3.0),
            en("sum assured|maturity|death benefit|nominee", 2.0), en("policy\\s*(?:no|number)", 1.0),
            any("जीवन बीमा|జీవిత బీమా", 3.0),
        ),
        DocType.SALARY_SLIP to listOf(
            en("pay\\s*slip|salary slip|salary statement", 4.0), any("वेतन पर्ची|వేతన|జీతం", 4.0),
            en("net pay|net salary|take home", 2.5), en("earnings", 1.5), en("deductions", 1.5),
            en("basic|hra|provident fund|pf|esi|professional tax", 1.0), en("gross (?:salary|earnings|pay)", 1.5),
            en("employee\\s*(?:id|code|name|no)|designation", 1.0),
        ),
        DocType.BANK_STATEMENT to listOf(
            en("statement of account|account statement|bank statement", 4.0), any("खाता विवरण|ఖాతా స్టేట్మెంట్|ఖాతా వివరాలు", 4.0),
            en("opening balance|closing balance", 2.5), en("withdrawals?|deposits?|debit|credit", 1.0),
            en("ifsc", 1.0), en("txn|transaction date|value date|cheque", 1.0), en("account\\s*(?:no|number)", 1.0),
        ),
        DocType.EMPLOYMENT_LETTER to listOf(
            en("offer letter|appointment letter|employment letter|experience letter|relieving letter|employment certificate", 4.0),
            en("to whom it may concern", 2.0), en("date of joining|joining date|ctc|cost to company", 1.5),
            en("designation", 1.0), en("we are pleased|hereby", 1.0), en("hr manager|human resources", 1.0),
        ),
        DocType.ITR_FORM16 to listOf(
            en("form\\s*(?:no\\.?\\s*)?16|form-16", 4.0), en("itr[-\\s]?v|income tax return|acknowledgement number", 4.0),
            en("assessment year|financial year", 2.0), en("tds|tax deducted", 1.5), any("आयकर रिटर्न", 3.0),
        ),
        DocType.RENT_AGREEMENT to listOf(
            en("rent(?:al)? agreement|lease (?:deed|agreement)|leave and licen[cs]e", 4.0), any("किराया|किरायानामा|అద్దె", 4.0),
            en("lessor|lessee|licensor|licensee|landlord|tenant", 2.5), en("monthly rent|security deposit", 2.0),
            en("stamp duty|e-stamp|non judicial", 1.0),
        ),
        DocType.PROPERTY_PAPER to listOf(
            en("sale deed|gift deed|encumbrance|khata|patta|pattadar|conveyance deed|mutation|title deed", 4.0),
            any("पट्टा|बैनामा|దస్తావేజు|పట్టా", 4.0), en("survey\\s*no|sy\\.?\\s*no|plot\\s*no|boundaries", 2.0),
            en("sub.?registrar|sro", 2.0), en("property tax|house tax", 2.0),
        ),
        DocType.LOAN_SANCTION_EMI to listOf(
            en("sanction letter|loan sanction|sanctioned amount|loan agreement", 4.0),
            en("emi|equated monthly|repayment schedule|amorti[sz]ation", 3.0),
            en("loan account|rate of interest|tenure|principal outstanding", 1.5),
        ),
        DocType.MEDICAL_REPORT to listOf(
            en("laboratory|lab report|test report|pathology|radiology|investigation", 3.0),
            en("reference (?:range|interval)|biological ref", 3.0),
            en("ha?emoglobin|glucose|cholesterol|creatinine|platelet|wbc|rbc|tsh|hba1c|bilirubin", 2.0),
            en("specimen|sample (?:type|collected)", 1.5), en("impression|findings|diagnosis|x-ray|mri|ct scan|ultrasound|ecg", 1.5),
            any("जांच|जाँच|रिपोर्ट|పరీక్ష|నివేదిక", 1.0),
        ),
        DocType.HOSPITAL_BILL to listOf(
            any("hospital|अस्पताल|हॉस्पिटल|ఆసుపత్రి|హాస్పిటల్", 1.5),
            en("final bill|ip bill|interim bill|discharge|in-?patient|ipd|room (?:rent|charges)", 3.0),
            en("(?:total|net|bill) amount|amount payable|gst", 1.0), en("patient name|uhid|admission|ip no", 1.5),
        ),
        DocType.PRESCRIPTION to listOf(
            any("(?<![a-z])rx(?![a-z])|℞|prescription|prescribed|नुस्खा|ప్రిస్క్రిప్షన్", 3.0),
            en("tab\\.?|cap\\.?|tablet|capsule|syrup|mg|dosage", 1.5),
            any("(?<!\\d)[01]-[01]-[01](?!\\d)|(?<![a-z])(?:od|bd)(?![a-z])|after food|before food", 2.0),
            en("reg\\.?\\s*no|mbbs|clinic", 1.0), en("dr\\.", 0.5),
        ),
        DocType.UTILITY_BILL to listOf(
            en("electricity|bijli|water (?:bill|supply)|gas (?:bill|connection)|broadband|postpaid|telephone bill|dth", 3.0),
            any("बिजली|విద్యుత్", 3.0), en("consumer\\s*(?:no|number)|service\\s*(?:no|number)|units consumed|meter\\s*(?:no|reading)", 2.5),
            en("bill\\s*(?:date|period|no)|amount (?:due|payable)|due date|pay by", 1.5),
            en("tsspdcl|apspdcl|bescom|msedcl|tata power|airtel|jio|bsnl|act fibernet", 2.0),
        ),
        DocType.RECEIPT_INVOICE to listOf(
            en("tax invoice|invoice|receipt|cash memo|bill\\s*no", 2.5), any("रसीद|చెల్లింపు రశీదు|రశీదు", 2.5),
            en("gstin|gst\\s*(?:no|in)|hsn|cgst|sgst|igst", 2.0), en("qty|quantity|rate|amount|total", 1.0),
        ),
        DocType.PAYMENT_SCREENSHOT to listOf(
            en("upi|bhim|google pay|gpay|phonepe|paytm|amazon pay", 2.5),
            en("transaction (?:id|successful)|utr|upi (?:ref|transaction)(?: id)?|paid to|payment (?:successful|completed)|money sent|debited from", 3.0),
            any("@ok(?:axis|hdfc|icici|sbi)|@ybl|@paytm|@upi|@ibl|@axl", 2.0),
        ),
        DocType.WARRANTY to listOf(
            en("warranty|guarantee card|warranty card", 4.0), any("वारंटी|వారంటీ", 4.0),
            en("serial\\s*(?:no|number)|model\\s*(?:no|number)|date of purchase|dealer", 1.5),
        ),
        DocType.RESUME to listOf(
            en("curriculum vitae|r[eé]sum[eé]|bio-?data", 3.0), any("बायोडाटा|रिज़्यूमे|బయోడేటా|రెజ్యూమే", 3.0),
            en("work experience|professional experience|employment history|career objective|professional summary", 2.0),
            en("education|academic qualifications?|technical skills|key skills|certifications|projects|internships?", 1.0),
            en("linkedin|github\\.com|portfolio", 1.0),
        ),
        DocType.TICKET to listOf(
            en("pnr|boarding pass|e-?ticket|electronic reservation slip|irctc|berth|seat\\s*no|departure|arrival", 2.5),
            en("passenger|journey|train\\s*no|flight", 1.0),
        ),
    )

    private val DL_NUMBER = Regex("(?<![A-Z0-9])[A-Z]{2}[- ]?\\d{2}[- ]?(?:19|20)\\d{2}\\d{7}(?![A-Z0-9])")
    private val EPIC = Regex("(?<![A-Z0-9])[A-Z]{3}\\d{7}(?![A-Z0-9])")
    private val MRZ = Regex("P<IND", RegexOption.IGNORE_CASE)

    /**
     * SigLIP2 gatekeeper labels that hint at document types (brief §3.1 label set), with a weight per
     * type: a label's primary type gets full weight; peers (the several ID cards) share it.
     */
    val SIGLIP_TYPE_HINTS: Map<String, List<Pair<DocType, Double>>> = mapOf(
        "a photo of an identity card" to listOf(
            DocType.AADHAAR to 0.4, DocType.PAN to 0.4, DocType.DRIVING_LICENCE to 0.4, DocType.VOTER_ID to 0.4,
            DocType.HEALTH_ID_ABHA to 0.4, DocType.PASSPORT to 0.4,
        ),
        "a screenshot of a payment receipt" to listOf(DocType.PAYMENT_SCREENSHOT to 1.0),
        "a printed bill or invoice" to listOf(DocType.RECEIPT_INVOICE to 1.0, DocType.UTILITY_BILL to 0.6),
        "a bank statement page" to listOf(DocType.BANK_STATEMENT to 1.0),
        "a salary slip" to listOf(DocType.SALARY_SLIP to 1.0),
        "an insurance policy document" to listOf(DocType.HEALTH_INSURANCE to 0.6, DocType.VEHICLE_INSURANCE to 0.6, DocType.LIFE_INSURANCE to 0.6),
        "a medical report" to listOf(DocType.MEDICAL_REPORT to 1.0, DocType.PRESCRIPTION to 0.4),
        "a hospital bill" to listOf(DocType.HOSPITAL_BILL to 1.0),
    )

    fun keywordScores(text: String): Map<DocType, Double> {
        val t = Normalizer.normalize(DigitNormalizer.normalize(text), Normalizer.Form.NFC)
        val scores = HashMap<DocType, Double>()
        for ((type, rules) in RULES) {
            val s = rules.sumOf { if (it.pattern.containsMatchIn(t)) it.weight else 0.0 }
            if (s > 0) scores[type] = s
        }
        return scores
    }

    /**
     * @param siglipLabelProbs probability per SigLIP2 label (softmax over labels), or empty when the
     *   vision model is unavailable.
     */
    fun classify(
        ocrText: String,
        entities: List<ExtractedEntity>,
        siglipLabelProbs: Map<String, Float> = emptyMap(),
        expectedType: DocType? = null,
    ): Classification {
        val scores = HashMap(keywordScores(ocrText))
        val t = DigitNormalizer.normalize(ocrText)
        val kinds = entities.map { it.kind }.toSet()

        // Step 1: deterministic rules. A strong rule adds a large boost instead of short-circuiting, so a
        // Form 16 carrying a PAN still ranks as ITR_FORM16 when its own keywords are stronger.
        var hardRule = false
        fun boost(type: DocType, by: Double) {
            scores[type] = (scores[type] ?: 0.0) + by
            hardRule = true
        }
        if (EntityKind.AADHAAR in kinds && (scores[DocType.AADHAAR] ?: 0.0) >= 1.0) boost(DocType.AADHAAR, 10.0)
        if (EntityKind.PAN in kinds && (scores[DocType.PAN] ?: 0.0) >= 2.5 && (scores[DocType.ITR_FORM16] ?: 0.0) < 3.0) {
            boost(DocType.PAN, 10.0)
        }
        if (DL_NUMBER.containsMatchIn(t) && (scores[DocType.DRIVING_LICENCE] ?: 0.0) >= 4.0) boost(DocType.DRIVING_LICENCE, 8.0)
        if (EPIC.containsMatchIn(t) && (scores[DocType.VOTER_ID] ?: 0.0) >= 2.0) boost(DocType.VOTER_ID, 8.0)
        if (EntityKind.ABHA in kinds && (scores[DocType.HEALTH_ID_ABHA] ?: 0.0) >= 2.0) boost(DocType.HEALTH_ID_ABHA, 8.0)
        if (MRZ.containsMatchIn(t)) boost(DocType.PASSPORT, 10.0)
        if (EntityKind.VEHICLE_NO in kinds) {
            scores[DocType.VEHICLE_RC]?.let { scores[DocType.VEHICLE_RC] = it + 1.0 }
            scores[DocType.VEHICLE_INSURANCE]?.let { scores[DocType.VEHICLE_INSURANCE] = it + 1.0 }
        }
        if (EntityKind.PAN in kinds) scores[DocType.PAN]?.let { scores[DocType.PAN] = it + 1.5 }

        val kw = ranked(scores)
        if (hardRule && kw.isNotEmpty()) {
            return Classification(kw[0].key, 0.97f, DocTypeSource.RULE, needsLlm = false, scores = scores)
        }
        if (kw.isNotEmpty() && kw[0].value >= 4.0 && kw[0].value - (kw.getOrNull(1)?.value ?: 0.0) >= 2.0) {
            return Classification(kw[0].key, confidence(kw), DocTypeSource.RULE, needsLlm = false, scores = scores)
        }

        // Step 2: SigLIP2 hint + keyword scores. A camera scan launched for a missing item also hints.
        val combined = HashMap(scores)
        for ((label, p) in siglipLabelProbs) {
            for ((type, w) in SIGLIP_TYPE_HINTS[label].orEmpty()) combined[type] = (combined[type] ?: 0.0) + 3.0 * p * w
        }
        if (expectedType != null) combined[expectedType] = (combined[expectedType] ?: 0.0) + 1.5
        val c = ranked(combined)
        val usedHints = siglipLabelProbs.isNotEmpty() || expectedType != null
        if (c.isNotEmpty() && c[0].value >= 2.5 && c[0].value - (c.getOrNull(1)?.value ?: 0.0) >= 1.2) {
            return Classification(c[0].key, confidence(c), if (usedHints) DocTypeSource.SIGLIP else DocTypeSource.RULE, false, combined)
        }

        // Undecided: best guess for now; the LLM (step 3) may replace it.
        val best = c.firstOrNull()?.takeIf { it.value >= 1.5 }?.key ?: DocType.OTHER_DOCUMENT
        return Classification(best, 0.3f, if (usedHints) DocTypeSource.SIGLIP else DocTypeSource.RULE, needsLlm = true, scores = combined)
    }

    private fun ranked(m: Map<DocType, Double>) = m.entries.filter { it.value > 0 }.sortedByDescending { it.value }

    private fun confidence(ranked: List<Map.Entry<DocType, Double>>): Float {
        val margin = ranked[0].value - (ranked.getOrNull(1)?.value ?: 0.0)
        return (0.5 + 0.08 * margin).coerceIn(0.5, 0.92).toFloat()
    }
}
