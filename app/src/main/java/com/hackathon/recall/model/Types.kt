package com.hackathon.recall.model

/**
 * Document types from brief §5. [hasExpiry] marks types whose validity end date matters; only these
 * may ask the LLM to pick an expiry date (a bank statement's "period to" date is not an expiry).
 */
enum class DocType(val labelEn: String, val hasExpiry: Boolean = false) {
    AADHAAR("Aadhaar card"),
    PAN("PAN card"),
    DRIVING_LICENCE("Driving licence", hasExpiry = true),
    VEHICLE_RC("Vehicle registration certificate", hasExpiry = true),
    PASSPORT("Passport", hasExpiry = true),
    VOTER_ID("Voter ID card"),
    HEALTH_ID_ABHA("ABHA health ID card"),
    HEALTH_INSURANCE("Health insurance policy", hasExpiry = true),
    VEHICLE_INSURANCE("Vehicle insurance policy", hasExpiry = true),
    LIFE_INSURANCE("Life insurance policy", hasExpiry = true),
    SALARY_SLIP("Salary slip"),
    BANK_STATEMENT("Bank statement"),
    EMPLOYMENT_LETTER("Employment letter"),
    ITR_FORM16("Income tax return or Form 16"),
    RENT_AGREEMENT("Rent agreement", hasExpiry = true),
    PROPERTY_PAPER("Property document"),
    LOAN_SANCTION_EMI("Loan sanction letter or EMI schedule"),
    MEDICAL_REPORT("Medical report"),
    HOSPITAL_BILL("Hospital bill"),
    PRESCRIPTION("Prescription"),
    UTILITY_BILL("Utility bill", hasExpiry = true),
    RECEIPT_INVOICE("Receipt or invoice"),
    PAYMENT_SCREENSHOT("Payment screenshot"),
    WARRANTY("Warranty card", hasExpiry = true),
    TICKET("Ticket"),
    OTHER_DOCUMENT("Other document");

    companion object {
        fun parse(value: String?): DocType? {
            val v = value?.trim()?.uppercase() ?: return null
            return entries.firstOrNull { it.name == v }
        }
    }
}

enum class SourceKind(val db: String) {
    GALLERY("gallery"), CAMERA("camera"), PDF("pdf");

    companion object {
        fun fromDb(v: String): SourceKind = entries.first { it.db == v }
    }
}

enum class DocTypeSource(val db: String) {
    RULE("rule"), SIGLIP("siglip"), LLM("llm"), USER("user");

    companion object {
        fun fromDb(v: String): DocTypeSource = entries.first { it.db == v }
    }
}

enum class EntityKind(val db: String) {
    AADHAAR("aadhaar"), PAN("pan"), AMOUNT("amount"), DATE("date"), POLICY_NO("policy_no"),
    VEHICLE_NO("vehicle_no"), IFSC("ifsc"), PHONE("phone"), ABHA("abha"), OTHER("other");

    companion object {
        fun fromDb(v: String): EntityKind = entries.firstOrNull { it.db == v } ?: OTHER
    }
}

/** Answer and UI languages. [tag] is the BCP-47 tag used for speech and TTS. */
enum class Lang(val code: String, val tag: String, val englishName: String) {
    EN("en", "en-IN", "English"), TE("te", "te-IN", "Telugu"), HI("hi", "hi-IN", "Hindi");

    companion object {
        fun fromCode(v: String?): Lang? = entries.firstOrNull { it.code == v?.trim()?.lowercase() }
    }
}
