package com.hackathon.recall.search

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.effectiveType
import java.time.LocalDate

sealed interface QueryResult {
    val intent: QueryIntent

    data class Found(override val intent: QueryIntent, val answer: Answer, val hits: List<HybridSearch.Hit>) : QueryResult
    data class Pack(override val intent: QueryIntent, val templateId: String) : QueryResult
    data class Reminders(override val intent: QueryIntent, val docs: List<DocumentEntity>) : QueryResult
    data class Chat(override val intent: QueryIntent, val reply: String, val source: String) : QueryResult
}

/** The ask bar's pipeline: parse → route by intent → retrieve → answer (brief §6). */
class QueryEngine(
    private val parser: IntentParser,
    private val search: HybridSearch,
    private val answers: AnswerGenerator,
    private val chat: ChatResponder,
    private val repo: DocumentRepository,
) {
    /** [onToken] streams a chat reply as it is generated; document answers arrive whole. */
    suspend fun ask(
        query: String,
        today: LocalDate = LocalDate.now(),
        useLlm: Boolean = true,
        ownerFilter: String? = null,
        onToken: ((String) -> Unit)? = null,
    ): QueryResult {
        val intent = parser.parse(query, today, useLlm)
        return when (intent.kind) {
            IntentKind.CHAT -> {
                val reply = chat.reply(query, intent.language, repo.count(), useLlm, onToken)
                QueryResult.Chat(intent, reply.text, reply.source)
            }
            IntentKind.PACK -> QueryResult.Pack(intent, intent.template ?: "home_loan")
            IntentKind.REMINDERS -> {
                val withExpiry = repo.all().filter { it.expiryOn != null }
                    .filter { intent.docTypes.isEmpty() || it.effectiveType() in intent.docTypes }
                    .filter { ownerFilter.isNullOrBlank() || it.ownerName?.contains(ownerFilter, ignoreCase = true) == true }
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
