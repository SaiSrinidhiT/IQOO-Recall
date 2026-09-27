package com.hackathon.recall.search

import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import com.hackathon.recall.model.PhotoCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Gallery requests go to photos; anything naming a document still searches documents. */
class PhotoRoutingTest {
    private val rules = RuleFallbackParser(Lexicon.parse(File("src/main/assets/rule_lexicon.json").readText()))
    private val today = LocalDate.of(2026, 9, 27)

    private fun photo(q: String): PhotoCategory? = rules.parse(q, today).let { if (it.kind == IntentKind.PHOTOS) it.photoCategory else null }

    @Test
    fun `each category is recognised, in English, Hindi and Telugu`() {
        assertEquals(PhotoCategory.SELFIE, photo("show my selfies"))
        assertEquals(PhotoCategory.SCREENSHOT, photo("screenshots from last month"))
        assertEquals(PhotoCategory.PEOPLE, photo("photos with friends"))
        assertEquals(PhotoCategory.FOOD, photo("food pics"))
        assertEquals(PhotoCategory.PLACES, photo("my trip photos"))
        assertEquals(PhotoCategory.SELFIE, photo("मेरी सेल्फी दिखाओ"))
        assertEquals(PhotoCategory.PLACES, photo("నా ట్రిప్ ఫోటోలు"))
    }

    @Test
    fun `a greeting in front of a photo request is not small talk`() {
        assertEquals(PhotoCategory.SELFIE, photo("hi, show my selfies"))
    }

    @Test
    fun `a named document wins over a photo word`() {
        val bill = rules.parse("restaurant bill from my goa trip", today)
        assertEquals(IntentKind.FIND, bill.kind)
        assertEquals(listOf(DocType.RECEIPT_INVOICE), bill.docTypes)
        assertNull(photo("payment screenshot"))
        assertNull(photo("family health insurance"))
    }

    @Test
    fun `dates narrow photo requests`() {
        val i = rules.parse("selfies from last month", today)
        assertEquals(LocalDate.of(2026, 8, 1), i.dateFrom)
        assertEquals(LocalDate.of(2026, 8, 31), i.dateTo)
    }

    @Test
    fun `Qwen photos replies are validated`() {
        val ok = IntentValidator.validate(IntentJson(intent = "photos", photoCategory = "food"), "lunch pics", Lang.EN)
        assertEquals(IntentKind.PHOTOS, ok.kind)
        assertEquals(PhotoCategory.FOOD, ok.photoCategory)
        // No category, or one chat can't answer from photos: fall back to a document search.
        assertEquals(IntentKind.FIND, IntentValidator.validate(IntentJson(intent = "photos"), "pics", Lang.EN).kind)
        assertEquals(IntentKind.FIND, IntentValidator.validate(IntentJson(intent = "photos", photoCategory = "documents"), "x", Lang.EN).kind)
    }
}
