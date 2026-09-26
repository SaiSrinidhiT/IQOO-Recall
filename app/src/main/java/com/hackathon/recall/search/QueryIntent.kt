package com.hackathon.recall.search

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class IntentKind(val code: String) {
    FIND("find"), PACK("pack"), REMINDERS("reminders"), QUESTION("question"),

    /** Greetings, thanks, help, small talk, off-topic questions: answered in words, never with a search. */
    CHAT("chat");

    companion object {
        fun fromCode(v: String?): IntentKind? = entries.firstOrNull { it.code == v?.trim()?.lowercase() }
    }
}

/** Raw shape of the intent JSON the LLM returns (brief §6.1). Everything is optional until validated. */
@Serializable
data class IntentJson(
    val intent: String? = null,
    @SerialName("task_template") val taskTemplate: String? = null,
    @SerialName("doc_types") val docTypes: List<String>? = null,
    @SerialName("query_en") val queryEn: String? = null,
    @SerialName("date_from") val dateFrom: String? = null,
    @SerialName("date_to") val dateTo: String? = null,
    @SerialName("answer_language") val answerLanguage: String? = null,
    val question: String? = null,
)

data class QueryIntent(
    val kind: IntentKind,
    val template: String?,
    val docTypes: List<DocType>,
    val queryEn: String,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val language: Lang,
    val question: String?,
    /** "llm" or "rules". */
    val source: String,
    /** doc_types values the LLM produced that are not in the enum (dropped, brief §6.1). */
    val droppedDocTypes: List<String> = emptyList(),
)

object IntentValidator {
    val TEMPLATES = setOf("home_loan", "health_insurance_claim", "vehicle_insurance_renewal", "passport")

    /** Throws [IllegalArgumentException] with a message suitable for the one retry the brief allows. */
    fun validate(raw: IntentJson, originalQuery: String, detectedLang: Lang): QueryIntent {
        val kind = IntentKind.fromCode(raw.intent)
            ?: throw IllegalArgumentException(
                "\"intent\" must be one of find, question, reminders, pack, chat (got ${raw.intent ?: "nothing"})",
            )
        val template = clean(raw.taskTemplate)?.lowercase()?.takeIf { it in TEMPLATES }
        val parsedTypes = raw.docTypes.orEmpty().map { it to DocType.parse(it) }
        val docTypes = parsedTypes.mapNotNull { it.second }.distinct()
        val dropped = parsedTypes.filter { it.second == null }.map { it.first }
        val d1 = date(raw.dateFrom)
        val d2 = date(raw.dateTo)
        val (from, to) = if (d1 != null && d2 != null && d1.isAfter(d2)) d2 to d1 else d1 to d2
        val resolved = when {
            kind == IntentKind.PACK && template == null && docTypes.isEmpty() -> IntentKind.FIND
            // A reply that says "chat" but names a document contradicts itself; the document wins.
            kind == IntentKind.CHAT && (docTypes.isNotEmpty() || template != null) -> IntentKind.FIND
            else -> kind
        }
        return QueryIntent(
            kind = resolved,
            template = template,
            docTypes = docTypes,
            queryEn = clean(raw.queryEn) ?: originalQuery,
            dateFrom = from,
            dateTo = to,
            language = Lang.fromCode(raw.answerLanguage) ?: detectedLang,
            question = clean(raw.question),
            source = "llm",
            droppedDocTypes = dropped,
        )
    }

    private fun clean(s: String?): String? = s?.trim()?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }

    private fun date(s: String?): LocalDate? = clean(s)?.let {
        try {
            LocalDate.parse(it)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
