package com.hackathon.recall.ui

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.hackathon.recall.MainActivity
import com.hackathon.recall.R
import com.hackathon.recall.actions.ChecklistResult
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.EntityRow
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.model.Lang
import kotlinx.coroutines.launch

/**
 * Emergency health pack (brief F9, D-001). Text only: no document images, so an Aadhaar card photo
 * can never be shown unmasked here. Readable without unlocking; nothing else is.
 */
@Composable
fun EmergencyScreen(locked: Boolean, onOpenVault: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = remember { Lang.fromCode(context.resources.configuration.locales[0].language) ?: Lang.EN }
    var note by remember { mutableStateOf<String?>(null) }
    val pack by produceState<Triple<ChecklistResult, Map<Long, DocumentEntity>, Map<Long, List<EntityRow>>>?>(null) {
        val r = container.emergency.pack()
        val ids = r.packDocIds
        value = Triple(r, container.repository.byIds(ids).associateBy { it.id }, ids.associateWith { container.repository.entities(it) })
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.emergency_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        if (locked) Text(stringResource(R.string.emergency_locked_note), style = MaterialTheme.typography.bodySmall)
        val p = pack ?: return@Column
        p.first.items.forEach { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(context.docTypeName(item.item.docType), style = MaterialTheme.typography.titleSmall)
                    if (item.found.isEmpty()) Text(stringResource(R.string.status_missing), color = MaterialTheme.colorScheme.error)
                    item.found.forEach { f ->
                        val doc = p.second[f.id] ?: return@forEach
                        Text(doc.titleEn, style = MaterialTheme.typography.bodyMedium)
                        doc.expiryOn?.let { Text(stringResource(R.string.expires_on, it), style = MaterialTheme.typography.bodySmall) }
                        p.third[f.id].orEmpty().filter { it.kind in setOf("aadhaar", "abha", "policy_no", "phone") }
                            .forEach { e -> Text("${e.kind}: ${e.valueMasked}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val text = container.emergency.summary(p.first, lang)
                    val used = container.speaker.speak(text, lang)
                    note = when {
                        used == null -> context.getString(R.string.emergency_tts_unavailable)
                        used != lang -> context.getString(R.string.emergency_tts_fallback, lang.englishName)
                        else -> null
                    }
                }
            }) { Text(stringResource(R.string.emergency_read_aloud)) }
            OutlinedButton(onClick = { container.speaker.stop() }) { Text(stringResource(R.string.emergency_stop)) }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onOpenVault) { Text(stringResource(R.string.emergency_open_vault)) }
    }
}

/** Quick Settings tile that opens the emergency pack (brief §7), over the lock screen if needed. */
class EmergencyTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EMERGENCY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
