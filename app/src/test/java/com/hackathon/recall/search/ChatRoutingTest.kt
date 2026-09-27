package com.hackathon.recall.search

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Small talk must reach the chat reply, never document search; anything naming a document must not. */
class ChatRoutingTest {
    private val rules = RuleFallbackParser(Lexicon.parse(File("src/main/assets/rule_lexicon.json").readText()))
    private val today = LocalDate.of(2026, 9, 27)
    private fun kind(q: String) = rules.parse(q, today).kind

    @Test
    fun `greetings thanks help and goodbyes are chat`() {
        listOf("hi", "Hello!", "hey there", "good morning", "namaste", "thanks", "thank you so much", "ok", "what can you do", "who are you", "bye")
            .forEach { assertEquals("\"$it\"", IntentKind.CHAT, kind(it)) }
    }

    @Test
    fun `Hindi and Telugu small talk is chat`() {
        listOf("नमस्ते", "धन्यवाद", "నమస్కారం", "ధన్యవాదాలు", "kaise ho", "ela unnavu")
            .forEach { assertEquals("\"$it\"", IntentKind.CHAT, kind(it)) }
    }

    @Test
    fun `emoji-only and empty messages are chat`() {
        assertEquals(IntentKind.CHAT, kind("🙂👍"))
        assertEquals(IntentKind.CHAT, kind("   "))
    }

    /** The reported bug: a greeting in front of a real request must still search. */
    @Test
    fun `a greeting before a document request is still a find`() {
        val intent = rules.parse("hi, show my aadhaar", today)
        assertEquals(IntentKind.FIND, intent.kind)
        assertTrue(DocType.AADHAAR in intent.docTypes)
    }

    @Test
    fun `document requests questions and reminders are not chat`() {
        assertEquals(IntentKind.FIND, kind("show my pan card"))
        assertEquals(IntentKind.QUESTION, kind("what is my car insurance policy number"))
        assertEquals(IntentKind.REMINDERS, kind("thanks, anything expiring soon?"))
    }

    /** These name a document type, so IntentParser routes them from the rules without a Qwen call. */
    @Test
    fun `common document requests are recognised by the rules alone`() {
        mapOf(
            "show my pan card" to DocType.PAN,
            "latest salary slip" to DocType.SALARY_SLIP,
            "I need my car insurance papers" to DocType.VEHICLE_INSURANCE,
            "send my resume as pdf" to DocType.RESUME,
            "mera aadhaar dikhao" to DocType.AADHAAR,
        ).forEach { (q, type) -> assertTrue("\"$q\" -> ${rules.parse(q, today).docTypes}", type in rules.parse(q, today).docTypes) }
        // "latest" names no period: no date filter that would hide older slips.
        assertEquals(null, rules.parse("latest salary slip", today).dateFrom)
    }

    @Test
    fun `chatKind picks the kind of small talk for template replies`() {
        assertEquals("greeting", rules.chatKind("hello"))
        assertEquals("thanks", rules.chatKind("thank you"))
        assertEquals("help", rules.chatKind("what can you do"))
        assertEquals("bye", rules.chatKind("bye"))
        assertEquals(null, rules.chatKind("show my pan card"))
    }

    /** The model sometimes answers "chat" while also naming a document; the document wins. */
    @Test
    fun `validator turns a self-contradicting chat reply into a find`() {
        val raw = IntentJson(intent = "chat", docTypes = listOf("PAN"), queryEn = "pan card", answerLanguage = "en")
        assertEquals(IntentKind.FIND, IntentValidator.validate(raw, "hi my pan", Lang.EN).kind)
        val chat = IntentJson(intent = "chat", docTypes = emptyList(), queryEn = "", answerLanguage = "en")
        assertEquals(IntentKind.CHAT, IntentValidator.validate(chat, "hi", Lang.EN).kind)
    }
}
