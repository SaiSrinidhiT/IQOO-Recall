package com.hackathon.recall.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** ML Kit face detection (bundled model, on-device): only counts faces and sizes the largest one. */
class FaceCounter {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build(),
    )

    suspend fun count(bitmap: Bitmap): Faces? {
        val start = System.nanoTime()
        val faces: List<Face> = suspendCancellableCoroutine<List<Face>?> { cont ->
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return null
        Metrics.record("faces.detect", (System.nanoTime() - start) / 1e6)
        val frame = (bitmap.width * bitmap.height).toFloat().coerceAtLeast(1f)
        val largest = faces.maxOfOrNull { it.boundingBox.width() * it.boundingBox.height() / frame } ?: 0f
        return Faces(faces.size, largest)
    }
}
