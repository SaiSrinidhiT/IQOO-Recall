package com.hackathon.recall.search

import com.hackathon.recall.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * The reported bug: asking for a document, saying it's wrong, then getting the identical answer back.
 * `RuleFallbackParser.isCorrection` is the piece that detects the rejection; QueryEngine (untested here,
 * it needs live models and a database) uses it plus [PreviousFind] to exclude what was already shown.
 */
class CorrectionTest {
    private val rules = RuleFallbackParser(Lexicon.parse(File("src/main/assets/rule_lexicon.json").readText()))
    private val today = LocalDate.of(2026, 9, 27)

    @Test
    fun `a rejection of the previous reply is recognised, in English and Indian languages`() {
        listOf(
            "this is not the salary slip", "that's not the salary slip", "wrong one", "not this one",
            "this isn't it", "none of these", "not what i asked", "galat hai", "यह गलत है", "ఇది తప్పు",
        ).forEach { assertTrue("\"$it\"", rules.isCorrection(it)) }
    }

    @Test
    fun `an ordinary document request is not mistaken for a rejection`() {
        listOf("show my salary slip", "give me the exact salary slip of sai", "what is my policy number", "hi")
            .forEach { assertFalse("\"$it\"", rules.isCorrection(it)) }
    }

    /** The exact reported message: it both names the type again AND rejects the previous answer. */
    @Test
    fun `'this is not the salary slip' still names the document type, so exclusion has two ways to trigger`() {
        val intent = rules.parse("this is not the salary slip", today)
        assertTrue(rules.isCorrection("this is not the salary slip"))
        assertEquals(listOf(DocType.SALARY_SLIP), intent.docTypes)
    }

    @Test
    fun `a bare rejection with no document keyword names no type of its own`() {
        val intent = rules.parse("wrong one, not this", today)
        assertTrue(intent.docTypes.isEmpty())
    }
}
