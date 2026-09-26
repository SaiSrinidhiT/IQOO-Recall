package com.hackathon.recall.actions

import com.hackathon.recall.extract.DigitNormalizer
import com.hackathon.recall.extract.EntityExtractor
import com.hackathon.recall.extract.Verhoeff
import com.hackathon.recall.ocr.Box
import com.hackathon.recall.ocr.OcrWord

/**
 * Where to black-box Aadhaar numbers (brief F6): the first 8 digits of every Verhoeff-valid number,
 * so only the last 4 stay readable. Words are regrouped into visual rows by vertical overlap, so a
 * number split across OCR lines or blocks is still found. Pure geometry: no Android types.
 */
object MaskPlanner {
    data class Plan(val rects: List<Box>, val numbersFound: Int, val numbersBoxed: Int) {
        /** Fail closed: every number we could read must have a box. */
        val complete: Boolean get() = numbersFound == numbersBoxed
    }

    fun planAadhaar(words: List<OcrWord>): Plan {
        val rects = ArrayList<Box>()
        var found = 0
        var boxed = 0
        for (row in rows(words)) {
            val sorted = row.sortedBy { it.box.l }
            // Join the row's words with single spaces, remembering which word each character came from.
            val sb = StringBuilder()
            val owner = ArrayList<Pair<Int, Int>>() // (word index, char index in word); -1 for separators
            sorted.forEachIndexed { wi, w ->
                if (wi > 0) {
                    sb.append(' ')
                    owner += -1 to -1
                }
                val t = DigitNormalizer.normalize(w.text)
                t.forEachIndexed { ci, c ->
                    sb.append(c)
                    owner += wi to ci
                }
            }
            for (m in EntityExtractor.AADHAAR.findAll(sb)) {
                val digits = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
                if (!Verhoeff.isValidAadhaar(digits)) continue
                found++
                // Character positions of the first 8 digits inside the match.
                val positions = m.range.filter { sb[it].isDigit() }.take(8)
                val byWord = positions.map { owner[it] }.filter { it.first >= 0 }.groupBy({ it.first }, { it.second })
                if (byWord.isEmpty()) continue
                for ((wi, chars) in byWord) {
                    val w = sorted[wi]
                    val len = DigitNormalizer.normalize(w.text).length.coerceAtLeast(1)
                    val left = w.box.l + w.box.width * chars.min() / len
                    val right = w.box.l + w.box.width * (chars.max() + 1) / len
                    val padX = w.box.height * 0.15f
                    val padY = w.box.height * 0.2f
                    rects += Box(left, w.box.t, right, w.box.b).inflate(padX, padY)
                }
                boxed++
            }
        }
        return Plan(rects, found, boxed)
    }

    /** Groups words into rows: a word joins a row when it overlaps the row's first word vertically by half. */
    fun rows(words: List<OcrWord>): List<List<OcrWord>> {
        val rows = ArrayList<MutableList<OcrWord>>()
        for (w in words.sortedBy { it.box.t }) {
            val row = rows.firstOrNull { it.first().box.verticalOverlap(w.box) >= 0.5f }
            if (row != null) row += w else rows += mutableListOf(w)
        }
        return rows
    }
}
