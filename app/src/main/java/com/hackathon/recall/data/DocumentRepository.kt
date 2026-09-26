package com.hackathon.recall.data

import androidx.room.withTransaction
import com.hackathon.recall.actions.DocSummary
import com.hackathon.recall.ml.VectorMath
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.DocTypeSource
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Everything the ingest pipeline produced for one document, written in a single transaction. */
class NewDocument(
    val doc: DocumentEntity,
    val entities: List<EntityRow>,
    val pages: List<PageRow>,
    /** Chunk text and its Nomic vector (null when the embedder is unavailable). */
    val chunks: List<Pair<String, FloatArray?>>,
    val imageVector: FloatArray?,
    /** Original bytes, stored encrypted under [DocumentEntity.vaultPath]. */
    val originalBytes: ByteArray,
)

class DocumentRepository(
    private val db: RecallDb,
    private val fts: FtsIndex,
    private val vectors: VectorIndex,
    private val vault: VaultFileStore,
) {
    private val docs = db.documents()

    fun observeDocuments(): Flow<List<DocumentEntity>> = docs.observeAll()
    fun observeDocument(id: Long): Flow<DocumentEntity?> = docs.observe(id)
    fun observeWithExpiry(): Flow<List<DocumentEntity>> = docs.observeWithExpiry()
    fun observeDuplicates(): Flow<List<DocumentEntity>> = docs.observePendingDuplicates()

    suspend fun byId(id: Long) = docs.byId(id)
    suspend fun byIds(ids: List<Long>) = docs.byIds(ids)
    suspend fun bySha(sha: String) = docs.bySha(sha)
    suspend fun all() = docs.all()
    suspend fun count() = docs.count()
    suspend fun entities(docId: Long) = db.entities().forDoc(docId)
    suspend fun pages(docId: Long) = db.pages().forDoc(docId)
    suspend fun chunks(docId: Long) = db.chunks().forDoc(docId)
    suspend fun chunkCount() = db.chunks().count()
    suspend fun imageVectors() = db.imageVectors().all()

    /** Vault file first (so a DB row never points at a missing file), then all rows in one transaction. */
    suspend fun save(nd: NewDocument): Long {
        vault.write(nd.doc.vaultPath, nd.originalBytes)
        val chunkIds = ArrayList<Long>()
        val id = db.withTransaction {
            val id = docs.insert(nd.doc)
            db.entities().insertAll(nd.entities.map { it.copy(docId = id) })
            db.pages().insertAll(nd.pages.map { it.copy(docId = id) })
            val rows = nd.chunks.mapIndexed { i, (text, v) ->
                ChunkRow(docId = id, chunkIx = i, text = text, vector = v?.let(VectorMath::toBytes))
            }
            chunkIds += db.chunks().insertAll(rows)
            chunkIds.forEachIndexed { i, cid -> fts.insert(cid, id, nd.chunks[i].first, nd.doc.titleEn) }
            nd.imageVector?.let { db.imageVectors().upsert(ImageVectorRow(id, VectorMath.toBytes(it))) }
            id
        }
        chunkIds.forEachIndexed { i, cid -> nd.chunks[i].second?.let { vectors.add(cid, id, it) } }
        return id
    }

    suspend fun delete(id: Long) {
        val doc = docs.byId(id) ?: return
        db.withTransaction {
            fts.deleteDoc(id)
            docs.delete(doc)
        }
        vectors.removeDoc(id)
        vault.delete(doc.vaultPath)
    }

    suspend fun setDocType(id: Long, type: DocType, source: DocTypeSource, confidence: Float) {
        val doc = docs.byId(id) ?: return
        docs.update(
            doc.copy(
                docType = type.name,
                docTypeSource = source.db,
                docTypeConfidence = confidence,
                isUserConfirmed = doc.isUserConfirmed || source == DocTypeSource.USER,
                needsLlm = doc.needsLlm and NEEDS_DOC_TYPE.inv(),
            ),
        )
    }

    suspend fun setExpiry(id: Long, expiry: LocalDate?, source: String) {
        val doc = docs.byId(id) ?: return
        docs.update(doc.copy(expiryOn = expiry?.toString(), expirySource = source, needsLlm = doc.needsLlm and NEEDS_EXPIRY.inv()))
    }

    suspend fun resolveDuplicate(id: Long, keepBoth: Boolean) {
        val doc = docs.byId(id) ?: return
        val older = doc.dupOfDocId
        docs.update(doc.copy(dupOfDocId = null))
        if (!keepBoth && older != null) delete(older)
    }

    suspend fun summaries(): List<DocSummary> = docs.all().map { it.toSummary() }

    fun ftsSearch(query: String, limit: Int) = fts.search(query, limit)
    suspend fun vectorSearch(query: FloatArray, limit: Int) = vectors.search(query, limit)
    val vectorCount: Int get() = vectors.size
    val lastVectorSearchMicros: Long get() = vectors.lastSearchMicros
    val ftsModule: FtsIndex.Module get() = fts.module

    fun readOriginal(doc: DocumentEntity): ByteArray = vault.readBytes(doc.vaultPath)

    companion object {
        const val NEEDS_DOC_TYPE = 1
        const val NEEDS_EXPIRY = 2
    }
}

fun DocumentEntity.type(): DocType = DocType.parse(docType) ?: DocType.OTHER_DOCUMENT

fun DocumentEntity.toSummary(): DocSummary = DocSummary(
    id = id,
    type = type(),
    issuedOn = issuedOn?.let(LocalDate::parse),
    capturedOn = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault()).toLocalDate(),
    expiryOn = expiryOn?.let(LocalDate::parse),
    owner = ownerName,
    confidence = docTypeConfidence,
    userConfirmed = isUserConfirmed,
)
