package com.example.autophotopose.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.autophotopose.CameraViewModel
import com.example.autophotopose.OverlayView

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsState()
    val poseResults = viewModel.poseResults

    val previewView = remember { PreviewView(context) }
    val overlayView: OverlayView = remember { OverlayView(context) }

    // Bind camera on first composition or when switching front/back
    LaunchedEffect(uiState.isFrontCamera) {
        viewModel.bindCamera(previewView, lifecycleOwner)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ===== Camera Preview =====
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // ===== Overlay =====
        AndroidView(factory = { overlayView }, modifier = Modifier.fillMaxSize())

        // Update overlay when poseResults changes
        LaunchedEffect(poseResults) {
            poseResults?.let { result ->
                overlayView.setResults(
                    results = result.results,
                    imageHeight = result.inputImageHeight,
                    imageWidth = result.inputImageWidth,
                )
            }
        }

        // ===== UI Buttons =====
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = if (uiState.isCaptureActive) "Smart Capture ON" else "Smart Capture OFF")
            uiState.bestScore?.let { score ->
                Text(text = "Best score: ${"%.2f".format(score)}")
            }

            Spacer(modifier = Modifier.height(12.dp))

            CameraButtons(
                isCaptureActive = uiState.isCaptureActive,
                bestScore = uiState.bestScore,
                lastGalleryBitmap = uiState.lastGalleryBitmap,
                onCaptureClick = { viewModel.toggleCapture() },
                onSwitchCameraClick = { viewModel.switchCamera() },
                onGalleryClick = { /* TODO: открыть галерею */ },
            )
        }
    }
}
