package com.hackathon.recall.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hackathon.recall.R
import com.hackathon.recall.data.type
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.templateName
import com.hackathon.recall.ingest.IndexWorker
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.model.Lang
import com.hackathon.recall.model.SourceKind
import com.hackathon.recall.search.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize

enum class PhotoAccess { FULL, PARTIAL, NONE }

fun photoAccess(context: Context): PhotoAccess {
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.FULL
        else -> PhotoAccess.NONE
    }
}

fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val docs by container.repository.observeDocuments().collectAsState(emptyList())
    val duplicates by container.repository.observeDuplicates().collectAsState(emptyList())
    val counts by container.database.indexState().observeCounts().collectAsState(emptyList())
    val work by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE).collectAsState(emptyList())
    var access by remember { mutableStateOf(photoAccess(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var voiceLang by rememberSaveable { mutableStateOf(Lang.EN) }
    var listening by remember { mutableStateOf(false) }
    val voiceUnavailable = stringResource(R.string.voice_unavailable)

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = photoAccess(context)
        if (access != PhotoAccess.NONE) IndexWorker.enqueue(context)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                container.pipeline.ingest(IngestPipeline.Source(bytes, "application/pdf", SourceKind.PDF, uri.toString(), System.currentTimeMillis()))
            }
            snackbar.showSnackbar(outcomeMessage(context, outcome))
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Finish deferred LLM steps (doc type, expiry pick) only if Qwen is already loaded.
        if (container.models.llm.state.value is com.hackathon.recall.ml.LlmState.Ready) withContext(Dispatchers.Default) { container.enricher.runPending() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header Row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shield Icon
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("iQOO Recall", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text("English", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Locked chip
                Surface(color = MaterialTheme.colorScheme.secondary, shape = CircleShape) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Locked", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(8.dp))
                // Profile
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                    Text("A", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                // Ask Button
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable { nav.navigate(Routes.results("")) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Ask for any document…", color = Color.White.copy(alpha = 0.9f), modifier = Modifier.weight(1f))
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
                
                // Chips
                item {
                    LazyRow(Modifier.padding(top = 12.dp, bottom = 16.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chips = listOf("Home loan documents", "Latest salary slip", "Health insurance", "Car policy expiry")
                        items(chips) { chip ->
                            Surface(shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface, onClick = { nav.navigate(Routes.results(chip)) }) {
                                Text(chip, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                }

                // Stats Card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                        border = BorderStroke(1.dp, Color(0xFFC8E6C9))
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("${docs.size} documents securely indexed", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }

                // Categories
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Categories", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text("See all", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { nav.navigate(Routes.VAULT) })
                    }
                    // 2x2 Grid using columns/rows
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(Modifier.weight(1f).clickable { nav.navigate(Routes.VAULT) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(14.dp)) {
                                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Identity", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                                    Text("4 documents", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Card(Modifier.weight(1f).clickable { nav.navigate(Routes.VAULT) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(14.dp)) {
                                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Income", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                                    Text("5 documents", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(Modifier.weight(1f).clickable { nav.navigate(Routes.VAULT) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(14.dp)) {
                                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Health", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                                    Text("2 documents", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Card(Modifier.weight(1f).clickable { nav.navigate(Routes.VAULT) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.padding(14.dp)) {
                                    Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondary, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                                    Spacer(Modifier.height(8.dp))
                                    Text("Property", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
                                    Text("3 documents", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // Upcoming
                item {
                    val expiring = docs.filter { it.expiryOn != null }.sortedBy { it.expiryOn }.take(2)
                    if (expiring.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Upcoming", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            expiring.forEach { d ->
                                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(10.dp).background(Color(0xFFF44336), CircleShape))
                                        Spacer(Modifier.width(12.dp))
                                        Text(context.docTypeName(d.type()), modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                // Emergency Health Pack
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(20.dp).clickable { nav.navigate(Routes.EMERGENCY) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEA)),
                        border = BorderStroke(1.dp, Color(0xFFF9A8A2))
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).background(Color(0xFFF44336).copy(alpha=0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.FavoriteBorder, contentDescription = null, tint = Color(0xFFF44336))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Emergency Health Pack", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium)
                                Text("Quick access for medical emergencies", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Duplicates & Permissions & Worker Info (conditionally visible to not break functionality)
                item {
                    if (access != PhotoAccess.FULL) {
                        Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stringResource(if (access == PhotoAccess.PARTIAL) R.string.index_partial_access else R.string.grant_photos))
                                TextButton(onClick = { photoLauncher.launch(photoPermissions()) }) {
                                    Text(stringResource(if (access == PhotoAccess.PARTIAL) R.string.action_change_access else R.string.action_grant))
                                }
                            }
                        }
                    }
                }
            }

            // Bottom elements
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("On-device AI • No cloud", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { nav.navigate(Routes.scan()) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onBackground),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a new document")
                }
            }
        }
    }
}

fun outcomeMessage(context: Context, outcome: IngestPipeline.Outcome?): String = when (outcome) {
    is IngestPipeline.Outcome.Saved -> context.getString(R.string.scan_saved, context.docTypeName(outcome.type))
    is IngestPipeline.Outcome.ExactDuplicate -> context.getString(R.string.scan_duplicate, LocalDate.ofEpochDay(outcome.existing.createdAt / 86_400_000).toString())
    is IngestPipeline.Outcome.NotADocument -> context.getString(R.string.scan_not_document)
    is IngestPipeline.Outcome.Failed -> context.getString(R.string.scan_failed, outcome.reason)
    null -> context.getString(R.string.scan_failed, "-")
}
