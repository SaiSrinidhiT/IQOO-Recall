package com.hackathon.recall.search

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.type
import java.time.LocalDate

sealed interface QueryResult {
    val intent: QueryIntent

    data class Found(override val intent: QueryIntent, val answer: Answer, val hits: List<HybridSearch.Hit>) : QueryResult
    data class Pack(override val intent: QueryIntent, val templateId: String) : QueryResult
    data class Emergency(override val intent: QueryIntent) : QueryResult
    data class Reminders(override val intent: QueryIntent, val docs: List<DocumentEntity>) : QueryResult
}

/** The ask bar's pipeline: parse → route by intent → retrieve → answer (brief §6). */
class QueryEngine(
    private val parser: IntentParser,
    private val search: HybridSearch,
    private val answers: AnswerGenerator,
    private val repo: DocumentRepository,
) {
    suspend fun ask(query: String, today: LocalDate = LocalDate.now(), useLlm: Boolean = true, ownerFilter: String? = null): QueryResult {
        val intent = parser.parse(query, today, useLlm)
        return when (intent.kind) {
            IntentKind.EMERGENCY -> QueryResult.Emergency(intent)
            IntentKind.PACK -> QueryResult.Pack(intent, intent.template ?: "home_loan")
            IntentKind.REMINDERS -> {
                val withExpiry = repo.all().filter { it.expiryOn != null }
                    .filter { intent.docTypes.isEmpty() || it.type() in intent.docTypes }
                    .sortedBy { it.expiryOn }
                QueryResult.Reminders(intent, withExpiry)
            }
            IntentKind.FIND, IntentKind.QUESTION -> {
                val hits = search.search(intent, query, ownerFilter = ownerFilter)
                val answer = answers.answer(intent.question ?: query, intent.language, hits.map { it.doc }, useLlm)
                QueryResult.Found(intent, answer, hits)
            }
        }
    }
}
