package com.hackathon.recall.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * CameraX document capture (brief F2). The photo is captured in memory (never written unencrypted to
 * storage), rotated upright, and sent through the same pipeline as gallery images. Opened from a
 * checklist's "Scan", [expectedType] hints the classifier; the checklist updates live afterwards.
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
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    val title = expected?.let { stringResource(R.string.camera_expected, context.docTypeName(it)) } ?: stringResource(R.string.camera_title)
    Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) } }) }) { padding ->
        if (!granted) {
            Text(stringResource(R.string.camera_permission_needed), modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    PreviewView(ctx).also { view ->
                        val future = ProcessCameraProvider.getInstance(ctx)
                        future.addListener({
                            val provider = future.get()
                            val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
            )
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        busy = true
                        status = context.getString(R.string.scan_processing)
                        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val jpeg = uprightJpeg(image)
                                image.close()
                                scope.launch {
                                    val outcome = withContext(Dispatchers.Default) {
                                        container.pipeline.ingest(
                                            IngestPipeline.Source(jpeg, "image/jpeg", SourceKind.CAMERA, null, System.currentTimeMillis(), expectedType = expected),
                                        )
                                    }
                                    busy = false
                                    status = outcomeMessage(context, outcome)
                                    if (outcome is IngestPipeline.Outcome.Saved && expected != null) nav.popBackStack()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                status = context.getString(R.string.scan_failed, exception.message ?: "camera error")
                            }
                        })
                    }) { Text(stringResource(R.string.action_capture)) }
                    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

/** JPEG bytes rotated so the text is upright (in-memory capture does not apply rotation itself). */
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
