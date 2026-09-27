package com.hackathon.recall.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.displayTitle
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.ingest.IndexWorker
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.model.PhotoCategory
import com.hackathon.recall.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay

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

/** A suggested question shown as a card: a short label plus the exact question it asks. */
private data class Suggestion(val title: String, val question: String)

private val SUGGESTIONS = listOf(
    Suggestion("Find my travel documents", "Show me my flight tickets for Mumbai"),
    Suggestion("Analyze recent spending", "Summarize my grocery bills from last week"),
    Suggestion("ID & Identity cards", "Where is my PAN card scan?"),
)

/** iQOO Recall's brand orange (the launcher icon's gradient, and the assistant's own avatar). */
private val AccentOrange = Color(0xFFFF7A1A)

/** A teal distinct from the app's navy primary, for the greeting headline only. */
private val HeadlineTeal = Color(0xFF1B6B66)

private fun greeting(hour: Int): String = when (hour) {
    in 0..11 -> "Good morning."
    in 12..16 -> "Good afternoon."
    else -> "Good evening."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val docs by container.repository.observeDocuments().collectAsState(emptyList())
    var access by remember { mutableStateOf(photoAccess(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var hour by remember { mutableStateOf(LocalTime.now().hour) }
    val photoCountRows by remember { container.database.photos().observeCounts() }.collectAsState(emptyList())
    val photoCounts = photoCountRows.associate { it.category to it.n }
    val tripCount by remember { container.database.photos().observeTripCount() }.collectAsState(0)

    // The greeting ("Good morning." → "Good afternoon.") changes while the app stays open across the hour, not only on next launch.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            hour = LocalTime.now().hour
        }
    }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = photoAccess(context)
        if (access != PhotoAccess.NONE) IndexWorker.enqueue(context, userInitiated = true)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
                container.pipeline.ingest(IngestPipeline.Source(bytes, "application/pdf", SourceKind.PDF, uri.toString(), System.currentTimeMillis()))
            }
            snackbar.showSnackbar(outcomeMessage(context, outcome))
        }
    }
    fun ask(text: String) {
        if (text.isBlank()) return
        nav.navigate(Routes.results(text))
        query = ""
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
                .imePadding() // Edge-to-edge (MainActivity.enableEdgeToEdge): without this the keyboard draws
                              // over the ask bar instead of the layout shifting above it.
        ) {
            // Header: the app icon, the app name, a way back into past chats, and the profile shortcut.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painterResource(R.drawable.ic_launcher_art),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Text("IQOO Recall", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                TextButton(onClick = { nav.navigate(Routes.results("")) }) {
                    Text(stringResource(R.string.history), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable { nav.navigate(Routes.SETTINGS) }, contentAlignment = Alignment.Center) {
                    Text("A", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                }
            }

            LazyColumn(Modifier.weight(1f)) {
                // Greeting
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(greeting(hour), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("How can I help you find things today?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = HeadlineTeal)
                    }
                }

                // Gallery categories: every photo filed as Screenshots, Selfies, People, Food, Trips & places,
                // Bills or Documents (the vault). Counts update live as the scan files photos.
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.your_gallery), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.see_all_photos), color = AccentOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { nav.navigate(Routes.VAULT) })
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

                item {
                    IndexStatus(
                        Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 4.dp),
                        onCategory = { nav.navigate(Routes.vaultCategory(it)) },
                    )
                }

                // Suggested things to ask, each a title plus the exact question it sends to chat.
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SUGGESTIONS.forEach { s -> SuggestionCard(s) { ask(s.question) } }
                    }
                }

                // Recently captured: the newest documents, across every type, with a real thumbnail each.
                if (docs.isNotEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("RECENTLY CAPTURED", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("VIEW ALL", color = AccentOrange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { nav.navigate(Routes.VAULT) })
                        }
                    }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(docs.take(10), key = { it.id }) { doc -> RecentCard(doc) { nav.navigate(Routes.doc(doc.id)) } }
                        }
                    }
                }

                item { Spacer(Modifier.height(12.dp)) }

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

            // Bottom: the trust line, then the ask bar as the lowest, most reachable element on screen.
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Local AI • Securely processed on-device", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = { pdfLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.AddCircle, contentDescription = stringResource(R.string.import_pdf), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask iQOO Recall…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                    Box(
                        Modifier.size(44.dp).background(AccentOrange, CircleShape).clickable { ask(query) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("↑", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryTile(cat: PhotoCategory, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
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

@Composable
private fun SuggestionCard(s: Suggestion, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(2.dp))
                Text(
                    "“${s.question}”",
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentCard(doc: DocumentEntity, onClick: () -> Unit) {
    Column(Modifier.width(128.dp).clickable(onClick = onClick)) {
        Thumbnail(doc, Modifier.fillMaxWidth().height(100.dp))
        Spacer(Modifier.height(6.dp))
        Text(doc.displayTitle(), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
        Text(
            DateUtils.getRelativeTimeSpanString(doc.capturedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun outcomeMessage(context: Context, outcome: IngestPipeline.Outcome?): String = when (outcome) {
    is IngestPipeline.Outcome.Saved -> context.getString(R.string.scan_saved, context.docTypeName(outcome.type))
    is IngestPipeline.Outcome.ExactDuplicate -> context.getString(R.string.scan_duplicate, LocalDate.ofEpochDay(outcome.existing.createdAt / 86_400_000).toString())
    is IngestPipeline.Outcome.NotADocument -> context.getString(R.string.scan_not_document)
    is IngestPipeline.Outcome.Failed -> context.getString(R.string.scan_failed, outcome.reason)
    null -> context.getString(R.string.scan_failed, "-")
}
