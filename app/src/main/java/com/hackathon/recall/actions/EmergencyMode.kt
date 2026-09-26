package com.hackathon.recall.actions

import android.content.Context
import android.speech.tts.TextToSpeech
import com.hackathon.recall.R
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.inLang
import com.hackathon.recall.model.EntityKind
import com.hackathon.recall.model.Lang
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate
import java.util.Locale

/**
 * Emergency mode (brief F9, docs/DECISIONS.md D-001): the emergency_health pack, read-only, Aadhaar
 * always masked, with a spoken summary. It may open without the biometric gate; nothing else can.
 */
class EmergencyMode(private val context: Context, private val repo: DocumentRepository, private val catalog: TemplateCatalog) {
    suspend fun pack(today: LocalDate = LocalDate.now()): ChecklistResult =
        ChecklistEngine.evaluate(catalog.byId("emergency_health")!!, repo.summaries(), today)

    /** One sentence per found item; IDs are spoken masked (last 4 digits only). */
    suspend fun summary(result: ChecklistResult, lang: Lang): String {
        val s = context.inLang(lang)
        val parts = ArrayList<String>()
        for (item in result.items) {
            val name = s.docTypeName(item.item.docType)
            if (item.found.isEmpty()) {
                parts += s.getString(R.string.emergency_missing_item, name)
                continue
            }
            for (d in item.found) {
                val doc = repo.byId(d.id) ?: continue
                val masked = repo.entities(d.id).firstOrNull { it.kind == EntityKind.AADHAAR.db || it.kind == EntityKind.ABHA.db || it.kind == EntityKind.POLICY_NO.db }
                    ?.valueMasked
                parts += when {
                    doc.expiryOn != null -> s.getString(R.string.emergency_item_valid_till, name, doc.expiryOn)
                    masked != null -> s.getString(R.string.emergency_item_with_id, name, masked.takeLast(4))
                    else -> s.getString(R.string.emergency_item, name, doc.titleEn)
                }
            }
        }
        return parts.joinToString(" ")
    }
}

/**
 * TextToSpeech in the user's language (brief §4). If the te-IN or hi-IN voice is not installed it
 * falls back to en-IN and reports that, so the UI can tell the user.
 */
class Speaker(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    /** Speaks [text]; returns the language actually used, or null if TTS is unavailable. */
    suspend fun speak(text: String, lang: Lang): Lang? {
        if (!ready.await()) return null
        val used = if (isAvailable(lang)) lang else Lang.EN
        tts.language = Locale.forLanguageTag(used.tag)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "recall-emergency")
        return used
    }

    suspend fun isAvailable(lang: Lang): Boolean {
        if (!ready.await()) return false
        return tts.isLanguageAvailable(Locale.forLanguageTag(lang.tag)) >= TextToSpeech.LANG_AVAILABLE
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() = tts.shutdown()
}
