package com.hackathon.recall.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hackathon.recall.R
import com.hackathon.recall.ingest.IndexWorker
import com.hackathon.recall.model.SourceKind

/**
 * Gallery scan status: a progress bar with Stop while a scan runs; after Stop, a summary of what was
 * scanned (grouped by category) with Resume. [showSummaryWhenDone] also shows the summary once a scan
 * finishes normally; otherwise nothing is drawn when idle.
 */
@Composable
fun IndexStatus(
    modifier: Modifier = Modifier,
    showSummaryWhenDone: Boolean = false,
    onCategory: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val flow = remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE) }
    val work by flow.collectAsState(emptyList())
    var paused by remember { mutableStateOf(IndexWorker.isPaused(context)) }
    val active = work.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    when {
        paused -> ScanSummary(paused = true, modifier = modifier, onCategory = onCategory, onResume = {
            IndexWorker.enqueue(context, userInitiated = true)
            paused = false
        })
        active != null -> ScanProgress(active, modifier, onStop = {
            IndexWorker.stop(context)
            paused = true
        })
        showSummaryWhenDone -> ScanSummary(paused = false, modifier = modifier, onCategory = onCategory, onResume = null)
    }
}

@Composable
private fun ScanProgress(info: WorkInfo, modifier: Modifier, onStop: () -> Unit) {
    // Progress keys are absent until the worker has scanned MediaStore and counted pending photos.
    val counted = info.state == WorkInfo.State.RUNNING && info.progress.keyValueMap.containsKey(IndexWorker.KEY_REMAINING)
    val done = info.progress.getInt(IndexWorker.KEY_DONE, 0)
    val left = info.progress.getInt(IndexWorker.KEY_REMAINING, 0)
    val eta = info.progress.getLong(IndexWorker.KEY_ETA_SEC, 0L)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.index_title), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onStop) { Text(stringResource(R.string.index_stop)) }
        }
        if (counted && done + left > 0) {
            LinearProgressIndicator(progress = { done.toFloat() / (done + left) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.index_progress, done, left, eta), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.index_preparing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ScanSummary(paused: Boolean, modifier: Modifier, onCategory: ((String) -> Unit)?, onResume: (() -> Unit)?) {
    val container = LocalContainer.current
    val countsFlow = remember { container.database.indexState().observeCounts() }
    val counts by countsFlow.collectAsState(emptyList())
    val docsFlow = remember { container.repository.observeDocuments() }
    val docs by docsFlow.collectAsState(emptyList())

    fun n(status: String) = counts.firstOrNull { it.status == status }?.n ?: 0
    val otherPhotos = n("skipped_non_doc")
    val failed = n("failed")
    val pending = n("pending")
    val scanned = n("done") + otherPhotos + failed
    val total = scanned + pending
    val byCategory = docs.filter { it.sourceKind == SourceKind.GALLERY.db }.groupingBy { DocCategory.of(it) }.eachCount()

    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (paused) R.string.index_paused_title else R.string.index_done_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.index_scanned, scanned, total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (total > 0) LinearProgressIndicator(progress = { scanned.toFloat() / total }, modifier = Modifier.fillMaxWidth())

            SummaryRow(stringResource(R.string.index_documents_found), byCategory.values.sum(), bold = true)
            (DocCategory.TYPES.keys + DocCategory.OTHER).forEach { key ->
                val count = byCategory[key] ?: 0
                if (count > 0) {
                    val open = onCategory?.takeIf { key != DocCategory.OTHER }?.let { { it(key) } }
                    SummaryRow(stringResource(DocCategory.label(key)), count, indent = true, onClick = open)
                }
            }
            if (failed > 0) SummaryRow(stringResource(R.string.index_failed), failed)
            if (pending > 0) SummaryRow(stringResource(R.string.index_not_scanned), pending)

            if (onResume != null) {
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(12.dp)) {
                    Text(stringResource(R.string.index_resume))
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, count: Int, bold: Boolean = false, indent: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(start = if (indent) 12.dp else 0.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}
