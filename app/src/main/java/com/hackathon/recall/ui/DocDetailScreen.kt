package com.hackathon.recall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.data.EntityRow
import com.hackathon.recall.data.type
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.DocTypeSource
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocDetailScreen(nav: NavHostController, docId: Long) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by container.repository.observeDocument(docId).collectAsState(null)
    val entities by produceState<List<EntityRow>>(emptyList(), docId) { value = container.repository.entities(docId) }
    var typeMenu by remember { mutableStateOf(false) }
    var editExpiry by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.doc_title)) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        val d = doc ?: return@Scaffold
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Preview(d, Modifier.fillMaxWidth())
            Text(context.docTypeName(d.type()), style = MaterialTheme.typography.titleLarge)
            Text(d.titleEn, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.field_confidence, d.docTypeSource, (d.docTypeConfidence * 100).toInt()),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            d.issuedOn?.let { Text("${stringResource(R.string.field_issued)}: $it") }
            d.expiryOn?.let { ExpiryBadge(LocalDate.parse(it)) }
            d.ownerName?.let { Text("${stringResource(R.string.field_owner)}: $it") }
            Text("${stringResource(R.string.field_source)}: ${d.sourceKind}")
            if (d.sourceMissing) Text(stringResource(R.string.field_source_missing), color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (entities.isNotEmpty()) {
                SectionTitle(stringResource(R.string.field_entities))
                // Masked values only: sensitive numbers never appear in full on screen.
                entities.forEach { e -> Text("${e.kind}: ${e.valueMasked}", style = MaterialTheme.typography.bodySmall) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { typeMenu = true }) { Text(stringResource(R.string.action_change_type)) }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DocType.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(context.docTypeName(t)) }, onClick = {
                            typeMenu = false
                            scope.launch { container.repository.setDocType(d.id, t, DocTypeSource.USER, 1f) }
                        })
                    }
                }
                OutlinedButton(onClick = { editExpiry = true }) { Text(stringResource(R.string.action_edit_expiry)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        container.reminders.scheduleTest(d.id)
                        note = context.getString(R.string.test_reminder_set)
                    }
                }) { Text(stringResource(R.string.action_test_reminder)) }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }

        if (editExpiry) {
            var text by remember { mutableStateOf(d.expiryOn.orEmpty()) }
            AlertDialog(
                onDismissRequest = { editExpiry = false },
                title = { Text(stringResource(R.string.action_edit_expiry)) },
                text = { OutlinedTextField(text, { text = it }, placeholder = { Text(stringResource(R.string.expiry_hint)) }) },
                confirmButton = {
                    TextButton(onClick = {
                        val date = try {
                            text.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
                        } catch (_: DateTimeParseException) {
                            return@TextButton
                        }
                        editExpiry = false
                        scope.launch {
                            container.repository.setExpiry(d.id, date, "user")
                            if (date != null) container.reminders.scheduleFor(d.id, date) else container.reminders.cancelFor(d.id)
                        }
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = { TextButton(onClick = { editExpiry = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                text = { Text(stringResource(R.string.confirm_delete)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            container.reminders.cancelFor(d.id)
                            container.repository.delete(d.id)
                            nav.popBackStack()
                        }
                    }) { Text(stringResource(R.string.action_delete)) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
