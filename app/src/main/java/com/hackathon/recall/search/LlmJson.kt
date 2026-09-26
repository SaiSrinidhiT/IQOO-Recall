package com.hackathon.recall.search

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Pulls the first JSON object out of LLM output and decodes it; errors carry a message for the retry prompt. */
object LlmJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** First balanced `{...}` in [text], respecting string literals; null if there is none. */
    fun extractObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    inline fun <reified T> decode(text: String): T {
        val obj = extractObject(text) ?: throw SerializationException("no JSON object in the reply")
        return json.decodeFromString<T>(obj)
    }
}
