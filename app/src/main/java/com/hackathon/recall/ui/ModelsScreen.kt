package com.hackathon.recall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R

/** "Models missing" screen (brief §3.3): what is expected where, and what is wrong with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val statuses = remember { container.modelFiles.statuses() }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.models_title)) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (statuses.any { !it.ok }) Text(stringResource(R.string.models_missing), color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.models_push_hint))
            Text(container.modelFiles.dir.absolutePath, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            statuses.forEach { s ->
                Text(
                    "${if (s.ok) "OK" else "✗"} ${s.name}\n   ${s.path}\n   ${s.problem ?: "${s.bytes / 1_000_000} MB"}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.action_continue_fallback)) }
        }
    }
}
