package com.hackathon.recall

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.hackathon.recall.actions.AadhaarMasker
import com.hackathon.recall.actions.PackBuilder
import com.hackathon.recall.actions.ReminderScheduler
import com.hackathon.recall.actions.TemplateCatalog
import com.hackathon.recall.data.DocumentRepository
import com.hackathon.recall.data.FtsIndex
import com.hackathon.recall.data.KeyManager
import com.hackathon.recall.data.RecallDb
import com.hackathon.recall.data.VaultFileStore
import com.hackathon.recall.data.VectorIndex
import com.hackathon.recall.ingest.GalleryObserver
import com.hackathon.recall.ingest.IndexWorker
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.ingest.MediaStoreScanner
import com.hackathon.recall.ml.ModelFiles
import com.hackathon.recall.ml.ModelManager
import com.hackathon.recall.model.DocType
import com.hackathon.recall.ocr.OcrEngine
import com.hackathon.recall.search.AnswerGenerator
import com.hackathon.recall.search.ChatResponder
import com.hackathon.recall.search.HybridSearch
import com.hackathon.recall.search.IntentParser
import com.hackathon.recall.search.Lexicon
import com.hackathon.recall.search.LlmEnricher
import com.hackathon.recall.search.QueryEngine
import com.hackathon.recall.search.RuleFallbackParser
import com.hackathon.recall.search.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Manual dependency wiring. Heavy pieces (database, keys, models) initialize off the main thread in [start]. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ready = MutableStateFlow(false)
    /** True once the encrypted database and vault are open. Screens wait for this. */
    val ready: StateFlow<Boolean> = _ready

    val keys by lazy { KeyManager(app) }
    val modelFiles by lazy { ModelFiles(app) }
    val models by lazy { ModelManager(app, modelFiles, scope) }
    private val fts = FtsIndex { database.openHelper.writableDatabase }
    val database: RecallDb by lazy { RecallDb.open(app, keys.databasePassphrase(), fts) }
    val vault by lazy { VaultFileStore(app, keys.streamingAead()) }
    val repository by lazy { DocumentRepository(database, fts, VectorIndex(database.chunks()), vault) }
    val reminders by lazy { ReminderScheduler(app) { database } }
    val ocr by lazy { OcrEngine(app) }
    val pipeline by lazy { IngestPipeline(models, ocr, repository, reminders) }
    val scanner by lazy { MediaStoreScanner(app) { database } }
    val templates by lazy { TemplateCatalog.parse(asset("templates.json")) }
    private val lexicon by lazy { Lexicon.parse(asset("rule_lexicon.json")) }
    val ruleParser by lazy { RuleFallbackParser(lexicon) }
    val queryEngine by lazy {
        QueryEngine(
            IntentParser(models.llm, ruleParser),
            HybridSearch(repository, models),
            AnswerGenerator(app, models.llm),
            ChatResponder(app, models.llm, ruleParser),
            repository,
        )
    }
    val enricher by lazy { LlmEnricher(repository, models.llm, reminders) }
    val packBuilder by lazy { PackBuilder(app, repository, AadhaarMasker(ocr)) }
    val voice by lazy { VoiceInput(app) }
    val session = VaultSession()

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                database.openHelper.writableDatabase // opens SQLCipher and creates the FTS table
                vault
                _ready.value = true
                clearBadOwnerNamesOnce()
            } catch (t: Throwable) {
                Log.e(TAG, "vault failed to open: ${t.javaClass.simpleName}")
            }
            packBuilder.deleteSharedFiles()
            models.warmUp()
            IndexWorker.enqueue(app)
            IndexWorker.enqueueOnNewMedia(app)
        }
        GalleryObserver(app).register()
        ProcessLifecycleOwner.get().lifecycle.addObserver(session)
    }

    private fun asset(name: String): String = app.assets.open(name).use { it.readBytes().decodeToString() }

    /**
     * Runs once per [CLEANUP_KEY] version: re-runs owner extraction over stored OCR text so fixes to
     * OwnerNameExtractor (restaurant names off receipts, towns off Aadhaar address blocks) correct
     * documents already in the vault, with no rescan. Names the user tagged by hand are left alone.
     */
    private suspend fun clearBadOwnerNamesOnce() {
        val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(CLEANUP_KEY, false)) return
        val manual = com.hackathon.recall.ui.People.manuallyTagged(database)
        val changed = repository.reextractOwners(manual)
        if (changed > 0) Log.i(TAG, "re-extracted owner name on $changed document(s)")
        prefs.edit().putBoolean(CLEANUP_KEY, true).apply()
    }

    private companion object {
        const val TAG = "AppContainer"
        const val CLEANUP_KEY = "owner_cleanup_v2"
    }
}

/**
 * UI unlock state (BiometricPrompt gate, docs/DECISIONS.md D-002). The vault relocks when the app has
 * been in the background for [RELOCK_AFTER_MS].
 */
class VaultSession : DefaultLifecycleObserver {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked
    private var backgroundedAt = 0L

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        _unlocked.value = false
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        if (backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt > RELOCK_AFTER_MS) lock()
    }

    private companion object {
        const val RELOCK_AFTER_MS = 60_000L
    }
}
