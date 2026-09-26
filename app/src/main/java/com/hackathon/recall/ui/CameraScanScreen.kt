package com.hackathon.recall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs

/** Real-time read on the preview feed: too dark, moving too much to hold a sharp shot, or steady and bright enough. */
private enum class Readiness { DARK, UNSTEADY, READY }

/**
 * CameraX document capture. The photo is captured in memory, rotated upright, and sent through the
 * pipeline. A low-rate frame analyzer reads the Y (luma) plane to drive the guide box's colour and
 * status text live off the actual preview — brightness and frame-to-frame motion, not a fixed corner/
 * edge detector (that would need OpenCV or ML Kit's Document Scanner, both out of scope here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScanScreen(nav: NavHostController, expectedType: String?) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val expected = DocType.parse(expectedType)

    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var readiness by remember { mutableStateOf(Readiness.DARK) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val analysis = remember {
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also { a ->
            a.setAnalyzer(analysisExecutor, FrameReadinessAnalyzer { readiness = it })
        }
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    fun runIngest(bytes: ByteArray, mime: String, kind: SourceKind, forceDocument: Boolean) {
        busy = true
        status = context.getString(R.string.scan_processing)
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                container.pipeline.ingest(
                    IngestPipeline.Source(bytes, mime, kind, null, System.currentTimeMillis(), expectedType = expected, forceDocument = forceDocument),
                )
            }
            busy = false
            status = outcomeMessage(context, outcome)
            if (outcome is IngestPipeline.Outcome.Saved && expected != null) nav.popBackStack()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            if (bytes == null) status = context.getString(R.string.scan_failed, "read error") else runIngest(bytes, context.contentResolver.getType(uri) ?: "image/*", SourceKind.GALLERY, forceDocument = true)
        }
    }

    LaunchedEffect(Unit) { if (!granted) cameraLauncher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.camera_permission_needed), color = Color.White)
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.1f), CircleShape).clickable { nav.popBackStack() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.back), tint = Color.White, modifier = Modifier.size(18.dp))
                }

                Row(Modifier.background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.scan_private_note), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Box(
                    Modifier.size(36.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)
                        .clickable { flash = !flash; capture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF },
                    contentAlignment = Alignment.Center,
                ) { FlashIcon(on = flash) }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).clip(RoundedCornerShape(24.dp))) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            val future = ProcessCameraProvider.getInstance(ctx)
                            future.addListener({
                                val provider = future.get()
                                val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                )

                val guideColor = when (readiness) {
                    Readiness.READY -> Color(0xFF4CAF50)
                    Readiness.UNSTEADY -> Color(0xFFFFC107)
                    Readiness.DARK -> Color.White.copy(alpha = 0.7f)
                }
                Box(Modifier.fillMaxSize().padding(24.dp).border(2.dp, guideColor, RoundedCornerShape(16.dp)))

                Box(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(status ?: stringResource(R.string.scan_processing), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Text(
                            stringResource(
                                when (readiness) {
                                    Readiness.DARK -> R.string.scan_hint_dark
                                    Readiness.UNSTEADY -> R.string.scan_hint_steady
                                    Readiness.READY -> R.string.scan_hint_ready
                                },
                            ),
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }

                if (!busy && readiness == Readiness.READY) {
                    Row(Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0xFF4CAF50), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.scan_ready_badge), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(44.dp).background(Color.White.copy(alpha = 0.1f), CircleShape)
                            .clickable(enabled = !busy) { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        contentAlignment = Alignment.Center,
                    ) { GalleryIcon() }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.scan_gallery), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Box(
                    Modifier.size(72.dp).border(4.dp, Color.White.copy(alpha = 0.7f), CircleShape).background(Color.White, CircleShape).clickable(enabled = !busy) {
                        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val jpeg = uprightJpeg(image)
                                image.close()
                                runIngest(jpeg, "image/jpeg", SourceKind.CAMERA, forceDocument = false)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                status = context.getString(R.string.scan_failed, exception.message ?: "camera error")
                            }
                        })
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).background(Color.White.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.List, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.scan_multi_page), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** A small lightning-bolt glyph so the flash toggle can't be mistaken for the Close (X) button next to it. */
@Composable
private fun FlashIcon(on: Boolean) {
    val color = if (on) Color(0xFFFFC107) else Color.White
    Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val bolt = Path().apply {
            moveTo(w * 0.58f, 0f)
            lineTo(w * 0.12f, h * 0.58f)
            lineTo(w * 0.44f, h * 0.58f)
            lineTo(w * 0.40f, h)
            lineTo(w * 0.90f, h * 0.40f)
            lineTo(w * 0.56f, h * 0.40f)
            close()
        }
        drawPath(bolt, color = color)
    }
}

@Composable
private fun GalleryIcon() {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.09f)
        drawRoundRect(color = Color.White, cornerRadius = CornerRadius(size.minDimension * 0.15f), style = stroke)
        val peak = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.72f)
            lineTo(size.width * 0.40f, size.height * 0.46f)
            lineTo(size.width * 0.58f, size.height * 0.64f)
            lineTo(size.width * 0.72f, size.height * 0.48f)
            lineTo(size.width * 0.86f, size.height * 0.72f)
            close()
        }
        clipRect(1f, 1f, size.width - 1f, size.height - 1f) { drawPath(peak, color = Color.White) }
        drawCircle(color = Color.White, radius = size.minDimension * 0.09f, center = Offset(size.width * 0.32f, size.height * 0.32f))
    }
}

private fun uprightJpeg(image: ImageProxy): ByteArray {
    val buffer = image.planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val degrees = image.imageInfo.rotationDegrees
    if (degrees == 0) return bytes
    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    return ByteArrayOutputStream().use { out ->
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
        out.toByteArray()
    }
}

/**
 * Reads only the Y (luma) plane, downsampled to a small grid (cheap: no bitmap allocation, no OCR/ML
 * model), to turn two real signals — average brightness and frame-to-frame change — into a readiness
 * state. This is a brightness/motion proxy, not document edge or corner detection.
 */
private class FrameReadinessAnalyzer(private val onResult: (Readiness) -> Unit) : ImageAnalysis.Analyzer {
    private var previous: FloatArray? = null

    override fun analyze(proxy: ImageProxy) {
        try {
            val plane = proxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = proxy.width
            val height = proxy.height
            val samples = FloatArray(GRID_W * GRID_H)
            for (gy in 0 until GRID_H) {
                val y = ((gy + 0.5f) / GRID_H * height).toInt().coerceIn(0, height - 1)
                for (gx in 0 until GRID_W) {
                    val x = ((gx + 0.5f) / GRID_W * width).toInt().coerceIn(0, width - 1)
                    val idx = y * rowStride + x * pixelStride
                    samples[gy * GRID_W + gx] = if (idx < buffer.limit()) (buffer.get(idx).toInt() and 0xFF).toFloat() else 0f
                }
            }
            val mean = samples.average().toFloat()
            val prev = previous
            val motion = if (prev != null) samples.indices.sumOf { abs(samples[it] - prev[it]).toDouble() }.toFloat() / samples.size else 0f
            previous = samples
            onResult(
                when {
                    mean < DARK_THRESHOLD -> Readiness.DARK
                    motion > MOTION_THRESHOLD -> Readiness.UNSTEADY
                    else -> Readiness.READY
                },
            )
        } finally {
            proxy.close()
        }
    }

    private companion object {
        const val GRID_W = 16
        const val GRID_H = 22
        const val DARK_THRESHOLD = 45f
        const val MOTION_THRESHOLD = 5.5f
    }
}
