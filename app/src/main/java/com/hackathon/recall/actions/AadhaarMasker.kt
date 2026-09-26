package com.hackathon.recall.actions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hackathon.recall.extract.EntityExtractor
import com.hackathon.recall.ocr.Box
import com.hackathon.recall.ocr.OcrEngine
import com.hackathon.recall.ocr.OcrResult
import com.hackathon.recall.ocr.await

/**
 * Masks one exported page (brief F6) and fails closed:
 * 1. black-box the first 8 digits of every Aadhaar number, using both the boxes stored at ingest and
 *    a fresh OCR of the exact bitmap being exported;
 * 2. black-box every QR code (ML Kit barcode scanning, bundled model);
 * 3. re-OCR and re-scan the masked page: if any Verhoeff-valid Aadhaar number or QR code is still
 *    readable, or a number was known to be on the page but could not be located, the page is blocked.
 */
class AadhaarMasker(private val ocr: OcrEngine) {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    sealed interface Result {
        data class Ok(val bitmap: Bitmap, val boxes: Int) : Result
        data class Blocked(val reason: String) : Result
    }

    suspend fun mask(page: Bitmap, storedLayout: OcrResult?, pageHasAadhaar: Boolean): Result {
        val work = page.copy(Bitmap.Config.ARGB_8888, true)
        val rects = ArrayList<Box>()

        storedLayout?.let { layout ->
            val words = layout.lines.flatMap { it.words }.map { it.copy(box = it.box.scaled(work.width, work.height)) }
            rects += MaskPlanner.planAadhaar(words).rects
        }
        val fresh = MaskPlanner.planAadhaar(ocr.recognizeLatin(work).lines.flatMap { it.words })
        if (!fresh.complete) return Result.Blocked("An Aadhaar number was read on this page but could not be located.")
        rects += fresh.rects
        if (pageHasAadhaar && rects.isEmpty()) return Result.Blocked("This page has an Aadhaar number that could not be located for masking.")

        for (qr in scanner.process(InputImage.fromBitmap(work, 0)).await()) {
            val b = qr.boundingBox ?: return Result.Blocked("A QR code was found but could not be located.")
            val pad = b.width() * 0.08f
            rects += Box(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()).inflate(pad, pad)
        }

        val canvas = Canvas(work)
        val paint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        for (r in rects) canvas.drawRect(r.l, r.t, r.r, r.b, paint)

        // Verify the output itself, not our intentions.
        if (EntityExtractor.aadhaarNumbers(ocr.recognizeLatin(work).text).isNotEmpty()) {
            return Result.Blocked("An Aadhaar number is still readable after masking.")
        }
        if (scanner.process(InputImage.fromBitmap(work, 0)).await().isNotEmpty()) {
            return Result.Blocked("A QR code is still readable after masking.")
        }
        return Result.Ok(work, rects.size)
    }
}
