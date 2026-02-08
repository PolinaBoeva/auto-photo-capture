package com.example.autophotopose.ui

import android.content.Context
import android.util.Log
import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.autophotopose.ui.CameraButtons
import com.example.autophotopose.CameraViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import com.example.autophotopose.OverlayView

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    isCaptureActive: Boolean,
    bestScore: Float? = null,
    lastGalleryBitmap: Bitmap? = null,
    onCaptureClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ===== PreviewView один раз =====
    val previewView = remember { PreviewView(context) }

    val uiState by viewModel.uiState.collectAsState()

    // ===== Биндим камеру один раз =====
    LaunchedEffect(uiState.isFrontCamera) {
        viewModel.bindCamera(previewView = previewView, lifecycleOwner = lifecycleOwner)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ===== Camera Preview =====
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // ===== 2️⃣ Overlay (точки Mediapipe) =====
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                OverlayView(context).also {
                    viewModel.setOverlayView(it)
                }
            }
        )

        // ===== UI поверх камеры =====
        Column(
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            CameraButtons(
                isCaptureActive = isCaptureActive,
                bestScore = bestScore,
                lastGalleryBitmap = lastGalleryBitmap,
                onCaptureClick = onCaptureClick,
                onSwitchCameraClick = onSwitchCameraClick,
                onGalleryClick = onGalleryClick
            )

            // Показываем score
            bestScore?.let { score ->
                Text(
                    text = "Best score: ${"%.2f".format(score)}",
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                )
            }

            // Статус захвата
            Text(
                text = if (isCaptureActive) "Smart Capture ON" else "Smart Capture OFF",
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}
