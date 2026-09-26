package com.hackathon.recall.i18n

import android.content.Context
import android.content.res.Configuration
import com.hackathon.recall.R
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import java.util.Locale

/**
 * Strings in a specific language regardless of the UI locale: answers and read-aloud follow the
 * language the user asked in (brief F4), which may differ from the app's display language.
 */
fun Context.inLang(lang: Lang): Context {
    val config = Configuration(resources.configuration)
    config.setLocale(Locale.forLanguageTag(lang.code))
    return createConfigurationContext(config)
}

fun Context.docTypeName(type: DocType): String = getString(docTypeRes(type))

fun Context.templateName(id: String): String = getString(
    when (id) {
        "home_loan" -> R.string.template_home_loan
        "health_insurance_claim" -> R.string.template_health_insurance_claim
        "vehicle_insurance_renewal" -> R.string.template_vehicle_insurance_renewal
        "passport" -> R.string.template_passport
        "health_insurance_renewal" -> R.string.template_health_insurance_renewal
        "driving_licence_renewal" -> R.string.template_driving_licence_renewal
        "life_insurance_renewal" -> R.string.template_life_insurance_renewal
        else -> R.string.template_renewal_generic
    },
)

fun docTypeRes(type: DocType): Int = when (type) {
    DocType.AADHAAR -> R.string.doc_AADHAAR
    DocType.PAN -> R.string.doc_PAN
    DocType.DRIVING_LICENCE -> R.string.doc_DRIVING_LICENCE
    DocType.VEHICLE_RC -> R.string.doc_VEHICLE_RC
    DocType.PASSPORT -> R.string.doc_PASSPORT
    DocType.VOTER_ID -> R.string.doc_VOTER_ID
    DocType.HEALTH_ID_ABHA -> R.string.doc_HEALTH_ID_ABHA
    DocType.HEALTH_INSURANCE -> R.string.doc_HEALTH_INSURANCE
    DocType.VEHICLE_INSURANCE -> R.string.doc_VEHICLE_INSURANCE
    DocType.LIFE_INSURANCE -> R.string.doc_LIFE_INSURANCE
    DocType.SALARY_SLIP -> R.string.doc_SALARY_SLIP
    DocType.BANK_STATEMENT -> R.string.doc_BANK_STATEMENT
    DocType.EMPLOYMENT_LETTER -> R.string.doc_EMPLOYMENT_LETTER
    DocType.ITR_FORM16 -> R.string.doc_ITR_FORM16
    DocType.RENT_AGREEMENT -> R.string.doc_RENT_AGREEMENT
    DocType.PROPERTY_PAPER -> R.string.doc_PROPERTY_PAPER
    DocType.LOAN_SANCTION_EMI -> R.string.doc_LOAN_SANCTION_EMI
    DocType.MEDICAL_REPORT -> R.string.doc_MEDICAL_REPORT
    DocType.HOSPITAL_BILL -> R.string.doc_HOSPITAL_BILL
    DocType.PRESCRIPTION -> R.string.doc_PRESCRIPTION
    DocType.UTILITY_BILL -> R.string.doc_UTILITY_BILL
    DocType.RECEIPT_INVOICE -> R.string.doc_RECEIPT_INVOICE
    DocType.PAYMENT_SCREENSHOT -> R.string.doc_PAYMENT_SCREENSHOT
    DocType.WARRANTY -> R.string.doc_WARRANTY
    DocType.TICKET -> R.string.doc_TICKET
    DocType.OTHER_DOCUMENT -> R.string.doc_OTHER_DOCUMENT
}
