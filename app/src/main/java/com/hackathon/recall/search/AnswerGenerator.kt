package com.hackathon.recall.search

import android.content.Context
import android.util.Log
import com.hackathon.recall.R
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.displayTitle
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.inLang
import com.hackathon.recall.ml.GenieXQwen
import com.hackathon.recall.ml.Metrics
import com.hackathon.recall.model.Lang
import kotlinx.coroutines.withTimeout

data class Answer(
    val text: String,
    val citedDocIds: List<Long>,
    /** "llm" or "template". */
    val source: String,
    /** IDs the model cited that were not in its context; dropped (brief §6.3). */
    val droppedCitations: List<Long> = emptyList(),
)

/**
 * Grounded answers (brief §6.3): Qwen sees only the OCR text of the top 3 documents and must cite
 * their IDs; any cited ID that wasn't in the context is dropped. With no LLM, or no documents, the
 * reply is a template in the user's language. Never invents a document.
 */
class AnswerGenerator(private val context: Context, private val llm: GenieXQwen) {
    suspend fun answer(question: String, lang: Lang, docs: List<DocumentEntity>, useLlm: Boolean = true): Answer {
        val strings = context.inLang(lang)
        if (docs.isEmpty()) return Answer(strings.getString(R.string.answer_nothing_found), emptyList(), "template")
        val top = docs.take(3)
        if (useLlm && llm.ensureLoaded()) {
            val start = System.nanoTime()
            try {
                val allowed = top.map { it.id }.toSet()
                val user = buildString {
                    appendLine("Documents:")
                    for (d in top) {
                        appendLine("[id ${d.id}] ${d.effectiveType().labelEn} ${d.displayTitle()}".trim())
                        appendLine(d.ocrText.take(CONTEXT_CHARS))
                        appendLine()
                    }
                    append("Question: ").append(question)
                }
                val answer = withTimeout(TIMEOUT_MS) {
                    llm.askJson(Prompts.answerSystem(lang), user, MAX_ANSWER_TOKENS, { LlmJson.decode<AnswerJson>(it) }) { a ->
                        val text = a.answer?.trim().orEmpty()
                        require(text.isNotEmpty()) { "\"answer\" is empty" }
                        Answer(text, a.citedDocIds.filter { it in allowed }.distinct(), "llm", a.citedDocIds.filter { it !in allowed })
                    }
                }
                Metrics.record("query.answer.llm", (System.nanoTime() - start) / 1e6)
                return answer
            } catch (e: Exception) {
                Log.w(TAG, "LLM answer failed: ${e.javaClass.simpleName}")
            }
        }
        val types = docs.map { it.effectiveType() }.distinct().joinToString(", ") { strings.docTypeName(it) }
        return Answer(strings.resources.getQuantityString(R.plurals.answer_found_docs, docs.size, docs.size, types), top.map { it.id }, "template")
    }

    /**
     * The user rejected the previous reply and nothing else matches: the document they mean is not
     * confused for another one, there simply isn't a second one saved.
     */
    fun excludedAll(lang: Lang, types: List<com.hackathon.recall.model.DocType>): Answer {
        val strings = context.inLang(lang)
        val text = types.singleOrNull()?.let { strings.getString(R.string.answer_no_other_typed, strings.docTypeName(it)) }
            ?: strings.getString(R.string.answer_no_other)
        return Answer(text, emptyList(), "template")
    }

    private companion object {
        const val TAG = "AnswerGenerator"
        const val CONTEXT_CHARS = 1200
        /** One short sentence plus the JSON wrapper; decoding runs ~24 tokens/s, so every token is ~40 ms. */
        const val MAX_ANSWER_TOKENS = 120
        const val TIMEOUT_MS = 20_000L
    }
}
