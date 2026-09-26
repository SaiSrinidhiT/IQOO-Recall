package com.hackathon.recall.data

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
}

@Dao
interface KvDao {
    @Query("SELECT value FROM kv WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(row: KvRow)
}
