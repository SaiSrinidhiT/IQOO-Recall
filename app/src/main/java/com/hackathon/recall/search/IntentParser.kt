package com.hackathon.recall.search

import android.util.Log
import com.hackathon.recall.ml.GenieXQwen
import com.hackathon.recall.ml.Metrics
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

/** Intent parse (brief §6.1): Qwen with JSON validation and one retry, else [RuleFallbackParser]. */
class IntentParser(private val llm: GenieXQwen, private val rules: RuleFallbackParser) {
    /** [context] describes the previous reply (see [ChatContext.describe]), so Qwen can resolve "it" and "that one". */
    suspend fun parse(query: String, today: LocalDate = LocalDate.now(), useLlm: Boolean = true, context: String? = null): QueryIntent {
        val q = query.trim().take(MAX_QUERY_CHARS)
        val detected = rules.detectLanguage(q)
        // Rules first: when the lexicon already recognises what is asked for (a document type, a task,
        // "expiring", or plain small talk) the routing JSON Qwen would write is the same, and writing it
        // costs ~2.5 s at ~24 tokens/s. Qwen is kept for messages the rules can't place.
        val ruled = Metrics.time("query.parse.rules") { rules.parse(q, today) }
        if (ruledIsConfident(ruled, q)) return ruled
        if (useLlm && llm.ensureLoaded()) {
            val start = System.nanoTime()
            try {
                val intent = withTimeout(TIMEOUT_MS) {
                    val user = if (context == null) q else "Earlier in this chat:\n$context\n\nNew message: $q"
                    llm.askJson(Prompts.intentSystem(today), user, 200, { LlmJson.decode<IntentJson>(it) }) { IntentValidator.validate(it, q, detected) }
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                Metrics.record("query.parse.llm", ms.toDouble())
                llm.reportParseLatency(ms)
                return intent
            } catch (e: Exception) {
                Log.w(TAG, "LLM intent parse failed, using rules: ${e.javaClass.simpleName}")
            }
        }
        return ruled
    }

    /** True for a short reply that rejects the previous answer ("that's not it", "wrong one"). */
    fun isCorrection(query: String): Boolean = rules.isCorrection(query)

    fun detectLanguage(query: String) = rules.detectLanguage(query)

    fun followUpCue(query: String) = rules.followUpCue(query)

    private fun ruledIsConfident(ruled: QueryIntent, q: String): Boolean = when (ruled.kind) {
        IntentKind.CHAT -> q.split(Regex("\\s+")).size <= QUICK_CHAT_WORDS
        IntentKind.REMINDERS, IntentKind.PACK, IntentKind.PHOTOS -> true
        IntentKind.FIND, IntentKind.QUESTION -> ruled.docTypes.isNotEmpty() || ruled.template != null || ruled.listAll
    }

    private companion object {
        const val TAG = "IntentParser"
        const val QUICK_CHAT_WORDS = 6
        const val MAX_QUERY_CHARS = 500
        const val TIMEOUT_MS = 12_000L
    }
}
