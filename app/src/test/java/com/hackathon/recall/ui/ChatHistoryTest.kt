package com.hackathon.recall.ui

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import com.hackathon.recall.search.IntentKind
import com.hackathon.recall.search.QueryIntent
import com.hackathon.recall.search.QueryResult
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatHistoryTest {
    private val intent = QueryIntent(IntentKind.CHAT, null, emptyList(), "hi", null, null, Lang.EN, null, source = "llm")

    @Test
    fun `finished turns are saved with their reply, unfinished ones are not`() {
        assertEquals(SavedTurn("hi", "chat", "Hello!"), ChatHistory.snapshot("hi", QueryResult.Chat(intent, "Hello!", "llm"), failed = false))
        assertEquals(SavedTurn("x", "pack", templateId = "home_loan"), ChatHistory.snapshot("x", QueryResult.Pack(intent, "home_loan"), false))
        assertEquals("failed", ChatHistory.snapshot("x", null, failed = true)?.kind)
        assertNull(ChatHistory.snapshot("still answering", null, failed = false))
    }

    @Test
    fun `a saved chat survives the stored JSON format`() {
        val chats = listOf(
            SavedChat(
                1L, 2L,
                listOf(SavedTurn("hi", "chat", "Hello!"), SavedTurn("my pan", "found", "Here is your PAN.", listOf(7, 3), listOf(7))),
            ),
        )
        val serializer = ListSerializer(SavedChat.serializer())
        val json = Json.encodeToString(serializer, chats)
        assertEquals(chats, Json.decodeFromString(serializer, json))
        assertEquals("hi", chats.single().title)
    }

    private fun doc(id: Long, type: DocType, confidence: Float = 0.9f) = DocumentEntity(
        id = id, sourceUri = null, sourceKind = "gallery", sha256 = "s$id", vaultPath = "$id.enc", docType = type.name,
        docTypeConfidence = confidence, docTypeSource = "rule", titleEn = "", ocrText = "", scripts = "", capturedAt = 0,
        issuedOn = null, expiryOn = null, expirySource = null, ownerName = null, createdAt = 0, mimeType = "image/jpeg",
    )

    /** The reported case: retyping a wrong card from its preview removes it from the chat it came from. */
    @Test
    fun `a document retyped or deleted after the reply drops out of it`() {
        val shown = listOf(doc(1, DocType.LOAN_SANCTION_EMI), doc(2, DocType.LOAN_SANCTION_EMI), doc(3, DocType.LOAN_SANCTION_EMI))
        val now = mapOf(
            1L to doc(1, DocType.LOAN_SANCTION_EMI),
            2L to doc(2, DocType.PAYMENT_SCREENSHOT, 1f), // retyped by the user
            // 3 deleted
        )
        assertEquals(listOf(1L), currentMatches(shown, listOf(DocType.LOAN_SANCTION_EMI), now).map { it.id })
        // Retyping removes it even when the question named no type.
        assertEquals(listOf(1L), currentMatches(shown, emptyList(), now).map { it.id })
        // Confirming the same type ("Yes, it's a loan sanction") keeps it.
        assertEquals(listOf(1L), currentMatches(shown.take(1), emptyList(), mapOf(1L to doc(1, DocType.LOAN_SANCTION_EMI, 1f))).map { it.id })
        // Before the document list loads, the reply is shown as it was.
        assertEquals(3, currentMatches(shown, emptyList(), emptyMap()).size)
    }
}
