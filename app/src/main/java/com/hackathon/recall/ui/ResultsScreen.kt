package com.hackathon.recall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.search.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(nav: NavHostController, initialQuery: String) {
    val container = LocalContainer.current
    var queryInput by rememberSaveable { mutableStateOf("") }
    var currentQuery by rememberSaveable { mutableStateOf(initialQuery) }

    val result by produceState<QueryResult?>(null, currentQuery) {
        value = null
        value = withContext(Dispatchers.Default) { container.queryEngine.ask(currentQuery) }
    }

    LaunchedEffect(result) {
        when (val r = result) {
            is QueryResult.Pack -> nav.navigate(Routes.checklist(r.templateId)) { popUpTo(Routes.HOME) }
            is QueryResult.Emergency -> nav.navigate(Routes.EMERGENCY) { popUpTo(Routes.HOME) }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable { nav.popBackStack() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("Ask Recall", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Default.DateRange, contentDescription = "History", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        bottomBar = {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Row(Modifier.padding(16.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = queryInput,
                        onValueChange = { queryInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type in English, Telugu or Hindi…", fontSize = 12.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (queryInput.isNotBlank()) {
                                currentQuery = queryInput
                                queryInput = ""
                            }
                        })
                    )
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary, CircleShape).clickable {
                        if (queryInput.isNotBlank()) {
                            currentQuery = queryInput
                            queryInput = ""
                        }
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(currentQuery, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondary, CircleShape).padding(top = 4.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(8.dp))

                    when (val r = result) {
                        null -> {
                            Box(Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).padding(16.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                        is QueryResult.Found -> {
                            Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).padding(16.dp)) {
                                Text(r.answer.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        is QueryResult.Reminders -> {
                            Column(Modifier.weight(1f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)).padding(16.dp)) {
                                Text("Here are your reminders.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        else -> {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            if (result is QueryResult.Found) {
                val r = result as QueryResult.Found
                if (r.hits.isNotEmpty()) {
                    item { Spacer(Modifier.height(8.dp)) }
                    val cited = r.answer.citedDocIds.toSet()
                    items(r.hits.sortedByDescending { it.doc.id in cited }, key = { it.doc.id }) { h ->
                        DocRow(h.doc, onClick = { nav.navigate(Routes.doc(h.doc.id)) })
                    }
                }
            }

            if (result is QueryResult.Reminders) {
                val r = result as QueryResult.Reminders
                if (r.docs.isEmpty()) {
                    item { Text(stringResource(R.string.no_expiry_docs)) }
                }
                items(r.docs, key = { it.id }) { d -> 
                    DocRow(d, onClick = { nav.navigate(Routes.doc(d.id)) }) 
                }
            }

            if (result != null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Answered completely on-device", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
