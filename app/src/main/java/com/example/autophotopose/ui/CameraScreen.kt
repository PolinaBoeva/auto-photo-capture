package com.example.autophotopose.ui

import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

private const val TAG = "CameraScreen"

@Composable
fun CameraScreen(
    viewModel: CameraViewModel,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsState()
    val poseOverlay = viewModel.poseOverlay
    val captureTrigger by viewModel.captureTrigger.collectAsState()

    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }
    val overlayView = remember { OverlayView(context) }

    // Changing the camera type (front/main)
    LaunchedEffect(uiState.isFrontCamera) {
        Log.d(TAG, "Re-binding camera. Front: ${uiState.isFrontCamera}")
        viewModel.bindCamera(previewView, lifecycleOwner)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        // Overlay
        AndroidView(
            factory = { overlayView },
            modifier = Modifier.fillMaxSize(),
        )

        // Pose update
        LaunchedEffect(poseOverlay) {
            poseOverlay?.let { overlay ->
                overlayView.setResults(
                    poses = overlay.poses,
                    imageHeight = overlay.imageHeight,
                    imageWidth = overlay.imageWidth,
                )
            }
        }

        // Visual shooting signal
        LaunchedEffect(captureTrigger) {
            if (captureTrigger != 0) {
                Log.d(TAG, "Triggering capture flash")
                overlayView.triggerCaptureFlash()
            }
        }

        // Control panel
        ControlPanel(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            uiState = uiState,
            onCaptureClick = { viewModel.toggleCapture() },
            onSwitchCameraClick = { viewModel.switchCamera() },
            onGalleryClick = { openGallery(context) },
        )
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier = Modifier,
    uiState: CameraUiState,
    onCaptureClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CameraButtons(
            isCaptureActive = uiState.isCaptureActive,
            lastGalleryBitmap = uiState.lastGalleryBitmap,
            onCaptureClick = onCaptureClick,
            onSwitchCameraClick = onSwitchCameraClick,
            onGalleryClick = onGalleryClick,
        )
    }
}

/**
 * Opens the system gallery.
 */
private fun openGallery(context: android.content.Context) {
    try {
        // Try to open gallery with folder filter (works on some OEM galleries)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                type = "vnd.android.cursor.dir/image"
                putExtra("bucket", "AutoPose")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
            Log.d(TAG, "Opened gallery with bucket filter")
            return
        }

        // Fallback: standard image picker
        val galleryIntent =
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        if (galleryIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(galleryIntent)
            Log.d(TAG, "Opened default gallery picker")
        } else {
            Log.w(TAG, "No gallery app found")
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open gallery", e)
    }
}
