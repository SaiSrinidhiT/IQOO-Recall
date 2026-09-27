package com.hackathon.recall.search

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.data.toSummary
import com.hackathon.recall.model.DocType
import com.hackathon.recall.data.PhotoDao
import com.hackathon.recall.model.PhotoCategory
import java.time.ZoneId
import java.time.LocalDate

/** Why a document reply may have no cards, so the chat says the right thing instead of "scan it". */
enum class FoundNote {
    /** Nothing matched: the chat offers to scan the document. */
    NONE,
    /** The only matches were already shown or rejected: there is simply no other one saved. */
    NO_OTHER,
    /** The reply text says it all (an unclear message, advice with nothing saved). */
    TEXT_ONLY,
}

sealed interface QueryResult {
    val intent: QueryIntent

    data class Found(
        override val intent: QueryIntent,
        val answer: Answer,
        val hits: List<HybridSearch.Hit>,
        val note: FoundNote = FoundNote.NONE,
    ) : QueryResult
    data class Pack(override val intent: QueryIntent, val templateId: String) : QueryResult
    data class Reminders(override val intent: QueryIntent, val docs: List<DocumentEntity>) : QueryResult
    data class Chat(override val intent: QueryIntent, val reply: String, val source: String) : QueryResult

    /** Gallery photos (content URIs, newest first); for Trips & places, trip photos come first. */
    data class Photos(override val intent: QueryIntent, val category: PhotoCategory, val uris: List<String>, val place: String? = null) : QueryResult
}

/** The ask bar's pipeline: parse → route by intent → retrieve → answer (brief §6). */
class QueryEngine(
    private val parser: IntentParser,
    private val search: HybridSearch,
    private val answers: AnswerGenerator,
    private val chat: ChatResponder,
    private val repo: DocumentRepository,
    private val photos: PhotoDao,
) {
    /** [onToken] streams a chat reply as it is generated; document answers arrive whole. */
    suspend fun ask(
        query: String,
        today: LocalDate = LocalDate.now(),
        useLlm: Boolean = true,
        ownerFilter: String? = null,
        /** The previous reply, when it showed documents: what "it", "that one" and "another" refer to. */
        context: ChatContext? = null,
        onToken: ((String) -> Unit)? = null,
    ): QueryResult {
        // A town the user has photos from ("ongole pics", "selfies in goa"): answered from the gallery by
        // the rules alone, unless the message also names a document ("hyderabad rent agreement").
        val place = PlaceMatcher.find(query, photos.places())
        if (place != null) {
            val ruled = parser.parse(query, today, useLlm = false)
            if (ruled.docTypes.isEmpty() && ruled.template == null && ruled.kind != IntentKind.REMINDERS) {
                val category = ruled.photoCategory?.takeIf { it != PhotoCategory.PLACES }
                val (from, to) = millis(ruled)
                val uris = photos.atPlace(place, category?.db, from, to, PHOTO_LIMIT).map { it.uri }
                return QueryResult.Photos(ruled.copy(kind = IntentKind.PHOTOS), category ?: PhotoCategory.PLACES, uris, place)
            }
        }
        // "That's not it", "wrong one": the previous answer was wrong. Same category, minus what was shown.
        if (context != null && context.docTypes.isNotEmpty() && parser.isCorrection(query)) {
            val ruled = parser.parse(query, today, useLlm = false)
            val types = ruled.docTypes.ifEmpty { context.docTypes }
            return find(ruled.copy(kind = IntentKind.FIND, docTypes = types), query, ownerFilter, context.shownIds)
        }
        // "When does it expire?", "share it", "the second one", "and my wife's?": about the previous reply.
        val ruled = parser.parse(query, today, useLlm = false)
        FollowUp.plan(parser.followUpCue(query), ruled, context)?.let { plan ->
            return followUp(plan, query, ruled, context!!, ownerFilter, useLlm, today)
        }
        val intent = parser.parse(query, today, useLlm, context?.describe())
        // Qwen saw the previous reply and says this message is about it, in words the rules didn't catch.
        if (intent.followUp && intent.source == "llm" && context != null && context.shown.isNotEmpty()) {
            val plan = if (intent.kind == IntentKind.QUESTION) FollowUpPlan.Ask(context.shown) else FollowUpPlan.Show(context.shown, intent.action)
            return followUp(plan, query, intent, context, ownerFilter, useLlm, today)
        }
        return when (intent.kind) {
            IntentKind.CHAT -> {
                val reply = chat.reply(query, intent.language, repo.count(), useLlm, onToken)
                QueryResult.Chat(intent, reply.text, reply.source)
            }
            IntentKind.PACK -> QueryResult.Pack(intent, intent.template ?: "home_loan")
            IntentKind.PHOTOS -> {
                val category = intent.photoCategory ?: PhotoCategory.OTHER
                val (from, to) = millis(intent)
                // Trips hold every kind of photo taken away from home, so "trip photos" lists those first.
                val trip = if (category == PhotoCategory.PLACES) photos.inTrips(from, to, PHOTO_LIMIT) else emptyList()
                val own = photos.byCategory(category.db, from, to, PHOTO_LIMIT)
                QueryResult.Photos(intent, category, (trip + own).map { it.uri }.distinct().take(PHOTO_LIMIT))
            }
            IntentKind.REMINDERS -> {
                val withExpiry = repo.all().filter { it.expiryOn != null }
                    .filter { intent.docTypes.isEmpty() || it.effectiveType() in intent.docTypes }
                    .filter { it.effectiveType() !in intent.excludeTypes }
                    .filter { ownerFilter.isNullOrBlank() || it.ownerName?.contains(ownerFilter, ignoreCase = true) == true }
                    .sortedBy { it.expiryOn }
                QueryResult.Reminders(intent, withExpiry)
            }
            // Asking for the same type again right after seeing it is "give me a different one", not a
            // request for the identical, deterministic result: exclude what was already shown.
            IntentKind.FIND -> find(intent, query, ownerFilter, excludeFor(intent.docTypes, context))
            IntentKind.QUESTION -> {
                val exclude = excludeFor(intent.docTypes, context)
                val hits = search.search(intent, query, limit = QUESTION_LIMIT, ownerFilter = ownerFilter, excludeIds = exclude)
                if (hits.isEmpty() && exclude.isNotEmpty()) return QueryResult.Found(intent, answers.excludedAll(intent.language, intent.docTypes), hits, FoundNote.NO_OTHER)
                if (hits.isEmpty() && intent.docTypes.isEmpty()) return QueryResult.Found(intent, answers.unclear(intent.language), hits, FoundNote.TEXT_ONLY)
                val answer = answers.answer(intent.question ?: query, intent.language, hits.map { it.doc }, useLlm, today)
                // Show the documents the answer came from, not every candidate it was chosen among.
                // Qwen citing nothing means "not in your documents", so no cards; without Qwen, the candidates.
                val shown = if (answer.source == "llm") hits.filter { it.doc.id in answer.citedDocIds } else hits
                QueryResult.Found(intent, answer, shown)
            }
        }
    }

    /**
     * "Show me X", with the reply line a person would give: how many are saved and whose, oldest first
     * when asked, and guidance when the user asked to share, delete, edit or get advice.
     */
    private suspend fun find(intent: QueryIntent, query: String, ownerFilter: String?, exclude: Set<Long>): QueryResult.Found {
        if (intent.listAll) return overview(intent, ownerFilter)
        val lang = intent.language
        val wide = intent.order == SortOrder.OLDEST
        var hits = search.search(intent, query, limit = if (wide) WIDE_LIMIT else FIND_LIMIT, ownerFilter = ownerFilter, excludeIds = exclude)
        if (wide) hits = hits.sortedBy { it.doc.toSummary().effectiveDate }.take(FIND_LIMIT)
        return when {
            hits.isEmpty() && exclude.isNotEmpty() -> QueryResult.Found(intent, answers.excludedAll(lang, intent.docTypes), hits, FoundNote.NO_OTHER)
            hits.isEmpty() && intent.action == DocAction.ADVICE -> QueryResult.Found(intent, answers.adviceWithoutDocument(lang, intent.docTypes), hits, FoundNote.TEXT_ONLY)
            hits.isEmpty() && intent.docTypes.isEmpty() && intent.template == null -> QueryResult.Found(intent, answers.unclear(lang), hits, FoundNote.TEXT_ONLY)
            hits.isEmpty() -> QueryResult.Found(intent, answers.answer(query, lang, emptyList(), useLlm = false), hits)
            else -> {
                val all = matching(intent, ownerFilter)
                val owners = all.mapNotNull { it.ownerName?.trim()?.takeIf(String::isNotEmpty) }.distinct().take(MAX_OWNERS)
                val answer = answers.summary(lang, intent.docTypes, hits.map { it.doc }, all.size.coerceAtLeast(hits.size), owners, intent.order, intent.action)
                QueryResult.Found(intent, answer, hits)
            }
        }
    }

    /** "What documents do I have?", "everything except Aadhaar": the latest few and a count per type. */
    private suspend fun overview(intent: QueryIntent, ownerFilter: String?): QueryResult.Found {
        val docs = repo.all()
            .filter { it.effectiveType() !in intent.excludeTypes }
            .filter { ownerFilter.isNullOrBlank() || it.ownerName?.contains(ownerFilter, ignoreCase = true) == true }
            .sortedByDescending { it.toSummary().effectiveDate }
        if (docs.isEmpty()) return QueryResult.Found(intent, answers.answer(intent.queryEn, intent.language, emptyList(), useLlm = false), emptyList())
        val counts = docs.groupingBy { it.effectiveType() }.eachCount().entries.sortedByDescending { it.value }.take(MAX_OVERVIEW_TYPES).map { it.key to it.value }
        return QueryResult.Found(intent, answers.overview(intent.language, docs.size, counts, intent.excludeTypes), docs.take(FIND_LIMIT).map(::hit))
    }

    /** Every saved document the intent's type, person and period cover: what a count or owner list is about. */
    private suspend fun matching(intent: QueryIntent, ownerFilter: String?): List<DocumentEntity> {
        if (intent.docTypes.isEmpty()) return emptyList()
        return repo.all().filter { d ->
            d.effectiveType() in intent.docTypes &&
                (ownerFilter.isNullOrBlank() || d.ownerName?.contains(ownerFilter, ignoreCase = true) == true) &&
                d.toSummary().effectiveDate.let { e -> (intent.dateFrom == null || !e.isBefore(intent.dateFrom)) && (intent.dateTo == null || !e.isAfter(intent.dateTo)) }
        }
    }

    private suspend fun followUp(
        plan: FollowUpPlan,
        query: String,
        ruled: QueryIntent,
        context: ChatContext,
        ownerFilter: String?,
        useLlm: Boolean,
        today: LocalDate,
    ): QueryResult {
        val lang = ruled.language
        val types = context.docTypes
        return when (plan) {
            is FollowUpPlan.Ask -> {
                val intent = ruled.copy(kind = IntentKind.QUESTION, docTypes = types, question = query, followUp = true)
                val answer = answers.answer(query, lang, plan.docs, useLlm, today)
                // They asked about these documents, so keep them on screen even when the answer isn't in them.
                val cited = plan.docs.filter { it.id in answer.citedDocIds }
                QueryResult.Found(intent, answer, cited.ifEmpty { plan.docs }.map(::hit))
            }
            is FollowUpPlan.Show -> {
                val intent = ruled.copy(kind = IntentKind.FIND, docTypes = types, action = plan.action, followUp = true)
                val answer = answers.summary(lang, plan.docs.map { it.effectiveType() }.distinct(), plan.docs, plan.docs.size, emptyList(), SortOrder.LATEST, plan.action)
                QueryResult.Found(intent, answer, plan.docs.map(::hit))
            }
            is FollowUpPlan.Again -> find(ruled.copy(kind = IntentKind.FIND, docTypes = plan.types, followUp = true), query, ownerFilter, plan.excludeIds)
        }
    }

    /** Only exclude when the same category is being asked for again; a genuinely new type gets a clean search. */
    private fun excludeFor(docTypes: List<DocType>, context: ChatContext?): Set<Long> {
        if (context == null || docTypes.isEmpty() || context.docTypes.isEmpty()) return emptySet()
        return if (docTypes.any { it in context.docTypes }) context.shownIds else emptySet()
    }

    private fun hit(doc: DocumentEntity) = HybridSearch.Hit(doc, 0.0, null, false)

    /** The intent's period as epoch millis, whole days in the phone's time zone; open-ended when absent. */
    private fun millis(intent: QueryIntent): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val from = intent.dateFrom?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
        val to = intent.dateTo?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.minus(1) ?: Long.MAX_VALUE
        return from to to
    }

    private companion object {
        const val FIND_LIMIT = 6
        const val QUESTION_LIMIT = 3
        const val PHOTO_LIMIT = 60
        /** Candidates fetched when sorting oldest first, so the oldest aren't cut off by relevance ranking. */
        const val WIDE_LIMIT = 50
        const val MAX_OWNERS = 4
        const val MAX_OVERVIEW_TYPES = 5
    }
}
