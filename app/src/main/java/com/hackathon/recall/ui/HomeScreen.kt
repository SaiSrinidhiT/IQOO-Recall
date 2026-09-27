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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.templateName
import com.hackathon.recall.ingest.IndexWorker
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.model.Lang
import com.hackathon.recall.model.PhotoCategory
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

/** Photos plus their location (EXIF GPS, read on the phone only) so trips can be grouped. */
fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED, Manifest.permission.ACCESS_MEDIA_LOCATION)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
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
    val photoCountRows by remember { container.database.photos().observeCounts() }.collectAsState(emptyList())
    val photoCounts = photoCountRows.associate { it.category to it.n }
    val tripCount by remember { container.database.photos().observeTripCount() }.collectAsState(0)
    var access by remember { mutableStateOf(photoAccess(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var voiceLang by rememberSaveable { mutableStateOf(Lang.EN) }
    var listening by remember { mutableStateOf(false) }
    val voiceUnavailable = stringResource(R.string.voice_unavailable)

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = photoAccess(context)
        if (access != PhotoAccess.NONE) IndexWorker.enqueue(context, userInitiated = true)
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
                // Profile
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable { nav.navigate(Routes.SETTINGS) }, contentAlignment = Alignment.Center) {
                    Text("A", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                item { Spacer(Modifier.height(4.dp)) }

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
                            Text("${docs.size} documents securely indexed", fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                        }
                    }
                }

                item {
                    IndexStatus(
                        Modifier.padding(horizontal = 20.dp).padding(top = 12.dp),
                        onCategory = { nav.navigate(Routes.vaultCategory(it)) },
                    )
                }

                // Gallery categories: every photo filed as Screenshots, Selfies, People, Food, Trips & places,
                // Bills or Documents (the vault). Counts update live as the scan files photos.
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.your_gallery), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.see_all_photos), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { nav.navigate(Routes.VAULT) })
                    }
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PhotoCategoryUi.HOME.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { cat ->
                                    val n = photoCounts[cat.db] ?: 0
                                    val subtitle = when (cat) {
                                        PhotoCategory.DOCUMENTS -> stringResource(R.string.docs_count, docs.size)
                                        PhotoCategory.PLACES if tripCount > 0 ->
                                            pluralStringResource(R.plurals.trips_count, tripCount, tripCount) + " · " + pluralStringResource(R.plurals.photos_count, n, n)
                                        else -> pluralStringResource(R.plurals.photos_count, n, n)
                                    }
                                    GalleryTile(cat, subtitle, Modifier.weight(1f)) {
                                        nav.navigate(if (cat == PhotoCategory.DOCUMENTS) Routes.VAULT else Routes.photos(cat.db))
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(20.dp)) }

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

            // Bottom elements: quick-ask chips, the scan button, then the Ask bar as the lowest,
            // most reachable element on screen (moved down from the top of the scrolling list).
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                LazyRow(Modifier.padding(bottom = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val chips = listOf("Home loan documents", "Latest salary slip", "Health insurance", "Car policy expiry")
                    items(chips) { chip ->
                        Surface(shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surface, onClick = { nav.navigate(Routes.results(chip)) }) {
                            Text(chip, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("On-device AI • No cloud", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(
                    onClick = { nav.navigate(Routes.scan()) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 12.dp).height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onBackground),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a new document")
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).clickable { nav.navigate(Routes.results("")) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(Color.White.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Ask for any document…", color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(cat: PhotoCategory, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondary, CircleShape), contentAlignment = Alignment.Center) {
                Text(PhotoCategoryUi.glyph(cat), fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(PhotoCategoryUi.label(cat)), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodySmall)
            Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
