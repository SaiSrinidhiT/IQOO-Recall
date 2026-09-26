package com.hackathon.recall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.actions.ChecklistEngine
import com.hackathon.recall.actions.ChecklistResult
import com.hackathon.recall.actions.IgnoreReason
import com.hackathon.recall.actions.ItemStatus
import com.hackathon.recall.actions.PackBuilder
import com.hackathon.recall.data.toSummary
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.templateName
import com.hackathon.recall.model.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Task pack checklist (brief F5): found vs missing per template item with counts, "Scan" for missing
 * items, and a masked PDF of everything found. Recomputed live whenever documents change, so a new
 * scan ticks its item off immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChecklistScreen(nav: NavHostController, templateId: String, pinnedDocId: Long?) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val docs by container.repository.observeDocuments().collectAsState(emptyList())
    val template = remember(templateId) { container.templates.byId(templateId) }
    val lang = remember { Lang.fromCode(context.resources.configuration.locales[0].language) ?: Lang.EN }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        container.packBuilder.deleteSharedFiles()
    }

    val result by produceState<ChecklistResult?>(null, docs, template) {
        value = template?.let { t ->
            val summaries = docs.map { it.toSummary() }
            ChecklistEngine.evaluate(t, summaries, LocalDate.now(), pinned = summaries.firstOrNull { it.id == pinnedDocId })
        }
    }
    val title = context.templateName(templateId)

    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        val r = result
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r == null) return@LazyColumn
            item {
                Text(
                    stringResource(if (r.complete) R.string.checklist_complete else R.string.checklist_incomplete),
                    color = if (r.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            items(r.items) { item ->
                val name = context.docTypeName(item.item.docType)
                val (label, color) = when (item.status) {
                    ItemStatus.COMPLETE -> stringResource(R.string.status_found) to MaterialTheme.colorScheme.primary
                    ItemStatus.PARTIAL -> stringResource(R.string.status_count, item.found.size, item.item.count) to MaterialTheme.colorScheme.tertiary
                    ItemStatus.MISSING -> stringResource(R.string.status_missing) to MaterialTheme.colorScheme.error
                    ItemStatus.OPTIONAL_MISSING -> stringResource(R.string.status_optional) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text("$label · ${item.found.size}/${item.item.count}", color = color, style = MaterialTheme.typography.bodySmall)
                            }
                            if (item.status == ItemStatus.MISSING || item.status == ItemStatus.PARTIAL || item.status == ItemStatus.OPTIONAL_MISSING) {
                                OutlinedButton(onClick = { nav.navigate(Routes.scan(item.item.docType.name)) }) { Text(stringResource(R.string.action_scan)) }
                            }
                        }
                        item.found.forEach { f -> docs.firstOrNull { it.id == f.id }?.let { d -> DocRow(d, onClick = { nav.navigate(Routes.doc(d.id)) }) } }
                        item.ignored.map { it.second }.distinct().forEach { reason ->
                            val note = when (reason) {
                                IgnoreReason.TOO_OLD -> R.string.ignored_too_old
                                IgnoreReason.EXPIRED -> R.string.ignored_expired
                                IgnoreReason.SAME_MONTH -> R.string.ignored_same_month
                                IgnoreReason.EXTRA -> null
                            }
                            note?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            item {
                Button(
                    enabled = !busy && r.packDocIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    onClick = {
                        busy = true
                        status = context.getString(R.string.pack_building)
                        scope.launch {
                            val built = withContext(Dispatchers.Default) { container.packBuilder.build(r, title, lang) }
                            busy = false
                            status = when (built) {
                                is PackBuilder.Result.Ready -> {
                                    shareLauncher.launch(container.packBuilder.shareIntent(built.file))
                                    null
                                }
                                is PackBuilder.Result.Blocked -> context.getString(R.string.pack_blocked, built.document, built.reason)
                                is PackBuilder.Result.Failed -> context.getString(R.string.pack_failed, built.reason)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.action_build_pdf)) }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
