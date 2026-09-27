package com.hackathon.recall.search

import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** What "it", "that one", "the second one" and "and my wife's?" mean after a reply that showed two salary slips. */
class FollowUpTest {
    private val rules = RuleFallbackParser(Lexicon.parse(File("src/main/assets/rule_lexicon.json").readText()))
    private val today = LocalDate.of(2026, 9, 27)

    private fun doc(id: Long, type: DocType, owner: String? = null) = DocumentEntity(
        id = id, sourceUri = null, sourceKind = "gallery", sha256 = "s$id", vaultPath = "$id.enc", docType = type.name,
        docTypeConfidence = 0.95f, docTypeSource = "rule", titleEn = "", ocrText = "SECRET OCR TEXT 1234", scripts = "", capturedAt = 0,
        issuedOn = null, expiryOn = null, expirySource = null, ownerName = owner, createdAt = 0, mimeType = "image/jpeg",
    )

    private val march = doc(1, DocType.SALARY_SLIP, "Sai")
    private val april = doc(2, DocType.SALARY_SLIP, "Sai")
    private val ctx = ChatContext("sai salary slip", listOf(DocType.SALARY_SLIP), listOf(march, april))

    private fun plan(q: String, context: ChatContext? = ctx) = FollowUp.plan(rules.followUpCue(q), rules.parse(q, today), context)

    @Test
    fun `a question about it is answered from the documents just shown, not a fresh search`() {
        // Before: REMINDERS for every expiring document in the vault.
        assertEquals(FollowUpPlan.Ask(listOf(march, april)), plan("when does it expire?"))
        assertEquals(FollowUpPlan.Ask(listOf(march, april)), plan("what's the total amount?"))
        assertEquals(FollowUpPlan.Ask(listOf(march, april)), plan("when does this salary slip expire?"))
    }

    @Test
    fun `ordinals pick one of the shown documents`() {
        assertEquals(FollowUpPlan.Show(listOf(april), null), plan("the second one"))
        assertEquals(FollowUpPlan.Show(listOf(april), null), plan("the last one"))
        assertEquals(FollowUpPlan.Ask(listOf(march)), plan("when was the first one issued?"))
        assertEquals(FollowUpPlan.Show(listOf(march), DocAction.DELETE), plan("delete the first one"))
    }

    @Test
    fun `verbs apply to what was shown`() {
        assertEquals(FollowUpPlan.Show(listOf(march, april), DocAction.SHARE), plan("share it"))
        assertEquals(FollowUpPlan.Show(listOf(march, april), DocAction.SHARE), plan("send that as pdf"))
        assertEquals(FollowUpPlan.Show(listOf(march, april), null), plan("open it"))
    }

    @Test
    fun `another person, or another document of the same type`() {
        assertEquals(FollowUpPlan.Again(listOf(DocType.SALARY_SLIP), emptySet()), plan("and my wife's?"))
        assertEquals(FollowUpPlan.Again(listOf(DocType.SALARY_SLIP), setOf(1L, 2L)), plan("show another one"))
        assertEquals(FollowUpPlan.Again(listOf(DocType.SALARY_SLIP), setOf(1L, 2L)), plan("show the older one"))
    }

    @Test
    fun `new requests and small talk are not follow-ups`() {
        assertNull(plan("show my pan"))           // a different type is a new search
        assertNull(plan("thanks"))
        assertNull(plan("yes"))
        assertNull(plan("show my trip photos"))
        assertNull(plan("what documents do I have"))
        assertNull(plan("share it", context = null)) // nothing to point at
        assertNull(plan("share it", context = ctx.copy(shown = emptyList())))
    }

    @Test
    fun `the context given to Qwen names types and owners, never OCR text`() {
        val described = ctx.describe()
        assertTrue(described.contains("1. SALARY_SLIP (belongs to Sai)"))
        assertFalse(described.contains("SECRET OCR TEXT"))
    }
}
