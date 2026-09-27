package com.hackathon.recall.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert
    suspend fun insert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Delete
    suspend fun delete(doc: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun byId(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE sha256 = :sha LIMIT 1")
    suspend fun bySha(sha: String): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY captured_at DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE doc_type IN (:types)")
    suspend fun byTypes(types: List<String>): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE expiry_on IS NOT NULL ORDER BY expiry_on ASC")
    fun observeWithExpiry(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE dup_of_doc_id IS NOT NULL")
    fun observePendingDuplicates(): Flow<List<DocumentEntity>>

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun count(): Int

    @Query("SELECT * FROM documents WHERE needs_llm != 0")
    suspend fun needingLlm(): List<DocumentEntity>

    /** One-time cleanup (AppContainer.start): owner names OwnerNameExtractor should never have set on these types. */
    @Query("UPDATE documents SET owner_name = NULL WHERE owner_name IS NOT NULL AND doc_type IN (:types)")
    suspend fun clearOwnerNames(types: List<String>): Int
}

@Dao
interface EntityDao {
    @Insert
    suspend fun insertAll(rows: List<EntityRow>)

    @Query("SELECT * FROM entities WHERE doc_id = :docId")
    suspend fun forDoc(docId: Long): List<EntityRow>
}

/** A chunk vector without the text, for the in-memory vector index. */
data class ChunkVector(val id: Long, val docId: Long, val vector: ByteArray)

@Dao
interface ChunkDao {
    @Insert
    suspend fun insertAll(rows: List<ChunkRow>): List<Long>

    @Query("SELECT * FROM chunks WHERE doc_id = :docId ORDER BY chunk_ix")
    suspend fun forDoc(docId: Long): List<ChunkRow>

    @Query("SELECT id, doc_id AS docId, vector FROM chunks WHERE vector IS NOT NULL")
    suspend fun allVectors(): List<ChunkVector>

    @Query("SELECT * FROM chunks WHERE vector IS NULL LIMIT :limit")
    suspend fun missingVectors(limit: Int): List<ChunkRow>

    @Query("UPDATE chunks SET vector = :vector WHERE id = :id")
    suspend fun setVector(id: Long, vector: ByteArray)

    @Query("SELECT COUNT(*) FROM chunks")
    suspend fun count(): Int
}

@Dao
interface ImageVectorDao {
    @Upsert
    suspend fun upsert(row: ImageVectorRow)

    @Query("SELECT * FROM image_vectors")
    suspend fun all(): List<ImageVectorRow>
}

@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PageRow>)

    @Query("SELECT * FROM pages WHERE doc_id = :docId ORDER BY page")
    suspend fun forDoc(docId: Long): List<PageRow>
}

@Dao
interface ReminderDao {
    /** REPLACE: a new row for the same (doc_id, offset_days) supersedes the old one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ReminderRow): Long

    @Query("SELECT * FROM reminders WHERE doc_id = :docId")
    suspend fun forDoc(docId: Long): List<ReminderRow>

    @Query("SELECT * FROM reminders WHERE state = 'scheduled'")
    suspend fun scheduled(): List<ReminderRow>

    @Query("UPDATE reminders SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: String)

    @Query("DELETE FROM reminders WHERE doc_id = :docId")
    suspend fun deleteForDoc(docId: Long)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): ReminderRow?
}

/** Count of index_state rows per status, for progress and the benchmark screen. */
data class StatusCount(val status: String, val n: Int)

@Dao
interface IndexStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: IndexStateRow): Long

    @Update
    suspend fun update(row: IndexStateRow)

    @Query("SELECT * FROM index_state WHERE uri = :uri")
    suspend fun byUri(uri: String): IndexStateRow?

    @Query("SELECT * FROM index_state WHERE status = 'pending' ORDER BY generation ASC LIMIT :limit")
    suspend fun pending(limit: Int): List<IndexStateRow>

    @Query("SELECT * FROM index_state WHERE status = 'skipped_non_doc' ORDER BY updated_at DESC LIMIT :limit")
    suspend fun skipped(limit: Int): List<IndexStateRow>

    @Query("SELECT status, COUNT(*) AS n FROM index_state GROUP BY status")
    fun observeCounts(): Flow<List<StatusCount>>

    @Query("SELECT status, COUNT(*) AS n FROM index_state GROUP BY status")
    suspend fun counts(): List<StatusCount>

    /** Photos indexed before gallery categories existed: finished, but not yet filed in `photos`. */
    @Query("SELECT * FROM index_state WHERE status IN ('done', 'skipped_non_doc') AND uri NOT IN (SELECT uri FROM photos) LIMIT :limit")
    suspend fun unfiled(limit: Int): List<IndexStateRow>
}

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(row: KvRow)
}

data class CategoryCount(val category: String, val n: Int)

@Dao
interface PhotoDao {
    @Upsert
    suspend fun upsert(row: PhotoRow)

    @Query("SELECT uri FROM photos")
    suspend fun uris(): List<String>

    @Query("SELECT category, COUNT(*) AS n FROM photos GROUP BY category")
    fun observeCounts(): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(DISTINCT trip_id) FROM photos WHERE trip_id IS NOT NULL")
    fun observeTripCount(): Flow<Int>

    @Query("SELECT * FROM photos WHERE category = :category ORDER BY taken_at DESC")
    fun observeCategory(category: String): Flow<List<PhotoRow>>

    @Query("SELECT * FROM photos WHERE trip_id IS NOT NULL ORDER BY taken_at DESC")
    fun observeTrips(): Flow<List<PhotoRow>>

    @Query("SELECT * FROM photos WHERE category = :category AND taken_at BETWEEN :from AND :to ORDER BY taken_at DESC LIMIT :limit")
    suspend fun byCategory(category: String, from: Long, to: Long, limit: Int): List<PhotoRow>

    @Query("SELECT * FROM photos WHERE trip_id IS NOT NULL AND taken_at BETWEEN :from AND :to ORDER BY taken_at DESC LIMIT :limit")
    suspend fun inTrips(from: Long, to: Long, limit: Int): List<PhotoRow>

    @Query("SELECT uri, taken_at, lat, lon FROM photos WHERE lat IS NOT NULL AND lon IS NOT NULL")
    suspend fun located(): List<LocatedPhoto>

    @Query("UPDATE photos SET trip_id = NULL")
    suspend fun clearTrips()

    @Query("UPDATE photos SET trip_id = :tripId WHERE uri = :uri")
    suspend fun setTrip(uri: String, tripId: Long)

    @Query("SELECT * FROM photos WHERE doc_id = :docId LIMIT 1")
    suspend fun byDoc(docId: Long): PhotoRow?

    @Query("SELECT * FROM photos WHERE doc_id IS NOT NULL")
    suspend fun withDocs(): List<PhotoRow>

    @Query("UPDATE photos SET category = :category, confidence = :confidence, doc_id = :docId WHERE uri = :uri")
    suspend fun setCategory(uri: String, category: String, confidence: Float, docId: Long?)

    @Query("SELECT * FROM photos WHERE uri = :uri")
    suspend fun byUri(uri: String): PhotoRow?

    @Query("SELECT category, COUNT(*) AS n FROM photos GROUP BY category")
    suspend fun counts(): List<CategoryCount>

    @Query("SELECT DISTINCT place FROM photos WHERE place IS NOT NULL")
    suspend fun places(): List<String>

    @Query("SELECT uri, taken_at, lat, lon FROM photos WHERE lat IS NOT NULL AND lon IS NOT NULL AND place IS NULL")
    suspend fun unplaced(): List<LocatedPhoto>

    @Query("UPDATE photos SET place = :place WHERE uri = :uri")
    suspend fun setPlace(uri: String, place: String)

    /** Photos taken at [place]; [category] null means any category. */
    @Query("SELECT * FROM photos WHERE place = :place COLLATE NOCASE AND (:category IS NULL OR category = :category) AND taken_at BETWEEN :from AND :to ORDER BY taken_at DESC LIMIT :limit")
    suspend fun atPlace(place: String, category: String?, from: Long, to: Long, limit: Int): List<PhotoRow>

    @Query("DELETE FROM photos WHERE uri = :uri")
    suspend fun delete(uri: String)
}

data class LocatedPhoto(val uri: String, @ColumnInfo(name = "taken_at") val takenAt: Long, val lat: Double, val lon: Double)
