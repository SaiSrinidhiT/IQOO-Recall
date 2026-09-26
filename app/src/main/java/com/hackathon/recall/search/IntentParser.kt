package com.hackathon.recall.search

import android.util.Log
import com.hackathon.recall.ml.GenieXQwen
import com.hackathon.recall.ml.Metrics
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

/** Intent parse (brief §6.1): Qwen with JSON validation and one retry, else [RuleFallbackParser]. */
class IntentParser(private val llm: GenieXQwen, private val rules: RuleFallbackParser) {
    suspend fun parse(query: String, today: LocalDate = LocalDate.now(), useLlm: Boolean = true): QueryIntent {
        val q = query.trim().take(MAX_QUERY_CHARS)
        val detected = rules.detectLanguage(q)
        // Plain small talk ("hi", "thanks", "what can you do") needs no routing call: going straight to
        // the chat reply saves a whole Qwen generation. Longer messages still get the model's judgement.
        val ruled = Metrics.time("query.parse.rules") { rules.parse(q, today) }
        if (ruled.kind == IntentKind.CHAT && q.split(Regex("\\s+")).size <= QUICK_CHAT_WORDS) return ruled
        if (useLlm && llm.ensureLoaded()) {
            val start = System.nanoTime()
            try {
                val intent = withTimeout(TIMEOUT_MS) {
                    llm.askJson(Prompts.intentSystem(today), q, 200, { LlmJson.decode<IntentJson>(it) }) { IntentValidator.validate(it, q, detected) }
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

    private companion object {
        const val TAG = "IntentParser"
        const val QUICK_CHAT_WORDS = 6
        const val MAX_QUERY_CHARS = 500
        const val TIMEOUT_MS = 12_000L
    }
}
