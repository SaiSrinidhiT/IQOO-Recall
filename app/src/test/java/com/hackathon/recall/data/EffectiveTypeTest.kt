package com.hackathon.recall.data

import com.hackathon.recall.actions.ChecklistEngine
import com.hackathon.recall.actions.ItemStatus
import com.hackathon.recall.actions.TaskTemplate
import com.hackathon.recall.actions.TemplateItem
import com.hackathon.recall.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Below 80% confidence and unconfirmed, a document's type counts as "Other" everywhere it is grouped. */
class EffectiveTypeTest {
    private fun doc(id: Long, type: DocType, confidence: Float, confirmed: Boolean = false) = DocumentEntity(
        id = id, sourceUri = null, sourceKind = "gallery", sha256 = "sha$id", vaultPath = "$id.enc",
        docType = type.name, docTypeConfidence = confidence, docTypeSource = "rule", titleEn = type.labelEn,
        ocrText = "", scripts = "", capturedAt = 1_780_000_000_000, issuedOn = null, expiryOn = null,
        expirySource = null, ownerName = null, createdAt = 0, isUserConfirmed = confirmed, mimeType = "image/jpeg",
    )

    @Test
    fun `an unsure guess is Other, a confident or confirmed one keeps its type`() {
        assertEquals(DocType.OTHER_DOCUMENT, doc(1, DocType.LOAN_SANCTION_EMI, 0.55f).effectiveType())
        assertEquals(DocType.OTHER_DOCUMENT, doc(2, DocType.LOAN_SANCTION_EMI, 0.79f).effectiveType())
        assertEquals(DocType.LOAN_SANCTION_EMI, doc(3, DocType.LOAN_SANCTION_EMI, 0.8f).effectiveType())
        assertEquals(DocType.LOAN_SANCTION_EMI, doc(4, DocType.LOAN_SANCTION_EMI, 0.3f, confirmed = true).effectiveType())
        assertFalse(doc(5, DocType.PAN, 0.5f).isTypeConfident())
        assertTrue(doc(6, DocType.PAN, 0.97f).isTypeConfident())
    }

    /** Stored titles lead with the detected type; an unsure document must not show that label. */
    @Test
    fun `an unsure document's title drops the guessed type label`() {
        val unsure = doc(1, DocType.LOAN_SANCTION_EMI, 0.55f).copy(titleEn = "Loan sanction letter or EMI schedule · 12 Mar 2026 · Ravi Kumar")
        assertEquals("12 Mar 2026 · Ravi Kumar", unsure.displayTitle())
        val sure = unsure.copy(docTypeConfidence = 0.9f)
        assertEquals("Loan sanction letter or EMI schedule · 12 Mar 2026 · Ravi Kumar", sure.displayTitle())
        assertEquals("", doc(2, DocType.LOAN_SANCTION_EMI, 0.4f).displayTitle())
        // Retyped since the scan (by Qwen or the user): the old label goes, even though it is now confident.
        val retyped = sure.copy(docType = DocType.BANK_STATEMENT.name)
        assertEquals("12 Mar 2026 · Ravi Kumar", retyped.displayTitle())
    }

    /** The reported bug: a 55% "loan sanction" photo was listed under the Loan sanction / EMI header. */
    @Test
    fun `an unsure guess does not fill a checklist header of its guessed type`() {
        val template = TaskTemplate("home_loan", "Home loan", listOf(TemplateItem(DocType.LOAN_SANCTION_EMI, 1)))
        val today = LocalDate.of(2026, 9, 27)

        val unsureOnly = ChecklistEngine.evaluate(template, listOf(doc(1, DocType.LOAN_SANCTION_EMI, 0.55f).toSummary()), today)
        assertTrue(unsureOnly.items.single().found.isEmpty())
        assertEquals(ItemStatus.MISSING, unsureOnly.items.single().status)

        val both = listOf(doc(1, DocType.LOAN_SANCTION_EMI, 0.55f), doc(2, DocType.LOAN_SANCTION_EMI, 0.92f)).map { it.toSummary() }
        assertEquals(listOf(2L), ChecklistEngine.evaluate(template, both, today).items.single().found.map { it.id })
    }
}
