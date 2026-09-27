package com.hackathon.recall.ml

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.PhotoCategory
import com.hackathon.recall.model.PhotoCategory.BILLS
import com.hackathon.recall.model.PhotoCategory.DOCUMENTS
import com.hackathon.recall.model.PhotoCategory.FOOD
import com.hackathon.recall.model.PhotoCategory.OTHER
import com.hackathon.recall.model.PhotoCategory.PEOPLE
import com.hackathon.recall.model.PhotoCategory.PLACES
import com.hackathon.recall.model.PhotoCategory.SCREENSHOT
import com.hackathon.recall.model.PhotoCategory.SELFIE
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCategorizerTest {
    private fun decide(
        probs: Map<PhotoCategory, Float>,
        faces: Faces? = null,
        path: String? = "DCIM/Camera/",
        saved: Boolean = false,
        type: DocType? = null,
    ) = PhotoCategorizer.decide(saved, type, 0.9f, path, probs, faces).category

    @Test
    fun `a saved document goes to Bills or Documents by its checked type`() {
        assertEquals(BILLS, decide(mapOf(FOOD to 0.9f), saved = true, type = DocType.RECEIPT_INVOICE))
        assertEquals(BILLS, decide(emptyMap(), saved = true, type = DocType.PAYMENT_SCREENSHOT))
        assertEquals(DOCUMENTS, decide(emptyMap(), saved = true, type = DocType.AADHAAR))
        // Unsure type (below 80 %): still a document, never guessed into Bills.
        assertEquals(DOCUMENTS, decide(emptyMap(), saved = true, type = DocType.OTHER_DOCUMENT))
    }

    @Test
    fun `the Screenshots folder wins over the picture`() {
        assertEquals(SCREENSHOT, decide(mapOf(FOOD to 0.95f), path = "Pictures/Screenshots/"))
    }

    @Test
    fun `the likeliest category is used when it is sure enough`() {
        assertEquals(FOOD, decide(mapOf(FOOD to 0.7f, PLACES to 0.3f)))
        assertEquals(OTHER, decide(mapOf(FOOD to 0.35f, PLACES to 0.33f, OTHER to 0.32f)))
        // The gate rejected it, so a document-looking photo isn't filed under Documents.
        assertEquals(OTHER, decide(mapOf(DOCUMENTS to 0.8f, BILLS to 0.2f)))
        // A receipt photo the gate rejected is still a bill.
        assertEquals(BILLS, decide(mapOf(BILLS to 0.8f, DOCUMENTS to 0.2f)))
    }

    @Test
    fun `faces decide between selfie, people and neither`() {
        val selfieLike = mapOf(SELFIE to 0.5f, PEOPLE to 0.2f, PLACES to 0.25f, OTHER to 0.05f)
        assertEquals(SELFIE, decide(selfieLike, Faces(1, 0.2f)))
        assertEquals(PEOPLE, decide(selfieLike, Faces(4, 0.05f)))
        // No face at all: the best non-person category, renormalised (0.25 / 0.3 ≈ 0.83).
        assertEquals(PLACES, decide(selfieLike, Faces(0, 0f)))
        // Face detection didn't run: trust the picture.
        assertEquals(SELFIE, decide(selfieLike, null))
    }

    @Test
    fun `face detection runs only on likely selfie or people photos`() {
        assertEquals(true, PhotoCategorizer.needsFaceCheck(mapOf(SELFIE to 0.15f, PEOPLE to 0.15f)))
        assertEquals(false, PhotoCategorizer.needsFaceCheck(mapOf(FOOD to 0.9f, PEOPLE to 0.1f)))
    }
}
