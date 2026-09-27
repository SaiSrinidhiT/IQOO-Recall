package com.hackathon.recall.ui

import android.Manifest
import android.app.AlarmManager
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.model.Lang

/** Stores the demo-only "allow screen capture" switch; FLAG_SECURE is on unless the user turns it off. */
object ScreenCapture {
    private const val PREFS = "settings"
    private const val KEY = "allow_capture"
    fun allowed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(context: Context, allowed: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, allowed).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    var capture by remember { mutableStateOf(ScreenCapture.allowed(context)) }
    val voiceLangs by produceState<List<String>?>(null) { value = container.voice.installedOfflineLanguages() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PermissionLine(stringResource(R.string.perm_photos), when (photoAccess(context)) {
                PhotoAccess.FULL -> stringResource(R.string.perm_granted)
                PhotoAccess.PARTIAL -> stringResource(R.string.perm_partial)
                PhotoAccess.NONE -> stringResource(R.string.perm_denied)
            }) { context.startActivity(appSettings(context)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionLine(stringResource(R.string.perm_notifications), granted(context, Manifest.permission.POST_NOTIFICATIONS)) { context.startActivity(appSettings(context)) }
            }
            PermissionLine(
                stringResource(R.string.perm_exact_alarms),
                if (context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) stringResource(R.string.perm_granted) else stringResource(R.string.perm_denied),
            ) { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
            PermissionLine(stringResource(R.string.perm_camera), granted(context, Manifest.permission.CAMERA)) { context.startActivity(appSettings(context)) }
            PermissionLine(stringResource(R.string.perm_mic), granted(context, Manifest.permission.RECORD_AUDIO)) { context.startActivity(appSettings(context)) }

            SectionTitle(stringResource(R.string.voice_langs))
            Text(voiceLangs?.joinToString().takeUnless { it.isNullOrEmpty() } ?: stringResource(R.string.voice_unavailable), style = MaterialTheme.typography.bodySmall)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SectionTitle(stringResource(R.string.app_language))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lang.entries.forEach { l ->
                        FilterChip(selected = false, onClick = {
                            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(l.code)
                        }, label = { Text(l.englishName) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(stringResource(R.string.allow_screen_capture), modifier = Modifier.weight(1f))
                Switch(checked = capture, onCheckedChange = { capture = it; ScreenCapture.set(context, it) })
            }
        }
    }
}

@Composable
private fun PermissionLine(name: String, state: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(name)
            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onFix) { Text(stringResource(R.string.action_settings)) }
    }
}

@Composable
private fun granted(context: Context, permission: String): String =
    if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) stringResource(R.string.perm_granted) else stringResource(R.string.perm_denied)

private fun appSettings(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
