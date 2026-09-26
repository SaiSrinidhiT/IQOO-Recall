package com.hackathon.recall.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import com.hackathon.recall.ml.Metrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR strategy (brief §4): ML Kit Latin and Devanagari (bundled models, offline) run on every page and
 * are merged; if fewer than [MIN_USEFUL_CHARS] useful characters come back, or most lines are low
 * confidence, Tesseract `tel+eng` runs too (ML Kit has no Telugu). Word boxes are kept for masking.
 */
class OcrEngine(private val context: Context) {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val devanagari = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val tessMutex = Mutex()
    private var tess: TessBaseAPI? = null

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        val image = InputImage.fromBitmap(bitmap, 0)
        val start = System.nanoTime()
        val l = latin.process(image).await()
        val d = devanagari.process(image).await()
        var result = merge(toLines(l), toLines(d), bitmap.width, bitmap.height)
        Metrics.record("ocr.mlkit", (System.nanoTime() - start) / 1e6)

        val useful = ScriptDetector.usefulChars(result.text)
        val lowConfidence = result.lines.isNotEmpty() && result.lines.count { it.confidence < 0.5f } * 2 > result.lines.size
        if (useful < MIN_USEFUL_CHARS || lowConfidence) {
            val t = tesseract(bitmap, "tel+eng")
            if (t != null && ScriptDetector.usefulChars(t.text) > useful) {
                result = if (useful == 0) t else t.copy(engine = "mlkit+tesseract")
            }
        }
        return result
    }

    /** Latin-only pass used to verify masked exports (fast; digits are what matter there). */
    suspend fun recognizeLatin(bitmap: Bitmap): OcrResult {
        val l = latin.process(InputImage.fromBitmap(bitmap, 0)).await()
        return OcrResult(toLines(l), bitmap.width, bitmap.height, "mlkit")
    }

    private fun toLines(text: Text): List<OcrLine> = text.textBlocks.flatMap { block ->
        block.lines.mapNotNull { line ->
            val box = line.boundingBox?.toBox() ?: return@mapNotNull null
            val words = line.elements.mapNotNull { e -> e.boundingBox?.let { OcrWord(e.text, it.toBox(), e.confidence) } }
            OcrLine(line.text, box, words, line.confidence)
        }
    }

    /**
     * Latin lines everywhere, except where the Devanagari recognizer read Devanagari script: there its
     * line replaces any overlapping Latin line (which would be a garbled reading of the same Hindi text).
     */
    private fun merge(latinLines: List<OcrLine>, devaLines: List<OcrLine>, w: Int, h: Int): OcrResult {
        val hindi = devaLines.filter { l -> l.text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.DEVANAGARI } }
        val kept = latinLines.filter { lat -> hindi.none { overlaps(it.box, lat.box) } }
        val lines = (kept + hindi).sortedWith(compareBy({ it.box.t }, { it.box.l }))
        return OcrResult(lines, w, h, "mlkit")
    }

    private fun overlaps(a: Box, b: Box): Boolean {
        val ix = minOf(a.r, b.r) - maxOf(a.l, b.l)
        val iy = minOf(a.b, b.b) - maxOf(a.t, b.t)
        if (ix <= 0 || iy <= 0) return false
        val inter = ix * iy
        return inter / minOf(a.width * a.height, b.width * b.height).coerceAtLeast(1f) > 0.5f
    }

    private suspend fun tesseract(bitmap: Bitmap, langs: String): OcrResult? = tessMutex.withLock {
        withContext(Dispatchers.Default) {
            val start = System.nanoTime()
            val api = tess ?: TessBaseAPI().also { api ->
                val root = TessData.ensureInstalled(context)
                if (!api.init(root.absolutePath, langs)) return@withContext null
                tess = api
            }
            api.setImage(bitmap)
            val lines = ArrayList<OcrLine>()
            val it = api.resultIterator
            if (it != null) {
                it.begin()
                var words = ArrayList<OcrWord>()
                do {
                    val word = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                    val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    if (word.isNotEmpty()) words.add(OcrWord(word, rect.toBox(), it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD) / 100f))
                    if (it.isAtFinalElement(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE, TessBaseAPI.PageIteratorLevel.RIL_WORD) && words.isNotEmpty()) {
                        val box = words.map { w -> w.box }.reduce(Box::union)
                        lines.add(OcrLine(words.joinToString(" ") { w -> w.text }, box, words, words.map { w -> w.confidence }.average().toFloat()))
                        words = ArrayList()
                    }
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                it.delete()
            }
            api.clear()
            Metrics.record("ocr.tesseract", (System.nanoTime() - start) / 1e6)
            OcrResult(lines, bitmap.width, bitmap.height, "tesseract")
        }
    }

    companion object {
        const val MIN_USEFUL_CHARS = 40
    }
}

private fun Rect.toBox() = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/** Tesseract needs its traineddata on the filesystem: copied once from assets/tessdata. */
object TessData {
    fun ensureInstalled(context: Context): File {
        val root = File(context.noBackupFilesDir, "tess")
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (name in context.assets.list("tessdata").orEmpty()) {
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            context.assets.open("tessdata/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return root
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
