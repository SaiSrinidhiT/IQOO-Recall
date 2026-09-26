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
        return Metrics.time("query.parse.rules") { rules.parse(q, today) }
    }

    private companion object {
        const val TAG = "IntentParser"
        const val MAX_QUERY_CHARS = 500
        const val TIMEOUT_MS = 12_000L
    }
}
