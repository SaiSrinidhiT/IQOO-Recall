package com.hackathon.recall.actions

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.hackathon.recall.R
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.inLang
import com.hackathon.recall.ingest.ImageLoader
import com.hackathon.recall.model.EntityKind
import com.hackathon.recall.model.Lang
import com.hackathon.recall.ocr.OcrResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * Merges a checklist's found documents into one PDF (brief F5): a cover page listing the contents,
 * then every page in template order, each masked by [AadhaarMasker]. If any page fails masking the
 * whole pack is refused (fail closed). Output is a temp file shared by FileProvider and deleted after.
 */
class PackBuilder(private val context: Context, private val repo: DocumentRepository, private val masker: AadhaarMasker) {
    sealed interface Result {
        data class Ready(val file: File, val pages: Int) : Result
        data class Blocked(val document: String, val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun build(checklist: ChecklistResult, templateTitle: String, lang: Lang): Result = withContext(Dispatchers.Default) {
        val pdf = PdfDocument()
        try {
            val titles = checklist.packDocIds.associateWith { repo.byId(it)?.titleEn.orEmpty() }
            cover(pdf, checklist, templateTitle, lang, titles)
            var pageNo = 1
            for (docId in checklist.packDocIds) {
                val doc = repo.byId(docId) ?: continue
                val bytes = repo.readOriginal(doc)
                val bitmaps = if (doc.mimeType == "application/pdf") ImageLoader.renderPdf(bytes, RENDER_DIM) else listOf(ImageLoader.decode(bytes, RENDER_DIM))
                val layouts = repo.pages(docId).map { json.decodeFromString(OcrResult.serializer(), it.layoutJson) }
                val aadhaarPages = repo.entities(docId).filter { it.kind == EntityKind.AADHAAR.db }.map { it.page }.toSet()
                for ((i, bmp) in bitmaps.withIndex()) {
                    when (val r = masker.mask(bmp, layouts.getOrNull(i), i in aadhaarPages)) {
                        is AadhaarMasker.Result.Blocked -> return@withContext Result.Blocked(doc.titleEn, r.reason)
                        is AadhaarMasker.Result.Ok -> {
                            pageNo++
                            addImagePage(pdf, r.bitmap, pageNo)
                            r.bitmap.recycle()
                        }
                    }
                    bmp.recycle()
                }
            }
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            val out = File(dir, "recall-pack-${System.currentTimeMillis()}.pdf")
            out.outputStream().use { pdf.writeTo(it) }
            Result.Ready(out, pageNo)
        } catch (t: Throwable) {
            Result.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            pdf.close()
        }
    }

    private fun cover(pdf: PdfDocument, checklist: ChecklistResult, title: String, lang: Lang, titles: Map<Long, String>) {
        val strings = context.inLang(lang)
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, 1).create())
        val c = page.canvas
        val titlePaint = TextPaint().apply { color = Color.BLACK; textSize = 22f; isFakeBoldText = true; isAntiAlias = true }
        val body = TextPaint().apply { color = Color.DKGRAY; textSize = 12f; isAntiAlias = true }
        var y = 60f
        fun text(s: String, p: TextPaint) {
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, A4_W - 2 * MARGIN).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            c.save()
            c.translate(MARGIN.toFloat(), y)
            layout.draw(c)
            c.restore()
            y += layout.height + 8f
        }
        text(title, titlePaint)
        text(strings.getString(R.string.pack_cover_generated, LocalDate.now().toString()), body)
        y += 8f
        for (item in checklist.items) {
            val name = strings.docTypeName(item.item.docType)
            val status = "${item.found.size} / ${item.item.count}" + if (item.status == ItemStatus.MISSING) " — " + strings.getString(R.string.status_missing) else ""
            text("• $name: $status", body)
            for (d in item.found) text("    ${titles[d.id].orEmpty()}", body)
        }
        y += 12f
        text(strings.getString(R.string.pack_cover_masking_note), body)
        pdf.finishPage(page)
    }

    private fun addImagePage(pdf: PdfDocument, bmp: Bitmap, number: Int) {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, number).create())
        val scale = minOf((A4_W - 2f * MARGIN) / bmp.width, (A4_H - 2f * MARGIN) / bmp.height)
        val w = bmp.width * scale
        val h = bmp.height * scale
        val left = (A4_W - w) / 2
        val top = (A4_H - h) / 2
        page.canvas.drawBitmap(bmp, null, RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        pdf.finishPage(page)
    }

    /** Share on tap only (brief §7). The caller deletes [file] when the user comes back. */
    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Masked outputs are temporary: removed after sharing and at every start. */
    fun deleteSharedFiles() {
        File(context.cacheDir, SHARE_DIR).listFiles()?.forEach { it.delete() }
    }

    companion object {
        const val SHARE_DIR = "share"
        private const val A4_W = 595
        private const val A4_H = 842
        private const val MARGIN = 36
        private const val RENDER_DIM = 1600
    }
}
