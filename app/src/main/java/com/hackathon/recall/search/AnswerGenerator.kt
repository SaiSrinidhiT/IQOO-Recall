package com.hackathon.recall.search

import android.content.Context
import android.util.Log
import com.hackathon.recall.R
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.displayTitle
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.extract.EntityExtractor
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.inLang
import com.hackathon.recall.ml.GenieXQwen
import com.hackathon.recall.ml.Metrics
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.Lang
import java.time.LocalDate
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
    suspend fun answer(question: String, lang: Lang, docs: List<DocumentEntity>, useLlm: Boolean = true, today: LocalDate = LocalDate.now()): Answer {
        val strings = context.inLang(lang)
        if (docs.isEmpty()) return Answer(strings.getString(R.string.answer_nothing_found), emptyList(), "template")
        val top = docs.take(3)
        if (useLlm && llm.ensureLoaded()) {
            val start = System.nanoTime()
            try {
                val allowed = top.map { it.id }.toSet()
                // Each document is fenced in tags: its OCR text is data from the user's files, and a scanned
                // page saying "ignore your instructions" must stay text, not become one (see answerSystem).
                val user = buildString {
                    for (d in top) {
                        append("<document id=\"").append(d.id).append("\" type=\"").append(d.effectiveType().labelEn).append('"')
                        d.ownerName?.let { append(" belongs_to=\"").append(it.replace("\"", "")).append('"') }
                        appendLine(">")
                        appendLine(d.displayTitle())
                        appendLine(d.ocrText.take(CONTEXT_CHARS).replace("</document>", ""))
                        appendLine("</document>")
                    }
                    append("Question: ").append(question)
                }
                val answer = withTimeout(TIMEOUT_MS) {
                    llm.askJson(Prompts.answerSystem(lang, today), user, MAX_ANSWER_TOKENS, { LlmJson.decode<AnswerJson>(it) }) { a ->
                        val text = a.answer?.trim().orEmpty()
                        require(text.isNotEmpty()) { "\"answer\" is empty" }
                        // Qwen quotes the OCR text it was shown; an Aadhaar number is masked here as it is
                        // everywhere else in the app (cards, exported PDFs), not printed and saved in full.
                        Answer(EntityExtractor.maskAadhaarIn(text), a.citedDocIds.filter { it in allowed }.distinct(), "llm", a.citedDocIds.filter { it !in allowed })
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
     * The reply line above document cards, the way a person would put it: how many of that type are
     * saved and whose they are ("Salary slip: 12 saved (Sai, Srikar). Showing the latest 6."), which
     * also answers "how many…", "do I have…" and "whose…" without a model call.
     */
    fun summary(lang: Lang, types: List<DocType>, shown: List<DocumentEntity>, total: Int, owners: List<String>, order: SortOrder, action: DocAction?): Answer {
        val strings = context.inLang(lang)
        val type = types.singleOrNull()
        val base = when {
            type == null -> strings.resources.getQuantityString(
                R.plurals.answer_found_docs, shown.size, shown.size, shown.map { it.effectiveType() }.distinct().joinToString(", ") { strings.docTypeName(it) },
            )
            total <= 1 -> strings.getString(R.string.answer_one_doc, strings.docTypeName(type))
            else -> buildString {
                append(
                    if (owners.isEmpty()) strings.getString(R.string.answer_count, strings.docTypeName(type), total)
                    else strings.getString(R.string.answer_count_owners, strings.docTypeName(type), total, owners.joinToString(", ")),
                )
                if (shown.size < total) {
                    append(' ').append(strings.getString(if (order == SortOrder.OLDEST) R.string.answer_showing_oldest else R.string.answer_showing_latest, shown.size))
                }
            }
        }
        val note = action?.let { actionNote(lang, it) }
        return Answer(listOfNotNull(base, note).joinToString(" "), shown.take(3).map { it.id }, "template")
    }

    /** What the app does, or deliberately doesn't do from chat, when the user asks to do something to a document. */
    fun actionNote(lang: Lang, action: DocAction): String {
        val strings = context.inLang(lang)
        return when (action) {
            DocAction.SHARE -> strings.getString(R.string.action_note_share, strings.getString(R.string.chat_share_pdf))
            DocAction.DELETE -> strings.getString(R.string.action_note_delete, strings.getString(R.string.action_delete))
            DocAction.EDIT -> strings.getString(R.string.action_note_edit)
            DocAction.ADVICE -> strings.getString(R.string.action_note_advice)
        }
    }

    /** "How do I get a new passport?" with no passport saved: say both limits plainly. */
    fun adviceWithoutDocument(lang: Lang, types: List<DocType>): Answer {
        val strings = context.inLang(lang)
        val name = types.firstOrNull()?.let { strings.docTypeName(it) } ?: strings.getString(R.string.photo_cat_documents)
        return Answer(strings.getString(R.string.action_note_advice_none, name), emptyList(), "template")
    }

    /** "What documents do I have?": the total and the most common types, optionally leaving some out. */
    fun overview(lang: Lang, total: Int, counts: List<Pair<DocType, Int>>, excluded: List<DocType>): Answer {
        val strings = context.inLang(lang)
        val list = counts.joinToString(", ") { (t, n) -> "${strings.docTypeName(t)} $n" }
        val text = buildString {
            append(strings.getString(R.string.answer_overview, total, list))
            if (excluded.isNotEmpty()) append(' ').append(strings.getString(R.string.answer_overview_excluding, excluded.joinToString(", ") { strings.docTypeName(it) }))
        }
        return Answer(text, emptyList(), "template")
    }

    /** A message that names no document and matched nothing: ask for one, instead of offering to scan "it". */
    fun unclear(lang: Lang): Answer = Answer(context.inLang(lang).getString(R.string.answer_unclear), emptyList(), "template")

    /**
     * The user rejected the previous reply and nothing else matches: the document they mean is not
     * confused for another one, there simply isn't a second one saved.
     */
    fun excludedAll(lang: Lang, types: List<DocType>): Answer {
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
