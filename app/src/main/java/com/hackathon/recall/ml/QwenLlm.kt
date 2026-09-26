package com.hackathon.recall.ml

import android.content.Context
import android.util.Log
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LlmState {
    data object NotLoaded : LlmState
    data object Loading : LlmState
    data class Ready(val model: String) : LlmState
    data class Missing(val path: String) : LlmState
    data class Failed(val message: String) : LlmState
}

/** One generation with its measured timings (from GenieX's own profiling). */
data class LlmResult(val text: String, val totalMs: Long, val ttftMs: Double, val decodeTokensPerSec: Double, val generatedTokens: Long)

/** Short structured jobs only (brief §3.3): every caller asks for JSON and validates it. */
interface LlmClient {
    val state: StateFlow<LlmState>
    suspend fun ensureLoaded(): Boolean
    /** [onToken] receives each generated piece as it arrives, for streaming a reply into the UI. */
    suspend fun complete(system: String, user: String, maxTokens: Int = 256, onToken: ((String) -> Unit)? = null): LlmResult
    fun unload()
}

/**
 * Qwen3-4B-Instruct-2507 through the Qualcomm GenieX Android SDK on the NPU (`qairt` runtime). The AI
 * Hub bundle is pushed with adb and imported with HubSource.LOCALFS (no network, no INTERNET
 * permission). Loaded lazily; falls back to Qwen3-1.7B when the 4B bundle fails to load or a parse
 * takes longer than [SLOW_PARSE_MS] (brief §3.3).
 */
class GenieXQwen(private val context: Context, private val files: ModelFiles) : LlmClient {
    private val _state = MutableStateFlow<LlmState>(LlmState.NotLoaded)
    override val state: StateFlow<LlmState> = _state
    private val mutex = Mutex()
    private var llm: LlmWrapper? = null
    private var usingFallback = false

    override suspend fun ensureLoaded(): Boolean = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): Boolean {
        if (llm != null) return true
        // The first load imports the pushed bundle into the app's internal storage, so a model
        // counts as available when either copy exists: the external one can be wiped without it.
        val imported = withContext(Dispatchers.IO) {
            GenieXSdk.getInstance().init(context)
            listOf(files.qwen, files.qwenFallback).filterTo(HashSet()) { ModelManagerWrapper.getPaths(key(it)) != null }
        }
        fun available(dir: File) = dir.exists() || dir in imported
        val candidates = buildList {
            if (!usingFallback && available(files.qwen)) add(files.qwen)
            if (available(files.qwenFallback)) add(files.qwenFallback)
        }
        if (candidates.isEmpty()) {
            // Logged, not silent: without this, chat quietly drops to the rule parser.
            if (_state.value !is LlmState.Missing) Log.w(TAG, "no Qwen bundle pushed or imported; chat uses the rule fallback")
            _state.value = LlmState.Missing(files.qwen.absolutePath)
            return false
        }
        _state.value = LlmState.Loading
        val errors = ArrayList<String>()
        for (dir in candidates) {
            try {
                llm = withContext(Dispatchers.IO) { load(dir) }
                usingFallback = dir == files.qwenFallback
                _state.value = LlmState.Ready(dir.name)
                Log.i(TAG, "loaded ${dir.name}${if (usingFallback) " (fallback)" else ""}")
                return true
            } catch (t: Throwable) {
                errors += "${dir.name}: ${t.message}"
                Log.w(TAG, "load failed for ${dir.name}: ${t.message}")
            }
        }
        _state.value = LlmState.Failed(errors.joinToString("; "))
        return false
    }

    private fun key(dir: File) = "local/${dir.name}"

    private suspend fun load(dir: File): LlmWrapper {
        GenieXSdk.getInstance().init(context)
        val key = key(dir)
        var paths = ModelManagerWrapper.getPaths(key)
        if (paths == null) {
            var error: String? = null
            ModelManagerWrapper.pullFlow(ModelPullInput(model_name = key, hub = HubSource.LOCALFS, local_path = dir.absolutePath))
                .collect { e -> if (e is ModelManagerWrapper.PullEvent.Error) error = "import error ${e.code}: ${e.message}" }
            error?.let { throw IllegalStateException(it) }
            paths = ModelManagerWrapper.getPaths(key) ?: throw IllegalStateException("import finished but the model has no paths")
        }
        return LlmWrapper.builder()
            .llmCreateInput(
                LlmCreateInput(
                    model_name = key,
                    model_path = paths.model_path,
                    tokenizer_path = null,
                    // The qairt plugin rejects any non-zero n_ctx ("--nctx is not supported"); the
                    // context size comes from the bundle's genie_config.json. ModelConfig() defaults it to 2048.
                    config = ModelConfig().apply { nCtx = 0 },
                    runtime_id = paths.runtime_id,
                    compute_unit = null,
                ),
            )
            .build()
            .getOrThrow()
    }

    override suspend fun complete(system: String, user: String, maxTokens: Int, onToken: ((String) -> Unit)?): LlmResult = mutex.withLock {
        if (!loadLocked()) throw IllegalStateException("LLM unavailable: ${_state.value}")
        val wrapper = llm!!
        // The engine keeps one running dialog: without a reset every call is appended to the last,
        // the answer step imitates the routing call before it, and once the 4096-token context fills
        // every generation returns zero tokens. Each call here is a standalone job, so start clean.
        val rc = wrapper.reset()
        if (rc != 0) Log.w(TAG, "reset returned $rc")
        val start = System.nanoTime()
        val messages = arrayOf(ChatMessage("system", system), ChatMessage("user", user))
        val prompt = wrapper.applyChatTemplate(messages, null, false).getOrThrow().formattedText
        val config = GenerationConfig().apply { this.maxTokens = maxTokens }
        val text = StringBuilder()
        var failure: Throwable? = null
        var ttft = 0.0
        var tps = 0.0
        var tokens = 0L
        wrapper.generateStreamFlow(prompt, config).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> {
                    text.append(r.text)
                    onToken?.invoke(r.text)
                }
                is LlmStreamResult.Completed -> {
                    ttft = r.profile.ttftMs
                    tps = r.profile.decodingSpeed
                    tokens = r.profile.generatedTokens
                }
                is LlmStreamResult.Error -> failure = r.throwable
            }
        }
        failure?.let { throw it }
        if (text.isBlank()) Log.w(TAG, "generation returned no text")
        val ms = (System.nanoTime() - start) / 1_000_000
        Metrics.record("qwen.generate", ms.toDouble())
        LlmResult(text.toString(), ms, ttft, tps, tokens)
    }

    /** Called by the intent parser with the parse's wall time; switches to the 1.7B model when too slow. */
    suspend fun reportParseLatency(ms: Long) {
        if (ms <= SLOW_PARSE_MS || usingFallback || !files.qwenFallback.exists()) return
        Log.w(TAG, "parse took $ms ms (> $SLOW_PARSE_MS); switching to ${files.qwenFallback.name}")
        mutex.withLock {
            llm?.close()
            llm = null
            usingFallback = true
            loadLocked()
        }
    }

    override fun unload() {
        llm?.close()
        llm = null
        _state.value = LlmState.NotLoaded
    }

    companion object {
        private const val TAG = "GenieXQwen"
        const val SLOW_PARSE_MS = 6_000L
    }
}
