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
    suspend fun complete(system: String, user: String, maxTokens: Int = 256): LlmResult
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
        val candidates = buildList {
            if (!usingFallback && files.qwen.exists()) add(files.qwen)
            if (files.qwenFallback.exists()) add(files.qwenFallback)
        }
        if (candidates.isEmpty()) {
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

    private suspend fun load(dir: File): LlmWrapper {
        GenieXSdk.getInstance().init(context)
        val key = "local/${dir.name}"
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
                    config = ModelConfig(),
                    runtime_id = paths.runtime_id,
                    compute_unit = null,
                ),
            )
            .build()
            .getOrThrow()
    }

    override suspend fun complete(system: String, user: String, maxTokens: Int): LlmResult = mutex.withLock {
        if (!loadLocked()) throw IllegalStateException("LLM unavailable: ${_state.value}")
        val wrapper = llm!!
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
                is LlmStreamResult.Token -> text.append(r.text)
                is LlmStreamResult.Completed -> {
                    ttft = r.profile.ttftMs
                    tps = r.profile.decodingSpeed
                    tokens = r.profile.generatedTokens
                }
                is LlmStreamResult.Error -> failure = r.throwable
            }
        }
        failure?.let { throw it }
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
