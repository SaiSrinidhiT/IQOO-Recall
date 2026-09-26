package com.hackathon.recall.ingest

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.hackathon.recall.data.IndexStateRow
import com.hackathon.recall.data.KvRow
import com.hackathon.recall.data.RecallDb

/**
 * Incremental gallery scan (brief F1): every image whose MediaStore GENERATION_MODIFIED is past the
 * stored watermark gets an index_state row (pending). If MediaStore's version changes (its database
 * was rebuilt), the watermark resets and everything is rechecked; SHA-256 dedup keeps that cheap.
 */
class MediaStoreScanner(private val context: Context, private val db: () -> RecallDb) {
    suspend fun scan(): Int {
        val kv = db().kv()
        val volume = MediaStore.VOLUME_EXTERNAL
        val version = MediaStore.getVersion(context, volume)
        var watermark = kv.get(KEY_GENERATION)?.toLongOrNull() ?: 0L
        if (kv.get(KEY_VERSION) != version) watermark = 0L

        val collection = MediaStore.Images.Media.getContentUri(volume)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.GENERATION_MODIFIED,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        var added = 0
        var maxGen = watermark
        val dao = db().indexState()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Images.Media.GENERATION_MODIFIED} > ?", arrayOf(watermark.toString()),
            "${MediaStore.Images.Media.GENERATION_MODIFIED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val genCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.GENERATION_MODIFIED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString()
                val gen = c.getLong(genCol)
                maxGen = maxOf(maxGen, gen)
                val row = IndexStateRow(
                    uri = uri, generation = gen, relativePath = c.getString(pathCol), mimeType = c.getString(mimeCol),
                    takenAt = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000),
                    status = "pending", updatedAt = System.currentTimeMillis(),
                )
                if (dao.insertIfAbsent(row) != -1L) added++
            }
        }
        kv.put(KvRow(KEY_GENERATION, maxGen.toString()))
        kv.put(KvRow(KEY_VERSION, version))
        return added
    }

    private companion object {
        const val KEY_GENERATION = "mediastore_generation"
        const val KEY_VERSION = "mediastore_version"
    }
}
