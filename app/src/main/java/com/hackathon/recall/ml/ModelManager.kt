package com.hackathon.recall.ml

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** assets/model_config.json: values calibrated on the seed set (brief §3.1, §8). */
@Serializable
data class ModelConfigJson(
    val siglip: Siglip = Siglip(),
    val gatekeeper: Gate = Gate(),
    val search: Search = Search(),
    val calibrated: Boolean = false,
) {
    @Serializable
    data class Siglip(@SerialName("input_range") val inputRange: String = "zero_one")

    @Serializable
    data class Gate(
        val margin: Float = 0f,
        @SerialName("uncertain_band") val uncertainBand: Float = 0.02f,
        @SerialName("near_dup_cosine") val nearDupCosine: Float = 0.95f,
        @SerialName("near_dup_jaccard") val nearDupJaccard: Float = 0.8f,
    )

    @Serializable
    data class Search(@SerialName("min_cosine") val minCosine: Float = 0.35f)
}

sealed interface ModelState {
    data object NotLoaded : ModelState
    data object Loading : ModelState
    data class Ready(val backend: Backend, val loadMs: Long, val detail: String) : ModelState
    data class Missing(val path: String) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Loads each model once and keeps it warm (brief §3.4). SigLIP2 and Nomic load in the background at
 * start-up; Qwen loads lazily on first use. When a model is missing or fails, its state says so and
 * callers take the documented fallback instead of faking output.
 */
class ModelManager(private val context: Context, val files: ModelFiles, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }

    val config: ModelConfigJson = runCatching {
        context.assets.open("model_config.json").use { json.decodeFromString(ModelConfigJson.serializer(), it.readBytes().decodeToString()) }
    }.getOrDefault(ModelConfigJson())

    private val _siglipState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    private val _nomicState = MutableStateFlow<ModelState>(ModelState.NotLoaded)
    val siglipState: StateFlow<ModelState> = _siglipState
    val nomicState: StateFlow<ModelState> = _nomicState

    @Volatile var siglip: SiglipVision? = null
        private set

    @Volatile var nomic: NomicEmbedder? = null
        private set

    @Volatile var gatekeeper: Gatekeeper? = null
        private set

    /** Why the gatekeeper is off although SigLIP2 may be loaded (for example, labels not generated yet). */
    @Volatile var gatekeeperProblem: String? = null
        private set

    val llm = GenieXQwen(context, files)

    private var warmJob: Job? = null

    fun warmUp(): Job = warmJob ?: scope.launch(Dispatchers.Default) {
        loadSiglip()
        loadNomic()
    }.also { warmJob = it }

    suspend fun awaitWarm() {
        warmUp().join()
    }

    private fun loadSiglip() {
        if (!files.siglip.exists()) {
            _siglipState.value = ModelState.Missing(files.siglip.absolutePath)
            gatekeeperProblem = "SigLIP2 model missing"
            return
        }
        _siglipState.value = ModelState.Loading
        try {
            var vision: SiglipVision? = null
            val model = LiteRtModel.load(context, files.siglip, BACKEND_ORDER) { m ->
                vision = SiglipVision(m, config.siglip.inputRange).also { it.warmup() }
            }
            siglip = vision
            _siglipState.value = ModelState.Ready(model.backend, model.loadMs, "input ${model.inputs[0].shape().contentToString()}")
            gatekeeper = loadLabels()?.let { Gatekeeper(it, config.gatekeeper.margin, config.gatekeeper.uncertainBand) }
            if (gatekeeper == null) gatekeeperProblem = "assets/siglip_labels.json missing (run tools/siglip_labels.py)"
        } catch (t: Throwable) {
            Log.e(TAG, "SigLIP2 failed", t)
            _siglipState.value = ModelState.Failed(t.message ?: t.javaClass.simpleName)
            gatekeeperProblem = "SigLIP2 failed to load"
        }
    }

    private fun loadLabels(): SiglipLabels? = runCatching {
        context.assets.open("siglip_labels.json").use { json.decodeFromString(SiglipLabels.serializer(), it.readBytes().decodeToString()) }
    }.getOrNull()

    private fun loadNomic() {
        if (!files.nomic.exists()) {
            _nomicState.value = ModelState.Missing(files.nomic.absolutePath)
            return
        }
        _nomicState.value = ModelState.Loading
        try {
            val tokenizer = context.assets.open("nomic_vocab.txt").use { WordPieceTokenizer.fromVocab(it, 128) }
            var embedder: NomicEmbedder? = null
            val model = LiteRtModel.load(context, files.nomic, BACKEND_ORDER) { m ->
                embedder = NomicEmbedder(m, tokenizer).also { it.warmup() }
            }
            nomic = embedder
            _nomicState.value = ModelState.Ready(model.backend, model.loadMs, embedder?.pooling ?: "")
        } catch (t: Throwable) {
            Log.e(TAG, "Nomic failed", t)
            _nomicState.value = ModelState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Tokenizer for chunking; available even when the Nomic model file is missing. */
    val tokenizer: WordPieceTokenizer? by lazy {
        runCatching { context.assets.open("nomic_vocab.txt").use { WordPieceTokenizer.fromVocab(it, 128) } }.getOrNull()
    }

    companion object {
        private const val TAG = "ModelManager"
        val BACKEND_ORDER = listOf(Backend.NPU, Backend.GPU, Backend.CPU)
    }
}
