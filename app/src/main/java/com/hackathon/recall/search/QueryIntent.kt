package com.hackathon.recall.search

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import com.hackathon.recall.model.PhotoCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class IntentKind(val code: String) {
    FIND("find"), PACK("pack"), REMINDERS("reminders"), QUESTION("question"),

    /** Greetings, thanks, help, small talk, off-topic questions: answered in words, never with a search. */
    CHAT("chat"),

    /** Gallery photos by category ("my selfies", "trip photos"), not documents. */
    PHOTOS("photos");

    companion object {
        fun fromCode(v: String?): IntentKind? = entries.firstOrNull { it.code == v?.trim()?.lowercase() }
    }
}

/** What the user wants done with the documents, beyond seeing them. Only [SHARE] is done from chat. */
enum class DocAction(val code: String) {
    SHARE("share"),
    /** Deleting, renaming or retyping happen on the document's own screen, never from a chat message. */
    DELETE("delete"), EDIT("edit"),
    /** "I lost my Aadhaar, what should I do?": the app can't look procedures up offline; it shows the saved copy. */
    ADVICE("advice");

    companion object {
        fun fromCode(v: String?): DocAction? = entries.firstOrNull { it.code == v?.trim()?.lowercase() }
    }
}

/** Newest first unless the user asks for the oldest or first one. */
enum class SortOrder { LATEST, OLDEST }

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
    @SerialName("photo_category") val photoCategory: String? = null,
    @SerialName("exclude_doc_types") val excludeDocTypes: List<String>? = null,
    val action: String? = null,
    val order: String? = null,
    @SerialName("follow_up") val followUp: Boolean? = null,
    @SerialName("list_all") val listAll: Boolean? = null,
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
    /** For [IntentKind.PHOTOS]: which gallery category. */
    val photoCategory: PhotoCategory? = null,
    /** Types the user ruled out: "everything except Aadhaar", "not my PAN, my Aadhaar". */
    val excludeTypes: List<DocType> = emptyList(),
    val action: DocAction? = null,
    val order: SortOrder = SortOrder.LATEST,
    /** The message is about the previous reply's documents ("when does it expire?"), not a new search. */
    val followUp: Boolean = false,
    /** "What documents do I have", "everything except Aadhaar": an overview of the vault, not a search. */
    val listAll: Boolean = false,
)

object IntentValidator {
    /** Categories a chat message can ask for; Bills and Documents are answered from the vault instead. */
    val PHOTO_CATEGORIES = setOf(PhotoCategory.SCREENSHOT, PhotoCategory.SELFIE, PhotoCategory.PEOPLE, PhotoCategory.FOOD, PhotoCategory.PLACES)
    val TEMPLATES = setOf("home_loan", "health_insurance_claim", "vehicle_insurance_renewal", "passport")

    /** Throws [IllegalArgumentException] with a message suitable for the one retry the brief allows. */
    fun validate(raw: IntentJson, originalQuery: String, detectedLang: Lang): QueryIntent {
        val kind = IntentKind.fromCode(raw.intent)
            ?: throw IllegalArgumentException(
                "\"intent\" must be one of find, question, reminders, pack, chat, photos (got ${raw.intent ?: "nothing"})",
            )
        val template = clean(raw.taskTemplate)?.lowercase()?.takeIf { it in TEMPLATES }
        val parsedTypes = raw.docTypes.orEmpty().map { it to DocType.parse(it) }
        val excluded = raw.excludeDocTypes.orEmpty().mapNotNull { DocType.parse(it) }.distinct()
        // A type can't be both wanted and ruled out; ruling out wins, as it is the more deliberate statement.
        val docTypes = parsedTypes.mapNotNull { it.second }.distinct().filterNot { it in excluded }
        val dropped = parsedTypes.filter { it.second == null }.map { it.first }
        val d1 = date(raw.dateFrom)
        val d2 = date(raw.dateTo)
        val (from, to) = if (d1 != null && d2 != null && d1.isAfter(d2)) d2 to d1 else d1 to d2
        val photoCategory = clean(raw.photoCategory)?.lowercase()?.let(PhotoCategory::fromDb)?.takeIf { it in PHOTO_CATEGORIES }
        val resolved = when {
            // A photos reply must say which photos; one that names a document is about the document.
            kind == IntentKind.PHOTOS && (photoCategory == null || docTypes.isNotEmpty()) -> IntentKind.FIND
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
            photoCategory = photoCategory.takeIf { resolved == IntentKind.PHOTOS },
            excludeTypes = excluded,
            action = DocAction.fromCode(raw.action),
            order = if (clean(raw.order)?.lowercase() == "oldest") SortOrder.OLDEST else SortOrder.LATEST,
            followUp = raw.followUp == true,
            listAll = raw.listAll == true || (resolved == IntentKind.FIND && docTypes.isEmpty() && excluded.isNotEmpty()),
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
