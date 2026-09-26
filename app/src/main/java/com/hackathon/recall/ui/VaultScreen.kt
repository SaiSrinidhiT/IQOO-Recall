package com.hackathon.recall.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.data.type
import com.hackathon.recall.i18n.docTypeName

/** Every stored document, filterable by type and by person (owner name). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val docs by container.repository.observeDocuments().collectAsState(emptyList())
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var person by rememberSaveable { mutableStateOf<String?>(null) }
    val types = docs.map { it.type() }.distinct()
    val people = docs.mapNotNull { it.ownerName }.distinct()
    val shown = docs.filter { (type == null || it.docType == type) && (person == null || it.ownerName == person) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.vault_title)) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == null, onClick = { type = null }, label = { Text(stringResource(R.string.filter_all)) })
                    types.forEach { t -> FilterChip(selected = type == t.name, onClick = { type = t.name }, label = { Text(context.docTypeName(t)) }) }
                }
                if (people.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = person == null, onClick = { person = null }, label = { Text(stringResource(R.string.filter_person)) })
                    people.forEach { p -> FilterChip(selected = person == p, onClick = { person = p }, label = { Text(p) }) }
                }
            }
            items(shown, key = { it.id }) { d -> DocRow(d, onClick = { nav.navigate(Routes.doc(d.id)) }) }
        }
    }
}
