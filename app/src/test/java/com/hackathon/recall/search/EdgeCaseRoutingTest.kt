package com.hackathon.recall.search

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * How real people phrase things, beyond "show my PAN". Each case here was wrong before: it was routed
 * as a plain search for the words, or, worse, as a confident search for exactly the wrong thing.
 */
class EdgeCaseRoutingTest {
    private val rules = RuleFallbackParser(Lexicon.parse(File("src/main/assets/rule_lexicon.json").readText()))
    private val today = LocalDate.of(2026, 9, 27)
    private fun parse(q: String) = rules.parse(q, today)

    @Test
    fun `a negated type is ruled out, not searched for`() {
        // Before: FIND [AADHAAR], i.e. exactly the documents the user said not to show.
        parse("show all documents except aadhaar").let {
            assertTrue(it.docTypes.isEmpty()); assertEquals(listOf(DocType.AADHAAR), it.excludeTypes); assertTrue(it.listAll)
        }
        parse("not my pan, my aadhaar").let {
            assertEquals(listOf(DocType.AADHAAR), it.docTypes); assertEquals(listOf(DocType.PAN), it.excludeTypes)
        }
        assertEquals(listOf(DocType.RECEIPT_INVOICE), parse("anything other than bills").excludeTypes)
        // A correction is not a negation: they still want a salary slip.
        assertEquals(listOf(DocType.SALARY_SLIP), parse("this is not the salary slip").docTypes)
    }

    @Test
    fun `verbs about a document are understood, not ignored`() {
        assertEquals(DocAction.DELETE, parse("delete my aadhaar").action)
        assertEquals(DocAction.SHARE, parse("send my pan to my brother").action)
        assertEquals(DocAction.SHARE, parse("email my salary slip").action)
        assertEquals(DocAction.ADVICE, parse("I lost my aadhaar what should I do").action)
        assertEquals(DocAction.ADVICE, parse("how to apply for passport").action)
        // "Email my expired PAN" is about the PAN, not a list of expiring documents.
        assertEquals(IntentKind.FIND, parse("email my expired pan").kind)
    }

    @Test
    fun `overview, oldest and counting questions`() {
        listOf("what documents do I have", "show everything", "my documents").forEach { assertTrue(it, parse(it).listAll) }
        assertEquals(SortOrder.OLDEST, parse("oldest salary slip").order)
        assertEquals(SortOrder.LATEST, parse("latest salary slip").order)
        // Counting and existence questions are finds: the reply states how many and whose.
        assertEquals(IntentKind.FIND, parse("how many salary slips do I have").kind)
        assertEquals(IntentKind.FIND, parse("do I have a passport?").kind)
    }

    @Test
    fun `a month on its own is its most recent occurrence, but not a stray word`() {
        parse("salary slip from march").let { assertEquals(LocalDate.of(2026, 3, 1), it.dateFrom); assertEquals(LocalDate.of(2026, 3, 31), it.dateTo) }
        // In September, "november" is last November.
        assertEquals(LocalDate.of(2025, 11, 1), parse("november salary slip").dateFrom)
        assertEquals(LocalDate.of(2025, 3, 1), parse("salary slip march 2025").dateFrom)
        assertEquals(LocalDate.of(2026, 5, 1), parse("bills from may").dateFrom)
        // Not dates: "may I…", and the Jan Dhan bank scheme.
        assertNull(parse("may I see my pan").dateFrom)
        assertNull(parse("jan dhan passbook").dateFrom)
    }

    @Test
    fun `questions about the world or the app are chat, not a document search`() {
        listOf("what's the weather today", "write python code", "tell me a joke", "is my data safe", "can you see my photos", "yes")
            .forEach { assertEquals(it, IntentKind.CHAT, parse(it).kind) }
        assertEquals("privacy", rules.chatKind("is my data safe"))
        // General advice with no document to show.
        assertEquals(IntentKind.CHAT, parse("how do I apply").kind)
    }

    @Test
    fun `function words are matched exactly, so relatives and policy holders are not follow-up cues`() {
        assertFalse("mother is not 'other'", rules.followUpCue("my mother's aadhaar").older)
        assertFalse("holder is not 'older'", rules.followUpCue("policy holder name").older)
        assertFalse("correct is not 'incorrect'", rules.isCorrection("is it correct?"))
    }

    @Test
    fun `a bare no rejects the reply, a no inside a sentence does not`() {
        assertTrue(rules.isCorrection("no"))
        assertTrue(rules.isCorrection("nahi"))
        assertFalse(rules.isCorrection("I have no passport"))
    }

    @Test
    fun `follow-up cues`() {
        rules.followUpCue("when does it expire?").let { assertTrue(it.reference); assertTrue(it.attribute) }
        assertEquals(DocAction.SHARE, rules.followUpCue("share it").action)
        assertEquals(1, rules.followUpCue("the second one").ordinal)
        assertEquals(-1, rules.followUpCue("the last one").ordinal)
        assertTrue(rules.followUpCue("and my wife's?").ownerSwitch)
        assertTrue(rules.followUpCue("sai's?").ownerSwitch)
        assertTrue(rules.followUpCue("show another one").older)
    }

    @Test
    fun `Qwen's new fields are validated, and omitted fields mean the defaults`() {
        val v = IntentValidator.validate(
            IntentJson(intent = "find", docTypes = listOf("AADHAAR", "PAN"), excludeDocTypes = listOf("PAN"), action = "share", order = "oldest", followUp = true),
            "x", Lang.EN,
        )
        assertEquals(listOf(DocType.AADHAAR), v.docTypes) // ruled out wins over wanted
        assertEquals(listOf(DocType.PAN), v.excludeTypes)
        assertEquals(DocAction.SHARE, v.action)
        assertEquals(SortOrder.OLDEST, v.order)
        assertTrue(v.followUp)
        IntentValidator.validate(IntentJson(intent = "find"), "x", Lang.EN).let {
            assertNull(it.action); assertEquals(SortOrder.LATEST, it.order); assertFalse(it.followUp); assertFalse(it.listAll)
        }
        // "everything except Aadhaar" with no list_all flag is still an overview.
        assertTrue(IntentValidator.validate(IntentJson(intent = "find", excludeDocTypes = listOf("AADHAAR")), "x", Lang.EN).listAll)
    }
}
