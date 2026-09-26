package com.hackathon.recall.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hackathon.recall.AppContainer
import com.hackathon.recall.R
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.MIN_TYPE_CONFIDENCE
import com.hackathon.recall.data.displayTitle
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.data.isTypeConfident
import com.hackathon.recall.data.type
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.ingest.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

val LocalContainer = compositionLocalOf<AppContainer> { error("AppContainer not provided") }

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 16.dp, bottom = 8.dp))
}

/**
 * Home/Vault category keys (also nav args for Routes.vaultCategory) and the doc types in each. A
 * document is grouped by its [effectiveType], so anything below [MIN_TYPE_CONFIDENCE] that the user
 * hasn't confirmed is filed under [OTHER]. The detected type itself is kept.
 */
object DocCategory {
    const val OTHER = "other"

    fun isConfident(doc: DocumentEntity): Boolean = doc.isTypeConfident()

    fun of(doc: DocumentEntity): String = of(doc.effectiveType().name)

    val TYPES: Map<String, Set<String>> = linkedMapOf(
        "identity" to setOf("AADHAAR", "PAN", "DRIVING_LICENCE", "PASSPORT", "VOTER_ID"),
        "income" to setOf("SALARY_SLIP", "BANK_STATEMENT", "EMPLOYMENT_LETTER", "ITR_FORM16", "LOAN_SANCTION_EMI"),
        "health" to setOf("HEALTH_ID_ABHA", "HEALTH_INSURANCE", "MEDICAL_REPORT", "HOSPITAL_BILL", "PRESCRIPTION"),
        "property" to setOf("PROPERTY_PAPER", "RENT_AGREEMENT", "VEHICLE_RC", "VEHICLE_INSURANCE", "UTILITY_BILL"),
    )

    fun of(docType: String): String = TYPES.entries.firstOrNull { docType in it.value }?.key ?: OTHER

    fun label(key: String): Int = when (key) {
        "identity" -> R.string.category_identity
        "income" -> R.string.category_income
        "health" -> R.string.category_health
        "property" -> R.string.category_property
        else -> R.string.category_other
    }
}

/** One document in a list: thumbnail, localized type, English title, expiry badge. */
@Composable
fun DocRow(doc: DocumentEntity, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(doc, Modifier.size(52.dp))
        Column(Modifier.weight(1f)) {
            Text(context.docTypeName(doc.effectiveType()), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(doc.displayTitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        doc.expiryOn?.let { ExpiryBadge(LocalDate.parse(it)) }
        trailing?.invoke()
    }
}

@Composable
fun ExpiryBadge(expiry: LocalDate) {
    val expired = expiry.isBefore(LocalDate.now())
    val bg = if (expired) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (expired) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        if (expired) stringResource(R.string.expired) else stringResource(R.string.expires_on, expiry.toString()),
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private val thumbCache = LruCache<Long, ImageBitmap>(64)

/** Decrypts and decodes a small preview; cached in memory only. */
@Composable
fun Thumbnail(doc: DocumentEntity, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val image by produceState(thumbCache.get(doc.id), doc.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = container.repository.readOriginal(doc)
                    val bmp: Bitmap = if (doc.mimeType == "application/pdf") ImageLoader.renderPdf(bytes, 256, 1).first() else ImageLoader.decode(bytes, 256)
                    bmp.asImageBitmap().also { thumbCache.put(doc.id, it) }
                }.getOrNull()
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
    }
}

/** Full-resolution preview for the detail screen (first page for PDFs). */
@Composable
fun Preview(doc: DocumentEntity, modifier: Modifier = Modifier) {
    val container = LocalContainer.current
    val image by produceState<ImageBitmap?>(null, doc.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = container.repository.readOriginal(doc)
                val bmp = if (doc.mimeType == "application/pdf") ImageLoader.renderPdf(bytes, 1400, 1).first() else ImageLoader.decode(bytes, 1400)
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth()) }
    }
}
