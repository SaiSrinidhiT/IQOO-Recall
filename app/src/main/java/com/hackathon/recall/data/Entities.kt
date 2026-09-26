package com.hackathon.recall.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Brief §5 `documents`, plus the few extra columns the pipeline needs (marked below). */
@Entity(
    tableName = "documents",
    indices = [Index(value = ["sha256"], unique = true), Index("doc_type"), Index("expiry_on")],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_uri") val sourceUri: String?,
    @ColumnInfo(name = "source_kind") val sourceKind: String,
    val sha256: String,
    @ColumnInfo(name = "vault_path") val vaultPath: String,
    @ColumnInfo(name = "doc_type") val docType: String,
    @ColumnInfo(name = "doc_type_confidence") val docTypeConfidence: Float,
    @ColumnInfo(name = "doc_type_source") val docTypeSource: String,
    @ColumnInfo(name = "title_en") val titleEn: String,
    @ColumnInfo(name = "ocr_text") val ocrText: String,
    val scripts: String,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
    @ColumnInfo(name = "issued_on") val issuedOn: String?,
    @ColumnInfo(name = "expiry_on") val expiryOn: String?,
    @ColumnInfo(name = "expiry_source") val expirySource: String?,
    @ColumnInfo(name = "owner_name") val ownerName: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "is_user_confirmed") val isUserConfirmed: Boolean = false,
    // Extra: original MIME type and page count, for rendering and packs.
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "page_count") val pageCount: Int = 1,
    // Extra: near-duplicate of an earlier document, pending the user's Keep both / Replace choice.
    @ColumnInfo(name = "dup_of_doc_id") val dupOfDocId: Long? = null,
    // Extra: bit flags for work the LLM should finish (1 = doc type, 2 = expiry pick).
    @ColumnInfo(name = "needs_llm") val needsLlm: Int = 0,
    // Extra: the source image was deleted from the gallery; the vault copy remains.
    @ColumnInfo(name = "source_missing") val sourceMissing: Boolean = false,
    @ColumnInfo(name = "ocr_engine") val ocrEngine: String = "",
)

@Entity(
    tableName = "entities",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["doc_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("doc_id"), Index("kind")],
)
data class EntityRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: Long,
    val kind: String,
    val value: String,
    @ColumnInfo(name = "value_masked") val valueMasked: String,
    /** JSON list of normalized [0,1] boxes on [page] (for Aadhaar: the first 8 digits). */
    @ColumnInfo(name = "bbox_json") val bboxJson: String?,
    val page: Int,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["doc_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["doc_id", "chunk_ix"], unique = true)],
)
data class ChunkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: Long,
    @ColumnInfo(name = "chunk_ix") val chunkIx: Int,
    val text: String,
    /** float32 little-endian Nomic vector; null until the embedder is available (then backfilled). */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray?,
)

@Entity(
    tableName = "image_vectors",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["doc_id"], onDelete = ForeignKey.CASCADE)],
)
data class ImageVectorRow(
    @PrimaryKey @ColumnInfo(name = "doc_id") val docId: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
)

/** Per-page OCR layout (lines and word boxes, normalized to the page size), needed for masking. */
@Entity(
    tableName = "pages",
    primaryKeys = ["doc_id", "page"],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["doc_id"], onDelete = ForeignKey.CASCADE)],
)
data class PageRow(
    @ColumnInfo(name = "doc_id") val docId: Long,
    val page: Int,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "layout_json") val layoutJson: String,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["doc_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["doc_id", "offset_days"], unique = true)],
)
data class ReminderRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: Long,
    @ColumnInfo(name = "fire_at") val fireAt: Long,
    /** 30, 7 or 1; 0 is the debug "test in one minute" reminder. */
    @ColumnInfo(name = "offset_days") val offsetDays: Int,
    /** scheduled | fired | skipped_past | cancelled */
    val state: String,
)

@Entity(tableName = "index_state", indices = [Index(value = ["uri"], unique = true), Index("status")])
data class IndexStateRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    /** MediaStore GENERATION_MODIFIED at scan time (the incremental-scan watermark). */
    val generation: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    /** MediaStore DATE_TAKEN (or DATE_ADDED) in epoch millis: when the photo was captured. */
    @ColumnInfo(name = "taken_at") val takenAt: Long = 0,
    /** pending | done | failed | skipped_non_doc */
    val status: String,
    val error: String? = null,
    @ColumnInfo(name = "doc_id") val docId: Long? = null,
    @ColumnInfo(name = "gate_label") val gateLabel: String? = null,
    @ColumnInfo(name = "gate_margin") val gateMargin: Float? = null,
    val attempts: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Small key/value store: watermarks, the chosen FTS module, calibration values. */
@Entity(tableName = "kv")
data class KvRow(@PrimaryKey val key: String, val value: String)
