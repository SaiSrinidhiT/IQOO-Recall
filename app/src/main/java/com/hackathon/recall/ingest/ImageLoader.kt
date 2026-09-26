package com.hackathon.recall.ingest

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Decoding for every input kind: JPEG/PNG/HEIC/WebP images and PDF pages, downsampled for inference. */
object ImageLoader {
    const val MAX_DIM = 2048

    /** ImageDecoder applies EXIF orientation and decodes HEIC/WebP; software bitmaps so ML Kit and getPixels work. */
    fun decode(bytes: ByteArray, maxDim: Int = MAX_DIM): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = maxDim / max(w, h).toFloat()
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    class PasswordProtectedPdf : Exception("password-protected PDF")

    /**
     * Renders PDF pages from memory. The bytes go into an anonymous memfd rather than a temp file, so
     * the decrypted document never touches storage. Throws [PasswordProtectedPdf] for encrypted PDFs.
     */
    fun renderPdf(bytes: ByteArray, maxDim: Int = MAX_DIM, maxPages: Int = 30): List<Bitmap> {
        val fd = Os.memfd_create("recall-pdf", 0)
        try {
            FileOutputStream(fd).write(bytes)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            val pfd = ParcelFileDescriptor.dup(fd)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: SecurityException) {
                pfd.close()
                throw PasswordProtectedPdf()
            }
            renderer.use { r ->
                return (0 until minOf(r.pageCount, maxPages)).map { i ->
                    r.openPage(i).use { page ->
                        val scale = maxDim / max(page.width, page.height).toFloat()
                        val bmp = Bitmap.createBitmap((page.width * scale).roundToInt(), (page.height * scale).roundToInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        } finally {
            Os.close(fd)
        }
    }

    fun pdfPageCount(bytes: ByteArray): Int = runCatching { renderPdf(bytes, maxDim = 64).size }.getOrDefault(0)
}
