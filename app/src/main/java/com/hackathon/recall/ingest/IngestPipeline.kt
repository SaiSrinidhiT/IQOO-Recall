package com.hackathon.recall.ingest

import android.graphics.Bitmap
import android.util.Log
import com.hackathon.recall.actions.MaskPlanner
import com.hackathon.recall.actions.ReminderScheduler
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.EntityRow
import com.hackathon.recall.data.NewDocument
import com.hackathon.recall.data.PageRow
import com.hackathon.recall.extract.DateParser
import com.hackathon.recall.extract.DigitNormalizer
import com.hackathon.recall.extract.DocClassifier
import com.hackathon.recall.extract.EntityExtractor
import com.hackathon.recall.extract.ExpiryDecision
import com.hackathon.recall.extract.ExpiryPicker
import com.hackathon.recall.extract.IssueDatePicker
import com.hackathon.recall.extract.OwnerNameExtractor
import com.hackathon.recall.extract.TitleBuilder
import com.hackathon.recall.ml.Metrics
import com.hackathon.recall.ml.ModelManager
import com.hackathon.recall.ml.NomicEmbedder
import com.hackathon.recall.ml.TextChunker
import com.hackathon.recall.ml.VectorMath
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.EntityKind
import com.hackathon.recall.model.SourceKind
import com.hackathon.recall.ocr.OcrEngine
import com.hackathon.recall.ocr.OcrResult
import com.hackathon.recall.ocr.ScriptDetector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.LocalDate

/**
 * The single ingest path for gallery images, camera scans and PDFs (brief F1-F3, F8):
 * SHA-256 dedup → decode → SigLIP2 gatekeeper → OCR → entities → doc type → dates → near-duplicate
 * check → chunk + Nomic embed → one DB transaction + encrypted vault copy → reminders.
 */
class IngestPipeline(
    private val models: ModelManager,
    private val ocr: OcrEngine,
    private val repo: DocumentRepository,
    private val reminders: ReminderScheduler,
) {
    data class Source(
        val bytes: ByteArray,
        val mime: String,
        val kind: SourceKind,
        val uri: String?,
        val capturedAt: Long,
        /** MediaStore RELATIVE_PATH, used for the screenshot / Downloads / WhatsApp Documents leniency. */
        val relativePath: String? = null,
        /** The user tapped "Mark as document". */
        val forceDocument: Boolean = false,
        /** Set when the camera was opened from a checklist's "Scan missing X". */
        val expectedType: DocType? = null,
    )

    sealed interface Outcome {
        data class Saved(val docId: Long, val type: DocType, val duplicateOf: DocumentEntity?) : Outcome
        data class ExactDuplicate(val existing: DocumentEntity) : Outcome
        data class NotADocument(val label: String, val margin: Float) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val json = Json { encodeDefaults = true }

    suspend fun ingest(src: Source, today: LocalDate = LocalDate.now()): Outcome {
        val start = System.nanoTime()
        try {
            val sha = sha256(src.bytes)
            repo.bySha(sha)?.let { return Outcome.ExactDuplicate(it) }

            val gated = src.kind == SourceKind.GALLERY && !src.forceDocument
            val isPdf = src.mime == "application/pdf"

            // Gatekeeper (gallery only: camera scans and picked PDFs are documents by the user's
            // choice): embeds a small (GATE_DIM) bitmap instead of the full page. SigLIP2's own input
            // is 224px regardless of what we hand it, so decoding at full resolution up front, as
            // before, was wasted work on every photo — and for the ~90% the gate rejects, it was the
            // *only* work needed, so most photos now skip the expensive full decode entirely.
            val siglip = models.siglip
            val gate = if (isPdf) null else run {
                val gateBitmap = ImageLoader.decode(src.bytes, GATE_DIM)
                val vec = siglip?.embed(gateBitmap)
                val verdict = vec?.let { models.gatekeeper?.judge(it) }
                gateBitmap.recycle()
                vec to verdict
            }
            val imageVector = gate?.first
            val verdict = gate?.second
            if (gated && verdict != null && !verdict.isDocument) {
                val lenient = isScreenshotOrDocsFolder(src.relativePath) && verdict.uncertain
                if (!lenient) return Outcome.NotADocument(verdict.topLabel, verdict.margin)
            }

            val pages: List<Bitmap> = if (isPdf) ImageLoader.renderPdf(src.bytes) else listOf(ImageLoader.decode(src.bytes))
            if (pages.isEmpty()) return Outcome.Failed("no pages")
            try {

            val ocrPages: List<OcrResult> = pages.map { ocr.recognize(it) }
            val text = ocrPages.joinToString("\n") { it.text }
            // Without SigLIP2, gate on text density instead (docs/ARCHITECTURE.md §6).
            if (gated && verdict == null && ScriptDetector.usefulChars(text) < OcrEngine.MIN_USEFUL_CHARS) {
                return Outcome.NotADocument("ocr-fallback: little text", 0f)
            }

            val normalized = DigitNormalizer.normalize(text)
            val entities = EntityExtractor.extract(normalized)
            val cls = DocClassifier.classify(text, entities, verdict?.docLabelProbs.orEmpty(), src.expectedType)

            val lines = text.lines()
            val dates = DateParser.parse(lines, today)
            var needsLlm = if (cls.needsLlm) DocumentRepository.NEEDS_DOC_TYPE else 0
            val (expiry, expirySource) = when (val d = ExpiryPicker.pick(lines, dates, cls.type)) {
                is ExpiryDecision.Picked -> d.date to d.source
                is ExpiryDecision.NeedsChoice -> {
                    needsLlm = needsLlm or DocumentRepository.NEEDS_EXPIRY
                    ExpiryPicker.fallback(d, today)?.let { it to "rule" } ?: (null to null)
                }
                ExpiryDecision.None -> null to null
            }
            val issued = IssueDatePicker.pick(lines, dates, cls.type, expiry, today)
            val owner = OwnerNameExtractor.extract(lines, cls.type)
            val title = TitleBuilder.build(cls.type, issued, expiry, owner, entities)
            val duplicateOf = imageVector?.let { findNearDuplicate(it, text) }

            // Aadhaar boxes per page, normalized, stored with the entity (the masker re-checks at export).
            val aadhaarBoxes = ocrPages.mapIndexed { i, p ->
                i to MaskPlanner.planAadhaar(p.lines.flatMap { it.words }).rects.map { it.normalized(p.width, p.height) }
            }.filter { it.second.isNotEmpty() }
            val entityRows = entities.map { e ->
                val boxes = if (e.kind == EntityKind.AADHAAR) aadhaarBoxes.firstOrNull() else null
                EntityRow(
                    docId = 0, kind = e.kind.db, value = e.value, valueMasked = e.masked,
                    bboxJson = boxes?.second?.let { json.encodeToString(it) }, page = boxes?.first ?: pageOf(e.start, ocrPages),
                )
            }

            val chunks = embedChunks(cls.type, TitleBuilder.factsLine(cls.type, issued, expiry, owner, entities), text)
            val vaultName = "$sha.enc"
            val doc = DocumentEntity(
                sourceUri = src.uri,
                sourceKind = src.kind.db,
                sha256 = sha,
                vaultPath = vaultName,
                docType = cls.type.name,
                docTypeConfidence = cls.confidence,
                docTypeSource = cls.source.db,
                titleEn = title,
                ocrText = text,
                scripts = ScriptDetector.scripts(text).joinToString(",") { it.code },
                capturedAt = src.capturedAt,
                issuedOn = issued?.toString(),
                expiryOn = expiry?.toString(),
                expirySource = expirySource,
                ownerName = owner,
                createdAt = System.currentTimeMillis(),
                mimeType = src.mime,
                pageCount = pages.size,
                dupOfDocId = duplicateOf?.id,
                needsLlm = needsLlm,
                ocrEngine = ocrPages.map { it.engine }.distinct().joinToString("+"),
            )
            val pageRows = ocrPages.mapIndexed { i, p -> PageRow(0, i, p.width, p.height, json.encodeToString(p.normalized())) }
            val id = repo.save(NewDocument(doc, entityRows, pageRows, chunks, imageVector, src.bytes))
            expiry?.let { reminders.scheduleFor(id, it) }
            Metrics.record("ingest.document", (System.nanoTime() - start) / 1e6)
            return Outcome.Saved(id, cls.type, duplicateOf)
            } finally {
                pages.forEach { it.recycle() }
            }
        } catch (e: ImageLoader.PasswordProtectedPdf) {
            return Outcome.Failed("password-protected PDF")
        } catch (t: Throwable) {
            Log.e(TAG, "ingest failed: ${t.javaClass.simpleName}")
            return Outcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Header chunk (English label + key facts) plus OCR chunks of ≤ 110 WordPiece tokens with a
     * 20-token overlap; each is embedded as "search_document: <label>. <chunk>" (brief §3.2).
     */
    private suspend fun embedChunks(type: DocType, facts: String, text: String): List<Pair<String, FloatArray?>> {
        val tokenizer = models.tokenizer
        val nomic = models.nomic
        val bodies: List<String> = if (tokenizer != null) {
            val chunker = TextChunker(tokenizer)
            listOf(chunker.fit(facts, 120)) + chunker.chunk(text).map { it.text }
        } else {
            listOf(facts) + text.chunked(400)
        }
        return bodies.mapIndexed { i, body ->
            val embedText = NomicEmbedder.DOC_PREFIX + if (i == 0) body else "${type.labelEn}. $body"
            body to nomic?.embed(embedText)
        }
    }

    /** SigLIP2 cosine ≥ τ and OCR word Jaccard ≥ 0.8 against an earlier document (brief §8). */
    private suspend fun findNearDuplicate(imageVector: FloatArray, text: String): DocumentEntity? {
        val cfg = models.config.gatekeeper
        val candidates = repo.imageVectors()
            .map { it.docId to VectorMath.dot(imageVector, VectorMath.fromBytes(it.vector)) }
            .filter { it.second >= cfg.nearDupCosine }
            .sortedByDescending { it.second }
        for ((docId, _) in candidates) {
            val other = repo.byId(docId) ?: continue
            if (VectorMath.jaccard(text, other.ocrText) >= cfg.nearDupJaccard) return other
        }
        return null
    }

    private fun pageOf(offset: Int, pages: List<OcrResult>): Int {
        var acc = 0
        pages.forEachIndexed { i, p ->
            acc += p.text.length + 1
            if (offset < acc) return i
        }
        return 0
    }

    private fun isScreenshotOrDocsFolder(path: String?): Boolean {
        val p = path?.lowercase() ?: return false
        return "screenshot" in p || "download" in p || "whatsapp documents" in p
    }

    companion object {
        private const val TAG = "IngestPipeline"
        /** SigLIP2's own input is 224px; a bit of headroom avoids upscaling artifacts, nothing more. */
        private const val GATE_DIM = 256

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
