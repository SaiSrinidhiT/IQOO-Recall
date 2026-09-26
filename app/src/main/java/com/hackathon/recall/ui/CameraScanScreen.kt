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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.ingest.IngestPipeline
import com.hackathon.recall.model.DocType
import com.hackathon.recall.model.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * CameraX document capture. The photo is captured in memory, rotated upright, and sent through the pipeline.
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
    
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.camera_permission_needed), color = Color.White)
            }
            return@Box
        }
        
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Color.White.copy(alpha=0.1f), CircleShape).clickable { nav.popBackStack() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                
                Row(Modifier.background(Color.White.copy(alpha=0.1f), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Processed privately on this phone", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                
                Box(Modifier.size(36.dp).background(Color.White.copy(alpha=0.1f), CircleShape).clickable { flash = !flash }, contentAlignment = Alignment.Center) {
                    Icon(if (flash) Icons.Default.Warning else Icons.Default.Clear, contentDescription = "Flash", tint = Color.White, modifier = Modifier.size(18.dp))
                }
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
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                            }, ContextCompat.getMainExecutor(ctx))
                        }
                    },
                )
                Box(Modifier.fillMaxSize().padding(24.dp).border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), RoundedCornerShape(16.dp)))
                
                Box(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha=0.5f), RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(status ?: "Processing...", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Text("Aligning document…", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }
                
                if (!busy) {
                    Row(Modifier.align(Alignment.TopEnd).padding(12.dp).background(Color(0xFF4CAF50), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text("Auto-capture ready", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).background(Color.White.copy(alpha=0.1f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Face, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Gallery", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                
                Box(
                    Modifier.size(72.dp).border(4.dp, Color.White.copy(alpha=0.7f), CircleShape).background(Color.White, CircleShape).clickable(enabled = !busy) {
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
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).background(Color.White.copy(alpha=0.1f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.List, contentDescription = "Multi-page", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Multi-page", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
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
