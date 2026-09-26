package com.hackathon.recall.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.data.EntityRow
import com.hackathon.recall.data.displayTitle
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.data.type
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.DocTypeSource
import com.hackathon.recall.model.EntityKind
import com.hackathon.recall.model.SourceKind
import com.hackathon.recall.search.Relation
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
    val allDocs by container.repository.observeDocuments().collectAsState(emptyList())
    var relations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { relations = People.relations(container.database) }
    val owners = remember(allDocs) { allDocs.mapNotNull { it.ownerName }.distinct().sorted() }
    val relationByName = remember(relations) { relations.entries.associate { (k, v) -> v to Relation.fromDb(k) } }
    var typeMenu by remember { mutableStateOf(false) }
    var editExpiry by remember { mutableStateOf(false) }
    var ownerDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable { nav.popBackStack() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.doc_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        },
    ) { padding ->
        val d = doc ?: return@Scaffold
        val confident = DocCategory.isConfident(d)
        Column(
            Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Preview(d, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(context.docTypeName(d.effectiveType()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (d.displayTitle().isNotBlank()) Text(d.displayTitle(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ConfidenceChip(d.docTypeSource, d.docTypeConfidence, d.isUserConfirmed, confident)
                if (!confident) {
                    Text(stringResource(R.string.confidence_low_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { scope.launch { container.repository.setDocType(d.id, d.type(), DocTypeSource.USER, 1f) } }) {
                        Text(stringResource(R.string.action_confirm_type, context.docTypeName(d.type())))
                    }
                }
            }

            DetailCard {
                d.issuedOn?.let { DetailRow(stringResource(R.string.field_issued), it) }
                d.expiryOn?.let {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.field_expiry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        ExpiryBadge(LocalDate.parse(it))
                    }
                }
                OwnerRow(d.ownerName, relationByName[d.ownerName]?.let { stringResource(relationLabel(it)) }, onClick = { ownerDialog = true })
                DetailRow(stringResource(R.string.field_source), stringResource(sourceLabel(d.sourceKind)))
                if (d.sourceMissing) Text(stringResource(R.string.field_source_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (entities.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.field_entities), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    // Masked values only: sensitive numbers never appear in full on screen.
                    DetailCard { entities.forEach { e -> DetailRow(entityLabel(e.kind), e.valueMasked) } }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { typeMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_change_type)) }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DocType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(context.docTypeName(t)) }, onClick = {
                                typeMenu = false
                                scope.launch { container.repository.setDocType(d.id, t, DocTypeSource.USER, 1f) }
                            })
                        }
                    }
                }
                OutlinedButton(onClick = { editExpiry = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_edit_expiry)) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    scope.launch {
                        container.reminders.scheduleTest(d.id)
                        note = context.getString(R.string.test_reminder_set)
                    }
                }) { Text(stringResource(R.string.action_test_reminder)) }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(16.dp))
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
        if (ownerDialog) {
            var newName by remember { mutableStateOf("") }
            fun assign(name: String?) {
                scope.launch {
                    container.repository.setOwner(d.id, name)
                    People.markManuallyTagged(container.database, d.id)
                }
                ownerDialog = false
            }
            fun assignWithRelation(relation: Relation) {
                if (newName.isBlank()) return
                scope.launch {
                    container.repository.setOwner(d.id, newName)
                    People.markManuallyTagged(container.database, d.id)
                    People.setRelation(container.database, relation, newName)
                }
                ownerDialog = false
            }
            AlertDialog(
                onDismissRequest = { ownerDialog = false },
                title = { Text(stringResource(R.string.owner_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (owners.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                owners.forEach { name ->
                                    val label = relationByName[name]?.let { stringResource(relationLabel(it)) }
                                    TextButton(onClick = { assign(name) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (label != null) "$name · $label" else name, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                                    }
                                }
                            }
                        }
                        Text(stringResource(R.string.owner_add_new), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            newName, { newName = it },
                            placeholder = { Text(stringResource(R.string.owner_new_name_hint)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.owner_relation_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Relation.entries.forEach { relation ->
                                OutlinedButton(onClick = { assignWithRelation(relation) }, enabled = newName.isNotBlank()) {
                                    Text(stringResource(relationLabel(relation)), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { if (newName.isNotBlank()) assign(newName) else ownerDialog = false }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    if (d.ownerName != null) {
                        TextButton(onClick = { assign(null) }) { Text(stringResource(R.string.owner_remove), color = MaterialTheme.colorScheme.error) }
                    } else {
                        TextButton(onClick = { ownerDialog = false }) { Text(stringResource(R.string.cancel)) }
                    }
                },
            )
        }
    }
}

/** "Detected by keyword rules · 97%": green at or above the category threshold, amber below it. */
@Composable
private fun ConfidenceChip(source: String, confidence: Float, userConfirmed: Boolean, confident: Boolean) {
    val (bg, fg) = if (confident) Color(0xFFE8F5E9) to Color(0xFF2E7D32) else Color(0xFFFFF4E5) to Color(0xFFB26A00)
    val text = if (userConfirmed) {
        stringResource(R.string.confidence_user)
    } else {
        stringResource(R.string.field_confidence, stringResource(detectorLabel(source)), (confidence * 100).toInt())
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun DetailCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** Owner field, always tappable: shows the name (with relation, e.g. "Suresh Kumar · Father") or a
 * prompt to tag one when OCR found no name — the same tap either way. */
@Composable
private fun OwnerRow(name: String?, relation: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.field_owner), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (name != null) {
            Text(if (relation != null) "$name · $relation" else name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        } else {
            Text(stringResource(R.string.owner_unassigned), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun detectorLabel(source: String): Int = when (source) {
    DocTypeSource.SIGLIP.db -> R.string.detector_image
    DocTypeSource.LLM.db -> R.string.detector_llm
    DocTypeSource.USER.db -> R.string.detector_user
    else -> R.string.detector_rules
}

private fun sourceLabel(kind: String): Int = when (kind) {
    SourceKind.CAMERA.db -> R.string.source_camera
    SourceKind.PDF.db -> R.string.source_pdf
    else -> R.string.source_gallery
}

private fun entityLabel(kind: String): String = when (EntityKind.fromDb(kind)) {
    EntityKind.AADHAAR -> "Aadhaar"
    EntityKind.PAN -> "PAN"
    EntityKind.AMOUNT -> "Amount"
    EntityKind.DATE -> "Date"
    EntityKind.POLICY_NO -> "Policy no."
    EntityKind.VEHICLE_NO -> "Vehicle no."
    EntityKind.IFSC -> "IFSC"
    EntityKind.PHONE -> "Phone"
    EntityKind.ABHA -> "ABHA"
    EntityKind.OTHER -> "Other"
}
