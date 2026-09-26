package com.hackathon.recall.search

import android.util.Log
import com.hackathon.recall.actions.ReminderScheduler
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.type
import com.hackathon.recall.extract.DateParser
import com.hackathon.recall.extract.ExpiryDecision
import com.hackathon.recall.extract.ExpiryPicker
import com.hackathon.recall.ml.GenieXQwen
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.DocTypeSource
import java.time.LocalDate

/**
 * Finishes the LLM steps ingest deferred (brief §5 step 3, §4 expiry pick): doc type from the enum
 * given the first 600 OCR characters, and the expiry as a candidate index, never a free-form date.
 * Runs only while Qwen is loaded (foreground), so background indexing never loads a 4B model.
 */
class LlmEnricher(private val repo: DocumentRepository, private val llm: GenieXQwen, private val reminders: ReminderScheduler) {
    suspend fun runPending(today: LocalDate = LocalDate.now()): Int {
        if (!llm.ensureLoaded()) return 0
        var n = 0
        for (doc in repo.all().filter { it.needsLlm != 0 && !it.isUserConfirmed }) {
            try {
                if (doc.needsLlm and DocumentRepository.NEEDS_DOC_TYPE != 0) {
                    val type = llm.askJson(Prompts.docTypeSystem, "OCR text (first 600 characters):\n${doc.ocrText.take(600)}", 24,
                        { LlmJson.decode<DocTypeJson>(it) }) { j -> DocType.parse(j.docType) ?: throw IllegalArgumentException("doc_type must be one of the listed types") }
                    repo.setDocType(doc.id, type, DocTypeSource.LLM, 0.7f)
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
    }
}
