package com.hackathon.recall.search

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.data.PhotoDao
import com.hackathon.recall.model.PhotoCategory
import java.time.ZoneId
import java.time.LocalDate

sealed interface QueryResult {
    val intent: QueryIntent

    data class Found(
        override val intent: QueryIntent,
        val answer: Answer,
        val hits: List<HybridSearch.Hit>,
        /** True when every match was excluded (the previous reply's documents, or ones the user rejected):
         * [hits] is empty because there is nothing else, not because nothing was ever found. */
        val excludedPrevious: Boolean = false,
    ) : QueryResult
    data class Pack(override val intent: QueryIntent, val templateId: String) : QueryResult
    data class Reminders(override val intent: QueryIntent, val docs: List<DocumentEntity>) : QueryResult
    data class Chat(override val intent: QueryIntent, val reply: String, val source: String) : QueryResult

    /** Gallery photos (content URIs, newest first); for Trips & places, trip photos come first. */
    data class Photos(override val intent: QueryIntent, val category: PhotoCategory, val uris: List<String>, val place: String? = null) : QueryResult
}

/** The previous reply's document type(s) and the documents it showed, so a follow-up can tell "give me
 * a different one" from "show me this again" instead of re-running the identical deterministic search. */
data class PreviousFind(val docTypes: List<com.hackathon.recall.model.DocType>, val shownIds: Set<Long>)

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
        /** The immediately preceding reply's documents, when it was a Found: lets "this is not the X" or
         * a bare "wrong one" mean "something else", not an identical repeat of the same search. */
        previousFind: PreviousFind? = null,
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
        // A short rejection of the previous reply ("that's not it", "wrong one") names no document itself;
        // routing it as chat or a blind text search on its own words was the bug. Answer from the same
        // category as before instead, minus what was already shown.
        if (previousFind != null && previousFind.docTypes.isNotEmpty() && parser.isCorrection(query)) {
            return findAgain(previousFind.docTypes, previousFind.shownIds, query, detectLanguage(query), ownerFilter)
        }
        val intent = parser.parse(query, today, useLlm)
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
                    .filter { ownerFilter.isNullOrBlank() || it.ownerName?.contains(ownerFilter, ignoreCase = true) == true }
                    .sortedBy { it.expiryOn }
                QueryResult.Reminders(intent, withExpiry)
            }
            IntentKind.FIND -> {
                // Asking for the same type again right after seeing it is "give me a different one", not a
                // request for the identical, deterministic result: exclude what was already shown.
                val exclude = excludeFor(intent.docTypes, previousFind)
                val hits = search.search(intent, query, limit = FIND_LIMIT, ownerFilter = ownerFilter, excludeIds = exclude)
                // "Show me X": the cards are the answer. A Qwen-written sentence would add ~3-4 s and say
                // nothing the cards don't, so the reply line is a template.
                val answer = if (hits.isEmpty() && exclude.isNotEmpty()) answers.excludedAll(intent.language, intent.docTypes)
                    else answers.answer(query, intent.language, hits.map { it.doc }, useLlm = false)
                QueryResult.Found(intent, answer, hits, excludedPrevious = hits.isEmpty() && exclude.isNotEmpty())
            }
            IntentKind.QUESTION -> {
                val exclude = excludeFor(intent.docTypes, previousFind)
                val hits = search.search(intent, query, limit = QUESTION_LIMIT, ownerFilter = ownerFilter, excludeIds = exclude)
                val answer = if (hits.isEmpty() && exclude.isNotEmpty()) answers.excludedAll(intent.language, intent.docTypes)
                    else answers.answer(intent.question ?: query, intent.language, hits.map { it.doc }, useLlm)
                // Show the documents the answer came from, not every candidate it was chosen among.
                // Qwen citing nothing means "not in your documents", so no cards; without Qwen, the candidates.
                val shown = if (answer.source == "llm") hits.filter { it.doc.id in answer.citedDocIds } else hits
                QueryResult.Found(intent, answer, shown, excludedPrevious = hits.isEmpty() && exclude.isNotEmpty())
            }
        }
    }

    /** Only exclude when the same category is being asked for again; a genuinely new type gets a clean search. */
    private fun excludeFor(docTypes: List<com.hackathon.recall.model.DocType>, previousFind: PreviousFind?): Set<Long> {
        if (previousFind == null || docTypes.isEmpty() || previousFind.docTypes.isEmpty()) return emptySet()
        return if (docTypes.any { it in previousFind.docTypes }) previousFind.shownIds else emptySet()
    }

    private fun detectLanguage(query: String) = parser.detectLanguage(query)

    private suspend fun findAgain(types: List<com.hackathon.recall.model.DocType>, excludeIds: Set<Long>, query: String, lang: com.hackathon.recall.model.Lang, ownerFilter: String?): QueryResult.Found {
        val intent = QueryIntent(IntentKind.FIND, null, types, query, null, null, lang, null, source = "rules")
        val hits = search.search(intent, query, limit = FIND_LIMIT, ownerFilter = ownerFilter, excludeIds = excludeIds)
        val answer = if (hits.isEmpty()) answers.excludedAll(lang, types) else answers.answer(query, lang, hits.map { it.doc }, useLlm = false)
        return QueryResult.Found(intent, answer, hits, excludedPrevious = hits.isEmpty())
    }

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
    }
}
