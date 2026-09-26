package com.hackathon.recall.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.ml.LlmState
import com.hackathon.recall.ml.Metrics
import com.hackathon.recall.ml.ModelState
import com.hackathon.recall.ml.NomicEmbedder
import com.hackathon.recall.search.EvalRunner
import com.hackathon.recall.search.IntentJson
import com.hackathon.recall.search.IntentValidator
import com.hackathon.recall.search.LlmJson
import com.hackathon.recall.search.Prompts
import com.hackathon.recall.model.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Benchmark screen (brief F11): per model, the compute unit that actually ran and measured latency;
 * plus index statistics. Every number here is measured on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val siglip by container.models.siglipState.collectAsState()
    val nomic by container.models.nomicState.collectAsState()
    val llm by container.models.llm.state.collectAsState()
    val version by Metrics.version.collectAsState()
    var running by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    val stats by produceState("", version) {
        value = withContext(Dispatchers.IO) {
            val r = container.repository
            "documents ${r.count()} · chunks ${r.chunkCount()} · vectors in memory ${r.vectorCount} · FTS ${r.ftsModule} · " +
                "last vector search ${r.lastVectorSearchMicros} µs · vault ${container.vault.sizeBytes() / 1024} KB · " +
                "DB key in StrongBox ${container.keys.strongBoxBacked}"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.bench_title)) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle(stringResource(R.string.bench_models))
            ModelLine("SigLIP2 vision", siglip)
            ModelLine("Nomic Embed v1.5", nomic)
            Text("Qwen3-4B-Instruct-2507 (GenieX qairt): ${llmText(llm)}")
            container.models.gatekeeperProblem?.let { Text("Gatekeeper: $it", color = MaterialTheme.colorScheme.error) }

            SectionTitle(stringResource(R.string.bench_latency))
            val rows = remember(version) { Metrics.snapshot() }
            if (rows.isEmpty()) Text(stringResource(R.string.bench_no_data))
            rows.forEach { s ->
                Text(
                    "%-22s n=%-4d last=%.1f p50=%.1f p95=%.1f".format(s.name, s.count, s.lastMs, s.p50Ms, s.p95Ms),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionTitle(stringResource(R.string.bench_index))
            Text(stats, style = MaterialTheme.typography.bodySmall)
            val internet = remember { requestsInternet(context) }
            Text(
                stringResource(if (internet) R.string.offline_violation else R.string.offline_ok),
                color = if (internet) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running, onClick = {
                    running = true
                    scope.launch {
                        log = withContext(Dispatchers.Default) { runBenchmark(container) }
                        running = false
                    }
                }) { Text(stringResource(if (running) R.string.bench_running else R.string.bench_run)) }
                OutlinedButton(enabled = !running, onClick = {
                    running = true
                    scope.launch {
                        log = withContext(Dispatchers.Default) { qwenJsonCheck(container) }
                        running = false
                    }
                }) { Text(stringResource(R.string.action_load_llm)) }
            }
            OutlinedButton(enabled = !running, onClick = {
                running = true
                scope.launch {
                    log = withContext(Dispatchers.Default) {
                        val json = context.assets.open("eval_queries.json").use { it.readBytes().decodeToString() }
                        val useLlm = container.models.llm.state.value is LlmState.Ready
                        "EVAL (${if (useLlm) "Qwen" else "rules"} parser)\n" + EvalRunner.report(EvalRunner(container.queryEngine).run(json, useLlm))
                    }
                    running = false
                }
            }) { Text(stringResource(R.string.bench_run_eval)) }
            if (log.isNotEmpty()) Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelLine(name: String, state: ModelState) {
    val text = when (state) {
        is ModelState.Ready -> stringResource(R.string.model_state_ready, state.backend.name, state.loadMs.toInt()) + " · " + state.detail
        is ModelState.Missing -> stringResource(R.string.model_state_missing) + " · " + state.path
        is ModelState.Failed -> stringResource(R.string.model_state_failed, state.message)
        ModelState.Loading -> stringResource(R.string.model_state_loading)
        ModelState.NotLoaded -> stringResource(R.string.model_state_not_loaded)
    }
    Text("$name: $text", color = if (state is ModelState.Ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
}

private fun llmText(s: LlmState) = when (s) {
    is LlmState.Ready -> "ready (${s.model}) · NPU"
    is LlmState.Missing -> "missing · ${s.path}"
    is LlmState.Failed -> "failed · ${s.message}"
    LlmState.Loading -> "loading…"
    LlmState.NotLoaded -> "not loaded (loads on first use)"
}

/** 20 timed runs per embedder on fixed inputs; results land in [Metrics]. */
private suspend fun runBenchmark(container: com.hackathon.recall.AppContainer): String {
    container.models.awaitWarm()
    val out = StringBuilder()
    container.models.siglip?.let { s ->
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawText("GOVERNMENT OF INDIA 1234 5678", 40f, 200f, Paint().apply { textSize = 36f }) }
        }
        repeat(20) { s.embed(bmp) }
        out.appendLine("SigLIP2 ×20 on ${s.backend}: last ${s.lastLatencyMs} ms")
    } ?: out.appendLine("SigLIP2 unavailable")
    container.models.nomic?.let { n ->
        repeat(20) { n.embed(NomicEmbedder.QUERY_PREFIX + "salary slips from the last three months") }
        out.appendLine("Nomic ×20 on ${n.backend}: last ${n.lastLatencyMs} ms")
        val q = n.embed(NomicEmbedder.QUERY_PREFIX + "health insurance policy")
        repeat(20) { container.repository.vectorSearch(q, 10) }
        out.appendLine("Vector search ×20 over ${container.repository.vectorCount} vectors: last ${container.repository.lastVectorSearchMicros} µs")
    } ?: out.appendLine("Nomic unavailable")
    return out.toString()
}

/** Phase 1 gate: Qwen must return valid intent JSON for 5 sample prompts. */
private suspend fun qwenJsonCheck(container: com.hackathon.recall.AppContainer): String {
    val llm = container.models.llm
    if (!llm.ensureLoaded()) return "Qwen not available: ${llm.state.value}"
    val prompts = listOf(
        "Home loan ki documents ready cheyyi",
        "show my salary slips from the last three months",
        "मेरा हेल्थ इंश्योरेंस कब खत्म हो रहा है",
        "నా కారు ఇన్సూరెన్స్ పాలసీ నంబర్ ఏంటి",
        "what expires next month",
    )
    val today = LocalDate.now()
    val lines = ArrayList<String>()
    for (p in prompts) {
        val line = try {
            val res = llm.complete(Prompts.intentSystem(today), p, 200)
            val valid = runCatching { IntentValidator.validate(LlmJson.decode<IntentJson>(res.text), p, Lang.EN) }
            "${if (valid.isSuccess) "VALID" else "INVALID"} ${res.totalMs} ms ttft=${"%.0f".format(res.ttftMs)} ms " +
                "${"%.1f".format(res.decodeTokensPerSec)} tok/s · ${valid.getOrNull()?.kind ?: valid.exceptionOrNull()?.message}"
        } catch (e: Exception) {
            "ERROR ${e.message}"
        }
        lines += line
    }
    return lines.joinToString("\n")
}

/** Runtime self-check (docs/DECISIONS.md D-005): does the installed build request INTERNET? */
private fun requestsInternet(context: android.content.Context): Boolean {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_PERMISSIONS.toLong()),
    )
    return info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
}
