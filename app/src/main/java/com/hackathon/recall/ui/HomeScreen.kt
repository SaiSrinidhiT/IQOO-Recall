package com.hackathon.recall.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hackathon.recall.R
import com.hackathon.recall.data.type
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.templateName
import com.hackathon.recall.ingest.IndexWorker
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.model.Lang
import com.hackathon.recall.model.SourceKind
import com.hackathon.recall.search.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

enum class PhotoAccess { FULL, PARTIAL, NONE }

fun photoAccess(context: Context): PhotoAccess {
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
        else -> PhotoAccess.NONE
    }
}

fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val docs by container.repository.observeDocuments().collectAsState(emptyList())
    val duplicates by container.repository.observeDuplicates().collectAsState(emptyList())
    val counts by container.database.indexState().observeCounts().collectAsState(emptyList())
    val work by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE).collectAsState(emptyList())
    var access by remember { mutableStateOf(photoAccess(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var voiceLang by rememberSaveable { mutableStateOf(Lang.EN) }
    var listening by remember { mutableStateOf(false) }
    val voiceUnavailable = stringResource(R.string.voice_unavailable)

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = photoAccess(context)
        if (access != PhotoAccess.NONE) IndexWorker.enqueue(context)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                container.pipeline.ingest(IngestPipeline.Source(bytes, "application/pdf", SourceKind.PDF, uri.toString(), System.currentTimeMillis()))
            }
            snackbar.showSnackbar(outcomeMessage(context, outcome))
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Finish deferred LLM steps (doc type, expiry pick) only if Qwen is already loaded.
        if (container.models.llm.state.value is com.hackathon.recall.ml.LlmState.Ready) withContext(Dispatchers.Default) { container.enricher.runPending() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { nav.navigate(Routes.VAULT) }) { Text(stringResource(R.string.action_vault)) }
                    TextButton(onClick = { nav.navigate(Routes.BENCHMARK) }) { Text(stringResource(R.string.action_benchmark)) }
                    TextButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Text(stringResource(R.string.action_settings)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.ask_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) nav.navigate(Routes.results(query)) }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = { if (query.isNotBlank()) nav.navigate(Routes.results(query)) }) { Text(stringResource(R.string.action_ask)) }
                    OutlinedButton(onClick = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@OutlinedButton
                        }
                        listening = true
                        container.voice.start(voiceLang) { e ->
                            when (e) {
                                is VoiceInput.Event.Partial -> query = e.text
                                is VoiceInput.Event.Final -> {
                                    listening = false
                                    query = e.text
                                    if (e.text.isNotBlank()) nav.navigate(Routes.results(e.text))
                                }
                                is VoiceInput.Event.Error -> {
                                    listening = false
                                    scope.launch { snackbar.showSnackbar(voiceUnavailable) }
                                }
                            }
                        }
                    }) { Text(if (listening) stringResource(R.string.voice_listening) else stringResource(R.string.action_voice)) }
                    Lang.entries.forEach { l ->
                        FilterChip(selected = voiceLang == l, onClick = { voiceLang = l }, label = { Text(l.code.uppercase()) })
                    }
                }
            }
            item {
                Button(
                    onClick = { nav.navigate(Routes.EMERGENCY) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.emergency_button), style = MaterialTheme.typography.titleMedium) }
            }
            if (access != PhotoAccess.FULL) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(if (access == PhotoAccess.PARTIAL) R.string.index_partial_access else R.string.grant_photos))
                        TextButton(onClick = { photoLauncher.launch(photoPermissions()) }) {
                            Text(stringResource(if (access == PhotoAccess.PARTIAL) R.string.action_change_access else R.string.action_grant))
                        }
                    }
                }
            }
            item {
                val running = work.firstOrNull { it.state == WorkInfo.State.RUNNING }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (running != null) {
                            val done = running.progress.getInt(IndexWorker.KEY_DONE, 0)
                            val left = running.progress.getInt(IndexWorker.KEY_REMAINING, counts.firstOrNull { it.status == "pending" }?.n ?: 0)
                            val eta = running.progress.getLong(IndexWorker.KEY_ETA_SEC, 0)
                            Text(stringResource(R.string.index_title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.index_progress, done, left, eta.toInt()))
                            LinearProgressIndicator(progress = { if (done + left == 0) 0f else done / (done + left).toFloat() }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(stringResource(R.string.index_idle, docs.size))
                        }
                    }
                }
            }
            items(duplicates, key = { "dup-${it.id}" }) { dup ->
                val original = docs.firstOrNull { it.id == dup.dupOfDocId }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.duplicate_banner, context.docTypeName(dup.type()), original?.let { LocalDate.ofEpochDay(it.createdAt / 86_400_000).toString() } ?: "?"))
                        Row {
                            TextButton(onClick = { scope.launch { container.repository.resolveDuplicate(dup.id, keepBoth = true) } }) { Text(stringResource(R.string.action_keep_both)) }
                            TextButton(onClick = { scope.launch { container.repository.resolveDuplicate(dup.id, keepBoth = false) } }) { Text(stringResource(R.string.action_replace)) }
                        }
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.packs_title))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("home_loan", "health_insurance_claim", "vehicle_insurance_renewal").forEach { t ->
                        AssistChip(onClick = { nav.navigate(Routes.checklist(t)) }, label = { Text(context.templateName(t)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { nav.navigate(Routes.scan()) }) { Text(stringResource(R.string.action_scan)) }
                    OutlinedButton(onClick = { pdfLauncher.launch(arrayOf("application/pdf")) }) { Text(stringResource(R.string.action_add_pdf)) }
                }
            }
            val expiring = docs.filter { it.expiryOn != null }.sortedBy { it.expiryOn }.take(5)
            if (expiring.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.expiring_title)) }
                items(expiring, key = { "exp-${it.id}" }) { d -> DocRow(d, onClick = { nav.navigate(Routes.doc(d.id)) }) }
            }
            item { SectionTitle(stringResource(R.string.recent_title)) }
            if (docs.isEmpty()) item { Text(stringResource(R.string.empty_vault), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(docs.take(30), key = { it.id }) { d -> DocRow(d, onClick = { nav.navigate(Routes.doc(d.id)) }) }
        }
    }
}

fun outcomeMessage(context: Context, outcome: IngestPipeline.Outcome?): String = when (outcome) {
    is IngestPipeline.Outcome.Saved -> context.getString(R.string.scan_saved, context.docTypeName(outcome.type))
    is IngestPipeline.Outcome.ExactDuplicate -> context.getString(R.string.scan_duplicate, LocalDate.ofEpochDay(outcome.existing.createdAt / 86_400_000).toString())
    is IngestPipeline.Outcome.NotADocument -> context.getString(R.string.scan_not_document)
    is IngestPipeline.Outcome.Failed -> context.getString(R.string.scan_failed, outcome.reason)
    null -> context.getString(R.string.scan_failed, "-")
}
