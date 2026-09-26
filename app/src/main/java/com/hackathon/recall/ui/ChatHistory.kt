package com.hackathon.recall.ui

import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.KvRow
import com.hackathon.recall.data.RecallDb
import com.hackathon.recall.model.Lang
import com.hackathon.recall.search.Answer
import com.hackathon.recall.search.HybridSearch
import com.hackathon.recall.search.IntentKind
import com.hackathon.recall.search.QueryIntent
import com.hackathon.recall.search.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One saved exchange: enough to redraw it exactly, without asking the model again. */
@Serializable
data class SavedTurn(
    val query: String,
    /** "chat", "found", "pack", "reminders" or "failed". */
    val kind: String,
    val text: String = "",
    /** Documents shown under the reply, in the order they were shown. */
    val docIds: List<Long> = emptyList(),
    val citedIds: List<Long> = emptyList(),
    val templateId: String? = null,
)

@Serializable
data class SavedChat(val id: Long, val updatedAt: Long, val turns: List<SavedTurn>) {
    val title: String get() = turns.firstOrNull()?.query.orEmpty()
}

/**
 * Past conversations, newest first, in the encrypted database's kv table (questions can be personal).
 * Stores reply text and document ids, not documents: reopening a chat reloads the documents, and ones
 * deleted since are simply left out.
 */
object ChatHistory {
    private const val KEY = "chat_history"
    private const val MAX_CHATS = 30
    private const val MAX_TURNS = 50
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedChat.serializer())

    suspend fun load(db: RecallDb): List<SavedChat> = withContext(Dispatchers.IO) {
        db.kv().get(KEY)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    /** Inserts or replaces [chat] and moves it to the top. */
    suspend fun save(db: RecallDb, chat: SavedChat): List<SavedChat> {
        if (chat.turns.isEmpty()) return load(db)
        val trimmed = chat.copy(turns = chat.turns.takeLast(MAX_TURNS))
        val updated = (listOf(trimmed) + load(db).filterNot { it.id == chat.id }).take(MAX_CHATS)
        withContext(Dispatchers.IO) { db.kv().put(KvRow(KEY, json.encodeToString(serializer, updated))) }
        return updated
    }

    suspend fun delete(db: RecallDb, id: Long): List<SavedChat> {
        val updated = load(db).filterNot { it.id == id }
        withContext(Dispatchers.IO) { db.kv().put(KvRow(KEY, json.encodeToString(serializer, updated))) }
        return updated
    }

    suspend fun clear(db: RecallDb): List<SavedChat> {
        withContext(Dispatchers.IO) { db.kv().put(KvRow(KEY, "[]")) }
        return emptyList()
    }

    /** A finished turn as a snapshot; null while it is still being answered. */
    fun snapshot(query: String, result: QueryResult?, failed: Boolean): SavedTurn? = when {
        failed -> SavedTurn(query, "failed")
        result is QueryResult.Chat -> SavedTurn(query, "chat", result.reply)
        result is QueryResult.Found -> SavedTurn(query, "found", result.answer.text, result.hits.map { it.doc.id }, result.answer.citedDocIds)
        result is QueryResult.Pack -> SavedTurn(query, "pack", templateId = result.templateId)
        result is QueryResult.Reminders -> SavedTurn(query, "reminders", docIds = result.docs.map { it.id })
        else -> null
    }

    /** Rebuilds the result a snapshot was taken from, so the chat screen draws it the same way. */
    suspend fun restore(turn: SavedTurn, repo: DocumentRepository): QueryResult? {
        val docs = if (turn.docIds.isEmpty()) emptyList() else repo.byIds(turn.docIds).associateBy { it.id }.let { byId -> turn.docIds.mapNotNull(byId::get) }
        fun intent(kind: IntentKind) = QueryIntent(kind, turn.templateId, emptyList(), turn.query, null, null, Lang.EN, null, source = "history")
        return when (turn.kind) {
            "chat" -> QueryResult.Chat(intent(IntentKind.CHAT), turn.text, "history")
            "found" -> QueryResult.Found(
                intent(IntentKind.FIND),
                Answer(turn.text, turn.citedIds.filter { id -> docs.any { it.id == id } }, "history"),
                docs.map { HybridSearch.Hit(it, 0.0, null, false) },
            )
            "pack" -> turn.templateId?.let { QueryResult.Pack(intent(IntentKind.PACK), it) }
            "reminders" -> QueryResult.Reminders(intent(IntentKind.REMINDERS), docs)
            else -> null
        }
    }
}
