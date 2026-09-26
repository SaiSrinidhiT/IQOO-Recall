package com.hackathon.recall.ui

import java.time.LocalDate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.type
import com.hackathon.recall.i18n.docTypeName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(nav: NavHostController, initialCategory: String? = null) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val docs by container.repository.observeDocuments().collectAsState(emptyList())

    // Map category names from HomeScreen to DocType sets
    val categoryDocTypes = mapOf(
        "identity" to setOf("AADHAAR", "PAN", "DRIVING_LICENCE", "PASSPORT", "VOTER_ID"),
        "income" to setOf("SALARY_SLIP", "BANK_STATEMENT", "EMPLOYMENT_LETTER", "ITR_FORM16", "LOAN_SANCTION_EMI"),
        "health" to setOf("HEALTH_ID_ABHA", "HEALTH_INSURANCE", "MEDICAL_REPORT", "HOSPITAL_BILL", "PRESCRIPTION"),
        "property" to setOf("PROPERTY_PAPER", "RENT_AGREEMENT", "VEHICLE_RC", "VEHICLE_INSURANCE", "UTILITY_BILL")
    )
    val initialFilterValue = initialCategory ?: "all"

    var activeFilter by rememberSaveable { mutableStateOf<String>(initialFilterValue) }
    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf("list") } // "list" or "grid"
    
    val types = docs.map { it.type() }.distinct()
    
    val filteredDocs = docs.filter { d ->
        val matchesCategory = when {
            activeFilter == "all" -> true
            activeFilter in categoryDocTypes -> d.docType in (categoryDocTypes[activeFilter] ?: emptySet())
            else -> d.docType == activeFilter
        }
        val matchesQuery = query.isBlank() || context.docTypeName(d.type()).contains(query, ignoreCase = true) || (d.ownerName?.contains(query, ignoreCase = true) == true)
        matchesCategory && matchesQuery
    }
    
    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                // Header
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable { nav.popBackStack() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Document Vault", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                
                // Search Box
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    placeholder = { Text("Search documents", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                
                // Categories Scroll
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isAll = activeFilter == "all"
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (isAll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface).border(1.dp, if (isAll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).clickable { activeFilter = "all" }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text("All", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isAll) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground)
                    }
                    val categoryLabels = listOf("identity" to "Identity", "income" to "Income", "health" to "Health", "property" to "Property")
                    categoryLabels.forEach { (key, label) ->
                        val isSel = activeFilter == key
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface).border(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).clickable { activeFilter = key }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
                
                // Sort and View Toggles
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort: Recent", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(if (view == "list") MaterialTheme.colorScheme.surface else Color.Transparent).clickable { view = "list" }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Box(Modifier.size(28.dp).clip(CircleShape).background(if (view == "grid") MaterialTheme.colorScheme.surface else Color.Transparent).clickable { view = "grid" }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Menu, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (filteredDocs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text("No documents found", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("Try a different search term or category.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            if (view == "list") {
                LazyColumn(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filteredDocs, key = { it.id }) { doc ->
                        VaultDocListItem(doc, onClick = { nav.navigate(Routes.doc(doc.id)) }, context = context)
                    }
                }
            } else {
                LazyVerticalGrid(columns = GridCells.Fixed(2), Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filteredDocs, key = { it.id }) { doc ->
                        VaultDocGridItem(doc, onClick = { nav.navigate(Routes.doc(doc.id)) }, context = context)
                    }
                }
            }
        }
    }
}

@Composable
fun VaultDocListItem(doc: DocumentEntity, onClick: () -> Unit, context: android.content.Context) {
    val date = LocalDate.ofEpochDay(doc.createdAt / 86_400_000).toString()
    val nowStr = LocalDate.now().toString()
    val nextMonthStr = LocalDate.now().plusDays(30).toString()
    val isExpired = doc.expiryOn != null && doc.expiryOn < nowStr
    val isExpiring = doc.expiryOn != null && doc.expiryOn < nextMonthStr
    val statusText = if (isExpired) "Expired" else if (isExpiring) "Expiring soon" else "Valid"
    val statusColor = if (isExpired) Color(0xFFF44336) else if (isExpiring) Color(0xFFFF9800) else Color(0xFF4CAF50)

    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(context.docTypeName(doc.type()), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(" • ", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(statusText, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.Lock, contentDescription = "Offline", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Default.MoreVert, contentDescription = "Options", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun VaultDocGridItem(doc: DocumentEntity, onClick: () -> Unit, context: android.content.Context) {
    val nowStr = LocalDate.now().toString()
    val nextMonthStr = LocalDate.now().plusDays(30).toString()
    val isExpired = doc.expiryOn != null && doc.expiryOn < nowStr
    val isExpiring = doc.expiryOn != null && doc.expiryOn < nextMonthStr
    val statusText = if (isExpired) "Expired" else if (isExpiring) "Expiring soon" else "Valid"
    val statusColor = if (isExpired) Color(0xFFF44336) else if (isExpiring) Color(0xFFFF9800) else Color(0xFF4CAF50)

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Text(context.docTypeName(doc.type()), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
            Text(doc.type().name, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Text(statusText, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = statusColor)
    }
}
