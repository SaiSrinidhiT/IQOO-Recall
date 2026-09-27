package com.hackathon.recall.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.data.PhotoRow
import com.hackathon.recall.model.PhotoCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How each gallery category is shown: name and a glyph (the core icon set has no food or receipt icons). */
object PhotoCategoryUi {
    @StringRes
    fun label(c: PhotoCategory): Int = when (c) {
        PhotoCategory.SCREENSHOT -> R.string.photo_cat_screenshot
        PhotoCategory.SELFIE -> R.string.photo_cat_selfie
        PhotoCategory.PEOPLE -> R.string.photo_cat_people
        PhotoCategory.FOOD -> R.string.photo_cat_food
        PhotoCategory.PLACES -> R.string.photo_cat_places
        PhotoCategory.BILLS -> R.string.photo_cat_bills
        PhotoCategory.DOCUMENTS -> R.string.photo_cat_documents
        PhotoCategory.OTHER -> R.string.photo_cat_other
    }

    fun glyph(c: PhotoCategory): String = when (c) {
        PhotoCategory.SCREENSHOT -> "📱"
        PhotoCategory.SELFIE -> "🤳"
        PhotoCategory.PEOPLE -> "👥"
        PhotoCategory.FOOD -> "🍽️"
        PhotoCategory.PLACES -> "✈️"
        PhotoCategory.BILLS -> "🧾"
        PhotoCategory.DOCUMENTS -> "🪪"
        PhotoCategory.OTHER -> "🖼️"
    }

    /** Tiles on Home, in this order. Documents opens the vault; Other isn't a tile. */
    val HOME = listOf(
        PhotoCategory.SCREENSHOT, PhotoCategory.SELFIE, PhotoCategory.PEOPLE, PhotoCategory.FOOD,
        PhotoCategory.PLACES, PhotoCategory.BILLS, PhotoCategory.DOCUMENTS,
    )
}

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")

/** "12 – 15 Mar 2026" for a trip's first and last photo. */
fun tripDates(from: Long, to: Long): String {
    val zone = ZoneId.systemDefault()
    val a = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
    val b = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
    return if (a == b) DAY.format(a) else "${DAY.format(a)} – ${DAY.format(b)}"
}

/** Opens a gallery photo in the phone's own viewer; the app keeps no copy of non-document photos. */
fun openPhoto(context: Context, uri: String) {
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, R.string.photo_open_failed, Toast.LENGTH_SHORT).show()
    }
}

private val photoThumbs = LruCache<String, ImageBitmap>(200)

/** MediaStore's own cached thumbnail: a few ms, no full decode. */
@Composable
fun PhotoThumb(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val image by produceState(photoThumbs.get(uri), uri) {
        if (value == null) value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(320, 320), null).asImageBitmap() }
                .getOrNull()?.also { photoThumbs.put(uri, it) }
        }
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(nav: NavHostController, categoryKey: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val category = PhotoCategory.fromDb(categoryKey) ?: PhotoCategory.OTHER
    val dao = container.database.photos()
    val photos by remember(category) { dao.observeCategory(category.db) }.collectAsState(emptyList())
    val trips by remember(category) { if (category == PhotoCategory.PLACES) dao.observeTrips() else flowOf(emptyList()) }.collectAsState(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(PhotoCategoryUi.label(category))) },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) } },
            )
        },
    ) { padding ->
        // Trips & places: each trip under its own dated header (any category, since a trip holds food and
        // people too), then scenic photos taken near home.
        val byTrip = trips.groupBy { it.tripId }.values.sortedByDescending { t -> t.maxOf { it.takenAt } }
        val tripUris = trips.mapTo(HashSet()) { it.uri }
        val rest = photos.filter { it.uri !in tripUris }
        if (byTrip.isEmpty() && rest.isEmpty()) {
            Text(stringResource(R.string.photos_empty), modifier = Modifier.padding(padding).padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            byTrip.forEach { trip ->
                val sorted = trip.sortedBy { it.takenAt }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // "Ongole · 12 – 15 Mar 2026 · 45 photos": the town most of the trip's photos were taken near.
                    val place = trip.mapNotNull { it.place }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                    val dates = tripDates(sorted.first().takenAt, sorted.last().takenAt)
                    Header(listOfNotNull(place, dates, pluralStringResource(R.plurals.trip_photos, trip.size, trip.size)).joinToString(" · "))
                }
                items(sorted, key = { "t" + it.uri }) { p -> Cell(p) { openPhoto(context, p.uri) } }
            }
            if (byTrip.isNotEmpty() && rest.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header(stringResource(R.string.places_not_trip)) }
            }
            items(rest, key = { it.uri }) { p -> Cell(p) { openPhoto(context, p.uri) } }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun Cell(p: PhotoRow, onClick: () -> Unit) {
    PhotoThumb(p.uri, Modifier.aspectRatio(1f).clickable(onClick = onClick))
}
