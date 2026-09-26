package com.hackathon.recall.ui

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
}
