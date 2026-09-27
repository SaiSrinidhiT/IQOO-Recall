package com.hackathon.recall.search

import android.util.Log
import com.hackathon.recall.actions.ReminderScheduler
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.isTypeConfident
import com.hackathon.recall.data.type
import com.hackathon.recall.extract.DateParser
import com.hackathon.recall.extract.ExpiryDecision
import com.hackathon.recall.extract.ExpiryPicker
import com.hackathon.recall.ml.GenieXQwen
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.DocTypeSource
import java.time.LocalDate

/**
 * Finishes the LLM steps ingest deferred (brief §5 step 3, §4 expiry pick) and double-checks every
 * unsure document type: Qwen reads the OCR text and picks a type. Agreement with the rules/image
 * guess confirms it ([AGREED], above the 80% bar, so it joins its category); disagreement keeps it
 * unsure ([DISAGREED], filed under Other, with Qwen's pick offered on the document screen). Expiry is
 * picked as a candidate index, never a free-form date. Started after unlock, while Qwen is loaded.
 */
class LlmEnricher(private val repo: DocumentRepository, private val llm: GenieXQwen, private val reminders: ReminderScheduler) {
    /**
     * [recheckConfirmed]: also re-run documents an older classification prompt confirmed into a category
     * (see [Prompts.DOC_TYPE_PROMPT_VERSION]). Ones Qwen disagreed with stay as they are: re-asking would
     * compare Qwen with itself, not with an independent reading.
     */
    suspend fun runPending(today: LocalDate = LocalDate.now(), recheckConfirmed: Boolean = false): Int {
        if (!llm.ensureLoaded()) return 0
        var n = 0
        fun confirmedByOldPrompt(d: DocumentEntity) = recheckConfirmed && d.docTypeSource == DocTypeSource.LLM.db &&
            d.isTypeConfident() && d.type() != DocType.OTHER_DOCUMENT
        val pending = repo.all().filter { d ->
            !d.isUserConfirmed && (d.needsLlm != 0 || (d.docTypeSource != DocTypeSource.LLM.db && !d.isTypeConfident()) || confirmedByOldPrompt(d))
        }
        for (doc in pending) {
            try {
                val unsure = (doc.docTypeSource != DocTypeSource.LLM.db && !doc.isTypeConfident()) || confirmedByOldPrompt(doc)
                if (doc.needsLlm and DocumentRepository.NEEDS_DOC_TYPE != 0 || unsure) {
                    val verdict = llm.askJson(Prompts.docTypeSystem, "OCR text:\n${doc.ocrText.take(OCR_CHARS)}", 24,
                        { LlmJson.decode<DocTypeJson>(it) }) { j -> DocType.parse(j.docType) ?: throw IllegalArgumentException("doc_type must be one of the listed types") }
                    val guess = doc.type()
                    val (type, confidence) = when {
                        verdict == guess -> guess to AGREED
                        // Not any listed document: nothing to file it under, and nothing to doubt.
                        verdict == DocType.OTHER_DOCUMENT -> DocType.OTHER_DOCUMENT to AGREED
                        else -> verdict to DISAGREED
                    }
                    repo.setDocType(doc.id, type, DocTypeSource.LLM, confidence)
                    // Types and scores only: document text never goes to the log.
                    Log.i(TAG, "doc ${doc.id}: rules ${guess.name} ${(doc.docTypeConfidence * 100).toInt()}%, qwen ${verdict.name} -> ${type.name} ${(confidence * 100).toInt()}%")
                }
                if (doc.needsLlm and DocumentRepository.NEEDS_EXPIRY != 0) {
                    val lines = doc.ocrText.lines()
                    val decision = ExpiryPicker.pick(lines, DateParser.parse(lines, today), repo.byId(doc.id)?.type() ?: doc.type())
                    if (decision is ExpiryDecision.NeedsChoice) {
                        val list = decision.candidates.mapIndexed { i, c -> "$i: ${c.date} — context: ${c.context.take(160)}" }.joinToString("\n")
                        val index = llm.askJson(Prompts.expirySystem, "Document type: ${doc.type().labelEn}\nCandidates:\n$list", 16,
                            { LlmJson.decode<ExpiryJson>(it) }) { j ->
                            j.index?.also { require(it in decision.candidates.indices) { "index must be between 0 and ${decision.candidates.lastIndex}" } }
                        }
                        val date = index?.let { decision.candidates[it].date }
                        repo.setExpiry(doc.id, date, "llm")
                        if (date != null) reminders.scheduleFor(doc.id, date) else reminders.cancelFor(doc.id)
                    } else {
                        repo.setExpiry(doc.id, doc.expiryOn?.let(LocalDate::parse), doc.expirySource ?: "rule")
                    }
                }
                n++
            } catch (e: Exception) {
                Log.w(TAG, "enrich ${doc.id} failed: ${e.javaClass.simpleName}")
            }
        }
        return n
    }

    private companion object {
        const val TAG = "LlmEnricher"
        const val OCR_CHARS = 900
        /** Two independent readings (rules/image and Qwen) name the same type. */
        const val AGREED = 0.9f
        /** They disagree: below the 80% bar, so the document waits under Other for the user. */
        const val DISAGREED = 0.6f
    }
}
