package com.hackathon.recall.search

import android.content.Context
import android.util.Log
import com.hackathon.recall.R
import com.hackathon.recall.i18n.inLang
import com.hackathon.recall.ml.GenieXQwen
import com.hackathon.recall.ml.Metrics
import com.hackathon.recall.model.Lang
import kotlinx.coroutines.withTimeout

/**
 * Replies to small talk ([IntentKind.CHAT]) in words, never with a document search. Qwen writes the
 * reply and streams it through [onToken]; with no model, or if generation fails, a template in the
 * user's language is used, chosen by the kind of small talk the rule lexicon recognises.
 */
class ChatResponder(private val context: Context, private val llm: GenieXQwen, private val rules: RuleFallbackParser) {
    data class Reply(val text: String, /** "llm" or "template". */ val source: String)

    suspend fun reply(message: String, lang: Lang, documentCount: Int, useLlm: Boolean = true, onToken: ((String) -> Unit)? = null): Reply {
        if (useLlm && llm.ensureLoaded()) {
            val start = System.nanoTime()
            try {
                val text = withTimeout(TIMEOUT_MS) {
                    llm.complete(Prompts.chatSystem(lang, documentCount), message.ifBlank { "(empty message)" }, MAX_TOKENS, onToken).text
                }.let(::tidy)
                if (text.isNotEmpty()) {
                    Metrics.record("query.chat.llm", (System.nanoTime() - start) / 1e6)
                    return Reply(text, "llm")
                }
            } catch (e: Exception) {
                Log.w(TAG, "LLM chat reply failed, using a template: ${e.javaClass.simpleName}")
            }
        }
        val strings = context.inLang(lang)
        val res = when (rules.chatKind(message)) {
            "greeting" -> R.string.chat_reply_greeting
            "thanks" -> R.string.chat_reply_thanks
            "help" -> R.string.chat_reply_help
            "bye" -> R.string.chat_reply_bye
            "privacy" -> R.string.chat_reply_privacy
            else -> R.string.chat_reply_offtopic
        }
        return Reply(strings.getString(res), "template")
    }

    /** Small models sometimes wrap a reply in quotes or prefix a speaker name; strip both. */
    private fun tidy(raw: String): String =
        raw.trim().removePrefix("Recall:").trim().removeSurrounding("\"").trim()

    private companion object {
        const val TAG = "ChatResponder"
        const val MAX_TOKENS = 90
        const val TIMEOUT_MS = 15_000L
    }
}
