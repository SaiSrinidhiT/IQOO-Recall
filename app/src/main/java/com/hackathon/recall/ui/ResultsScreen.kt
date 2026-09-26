package com.hackathon.recall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.search.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(nav: NavHostController, query: String) {
    val container = LocalContainer.current
    val result by produceState<QueryResult?>(null, query) {
        value = withContext(Dispatchers.Default) { container.queryEngine.ask(query) }
    }
    // Pack and emergency intents open their own screens.
    LaunchedEffect(result) {
        when (val r = result) {
            is QueryResult.Pack -> nav.navigate(Routes.checklist(r.templateId)) { popUpTo(Routes.HOME) }
            is QueryResult.Emergency -> nav.navigate(Routes.EMERGENCY) { popUpTo(Routes.HOME) }
            else -> {}
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(query) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val r = result) {
                null -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                is QueryResult.Found -> {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(r.answer.text, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(if (r.answer.source == "llm") R.string.results_mode_llm else R.string.results_mode_rules),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (r.hits.isNotEmpty()) item { SectionTitle(stringResource(R.string.results_sources)) }
                    val cited = r.answer.citedDocIds.toSet()
                    items(r.hits.sortedByDescending { it.doc.id in cited }, key = { it.doc.id }) { h ->
                        DocRow(h.doc, onClick = { nav.navigate(Routes.doc(h.doc.id)) })
                    }
                }
                is QueryResult.Reminders -> {
                    item { SectionTitle(stringResource(R.string.reminders_title)) }
                    if (r.docs.isEmpty()) item { Text(stringResource(R.string.no_expiry_docs)) }
                    items(r.docs, key = { it.id }) { d -> DocRow(d, onClick = { nav.navigate(Routes.doc(d.id)) }) }
                }
                is QueryResult.Pack, is QueryResult.Emergency -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
            }
        }
    }
}
