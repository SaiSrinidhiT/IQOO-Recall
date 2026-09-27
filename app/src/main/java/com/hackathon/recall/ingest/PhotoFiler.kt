package com.hackathon.recall.ingest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.hackathon.recall.data.PhotoRow
import com.hackathon.recall.data.RecallDb
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.ml.Faces
import com.hackathon.recall.ml.ModelManager
import com.hackathon.recall.ml.PhotoCategorizer
import com.hackathon.recall.ml.VectorMath
import com.hackathon.recall.model.PhotoCategory

/**
 * Files every scanned gallery photo under a personal category (brief: Screenshots, Selfies, People,
 * Food, Trips & places, Bills, Documents) and groups geotagged ones into trips. Reuses the SigLIP2
 * vector the document gate already computed, so a photo costs one extra dot product per label;
 * face detection only runs on the few that look like selfies or people.
 */
class PhotoFiler(
    private val context: Context,
    private val models: ModelManager,
    private val db: RecallDb,
    private val repo: DocumentRepository,
    private val places: PlaceIndex?,
) {
    /**
     * The photo as MediaStore hands it to apps, with its location redacted. This is what the ingest pipeline
     * hashes, OCRs and copies into the vault: a document's saved copy must not carry where it was taken.
     * Location is read separately, only for trip grouping ([latLon]).
     */
    fun readBytes(uri: Uri): ByteArray? = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    private fun canReadLocation() =
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Files one photo after the ingest pipeline ran on it. [imageVector] is null when the gate didn't run. */
    suspend fun file(uri: String, takenAt: Long, relativePath: String?, bytes: ByteArray, docId: Long?, imageVector: FloatArray?) {
        val categorizer = models.photoCategorizer
        val vector = imageVector ?: runCatching { models.siglip?.let { s -> ImageLoader.decode(bytes, GATE_DIM).let { b -> s.embed(b).also { b.recycle() } } } }.getOrNull()
        val probs = if (vector != null && categorizer != null) categorizer.probabilities(vector) else emptyMap()
        val doc = docId?.let { repo.byId(it) }
        val screenshot = relativePath?.lowercase()?.contains("screenshot") == true
        val faces = if (doc == null && !screenshot && PhotoCategorizer.needsFaceCheck(probs)) countFaces(bytes) else null
        val decision = PhotoCategorizer.decide(doc != null, doc?.effectiveType(), doc?.docTypeConfidence ?: 0f, relativePath, probs, faces)
        val gps = latLon(uri)
        db.photos().upsert(
            PhotoRow(
                uri = uri, category = decision.category.db, confidence = decision.confidence, takenAt = takenAt,
                relativePath = relativePath, lat = gps?.first, lon = gps?.second, faces = faces?.count ?: -1,
                docId = doc?.id, tripId = null, vector = vector?.let(VectorMath::toBytes),
                place = gps?.let { places?.nearest(it.first, it.second) },
            ),
        )
    }

    /** A photo that could not be read (deleted since the scan): filed as Other so it isn't retried forever. */
    suspend fun fileUnreadable(uri: String, takenAt: Long, relativePath: String?) {
        db.photos().upsert(PhotoRow(uri, PhotoCategory.OTHER.db, 0f, takenAt, relativePath, null, null, -1, null, null, null))
    }

    private suspend fun countFaces(bytes: ByteArray): Faces? = runCatching {
        val bmp = ImageLoader.decode(bytes, FACE_DIM)
        try { models.faces.count(bmp) } finally { bmp.recycle() }
    }.getOrNull()

    /**
     * EXIF GPS from the unredacted original, when the user allowed media location. Only the EXIF header is
     * parsed and only the coordinates are kept (in the encrypted database); the original's bytes go nowhere.
     * Float precision (a few metres) is plenty for trips; 0,0 means redacted or absent and is dropped.
     */
    @Suppress("DEPRECATION")
    private fun latLon(uri: String): Pair<Double, Double>? {
        if (!canReadLocation()) return null
        return runCatching {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(Uri.parse(uri)))?.use { stream ->
                val out = FloatArray(2)
                if (ExifInterface(stream).getLatLong(out)) out[0].toDouble() to out[1].toDouble() else null
            }
        }.getOrNull()?.takeIf { it.first != 0.0 || it.second != 0.0 }
    }

    /** Re-groups every geotagged photo into trips. Cheap: pure arithmetic over a few thousand points. */
    suspend fun regroupTrips(): Int {
        // Photos located before place names existed get theirs now; a lookup is microseconds.
        places?.let { index -> db.photos().unplaced().forEach { p -> index.nearest(p.lat, p.lon)?.let { db.photos().setPlace(p.uri, it) } } }
        val points = db.photos().located().map { TripClusterer.Point(it.uri, it.takenAt, it.lat, it.lon) }
        val trips = TripClusterer.cluster(points)
        db.photos().clearTrips()
        trips.forEach { (uri, id) -> db.photos().setTrip(uri, id) }
        val n = trips.values.distinct().size
        Log.i(TAG, "trips: ${points.size} geotagged photo(s), $n trip(s)")
        return n
    }

    /**
     * Keeps Bills / Documents in step with the vault: a document retyped (by Qwen or the user) moves
     * between Bills and Documents, and a deleted one goes back to its picture's own category.
     */
    suspend fun syncDocuments() {
        val categorizer = models.photoCategorizer
        for (row in db.photos().withDocs()) {
            val doc = row.docId?.let { repo.byId(it) }
            val probs = if (categorizer != null && row.vector != null) categorizer.probabilities(VectorMath.fromBytes(row.vector)) else emptyMap()
            val faces = row.faces.takeIf { it >= 0 }?.let { Faces(it, 0f) }
            val d = PhotoCategorizer.decide(doc != null, doc?.effectiveType(), doc?.docTypeConfidence ?: 0f, row.relativePath, probs, faces)
            if (d.category.db != row.category || doc == null) db.photos().setCategory(row.uri, d.category.db, d.confidence, doc?.id)
        }
    }

    companion object {
        private const val TAG = "PhotoFiler"
        private const val GATE_DIM = 256
        /** Large enough for faces in a group photo, small enough to decode in a few ms. */
        private const val FACE_DIM = 640
    }
}
