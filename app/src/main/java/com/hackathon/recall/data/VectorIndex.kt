package com.hackathon.recall.data

import com.hackathon.recall.ml.VectorMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureNanoTime

/**
 * Brute-force cosine search over all chunk vectors, held in memory (brief §4: fine below 50k chunks).
 * Vectors are L2-normalized at write time, so cosine is a dot product.
 */
class VectorIndex(private val chunks: ChunkDao) {
    private val mutex = Mutex()
    private var loaded = false
    private val chunkIds = ArrayList<Long>()
    private val docIds = ArrayList<Long>()
    private val vectors = ArrayList<FloatArray>()

    @Volatile
    var lastSearchMicros: Long = 0
        private set

    val size: Int get() = vectors.size

    suspend fun ensureLoaded() = mutex.withLock {
        if (loaded) return@withLock
        for (cv in chunks.allVectors()) {
            chunkIds += cv.id
            docIds += cv.docId
            vectors += VectorMath.fromBytes(cv.vector)
        }
        loaded = true
    }

    suspend fun add(chunkId: Long, docId: Long, vector: FloatArray) = mutex.withLock {
        if (!loaded) return@withLock // picked up by the next full load
        chunkIds += chunkId
        docIds += docId
        vectors += vector
    }

    suspend fun removeDoc(docId: Long) = mutex.withLock {
        for (i in docIds.indices.reversed()) {
            if (docIds[i] == docId) {
                chunkIds.removeAt(i)
                docIds.removeAt(i)
                vectors.removeAt(i)
            }
        }
    }

    /** Documents ranked by their best chunk's cosine similarity to [query]. */
    suspend fun search(query: FloatArray, limit: Int = 50): List<Pair<Long, Float>> {
        ensureLoaded()
        return mutex.withLock {
            val best = HashMap<Long, Float>()
            lastSearchMicros = measureNanoTime {
                for (i in vectors.indices) {
                    val v = vectors[i]
                    if (v.size != query.size) continue
                    val s = VectorMath.dot(query, v)
                    val d = docIds[i]
                    if (s > (best[d] ?: -2f)) best[d] = s
                }
            } / 1000
            best.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
        }
    }
}
