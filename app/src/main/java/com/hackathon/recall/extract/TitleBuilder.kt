package com.hackathon.recall.extract

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.EntityKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** English display title (`title_en`), for example "Salary slip · Mar 2026 · Ravi Kumar". */
object TitleBuilder {
    private val MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    fun build(type: DocType, issuedOn: LocalDate?, expiryOn: LocalDate?, owner: String?, entities: List<ExtractedEntity>): String {
        val parts = mutableListOf(type.labelEn)
        when (type) {
            DocType.SALARY_SLIP, DocType.BANK_STATEMENT, DocType.UTILITY_BILL -> issuedOn?.let { parts += MONTH.format(it) }
            DocType.AADHAAR -> entities.firstOrNull { it.kind == EntityKind.AADHAAR }?.let { parts += it.masked }
            DocType.PAN -> entities.firstOrNull { it.kind == EntityKind.PAN }?.let { parts += it.masked }
            DocType.VEHICLE_RC, DocType.VEHICLE_INSURANCE ->
                entities.firstOrNull { it.kind == EntityKind.VEHICLE_NO }?.let { parts += it.value }
            else -> {}
        }
        if (expiryOn != null && type.hasExpiry) parts += "valid till " + DAY.format(expiryOn)
        else if (issuedOn != null && type !in setOf(DocType.SALARY_SLIP, DocType.BANK_STATEMENT, DocType.UTILITY_BILL)) {
            parts += DAY.format(issuedOn)
        }
        owner?.let { parts += it }
        return parts.joinToString(" · ")
    }

    /** One short English line of key facts, embedded with the document's header chunk. */
    fun factsLine(type: DocType, issuedOn: LocalDate?, expiryOn: LocalDate?, owner: String?, entities: List<ExtractedEntity>): String {
        val facts = mutableListOf<String>()
        issuedOn?.let { facts += "issued ${MONTH.format(it)}" }
        expiryOn?.let { facts += "expires ${DAY.format(it)}" }
        owner?.let { facts += "name $it" }
        entities.firstOrNull { it.kind == EntityKind.POLICY_NO }?.let { facts += "policy number ${it.value}" }
        entities.firstOrNull { it.kind == EntityKind.VEHICLE_NO }?.let { facts += "vehicle ${it.value}" }
        entities.filter { it.kind == EntityKind.AMOUNT }.maxByOrNull { it.value.toDoubleOrNull() ?: 0.0 }
            ?.let { facts += "amount rupees ${it.value}" }
        return "${type.labelEn}. " + facts.joinToString(", ")
    }
}
