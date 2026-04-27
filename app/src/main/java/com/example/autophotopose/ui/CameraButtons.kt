package com.example.autophotopose.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.autophotopose.R

// ========================= CONSTANTS =========================
private object UiConstants {
    // Button sizes
    val PRIMARY_BUTTON_SIZE = 80.dp
    val SECONDARY_BUTTON_SIZE = 88.dp
    val INNER_RING_SIZE = 72.dp
    val CENTER_DOT_SIZE = 56.dp

    // Spacing
    val BOTTOM_PADDING_PRIMARY = 32.dp
    val BOTTOM_PADDING_SECONDARY = 24.dp

    // Colors
    val CAPTURE_ACTIVE_COLOR = Color(0xFF4CAF50)
    val CAPTURE_INACTIVE_COLOR = Color(0xFFEEEEEE)
}

@Composable
fun CameraButtons(
    isCaptureActive: Boolean,
    lastGalleryBitmap: Bitmap?,
    onCaptureClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // ===== Control Panel (Center Bottom) =====
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = UiConstants.BOTTOM_PADDING_PRIMARY),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CaptureButton(
                isActive = isCaptureActive,
                onClick = onCaptureClick,
                modifier =
                    Modifier.semantics {
                        contentDescription = if (isCaptureActive) "Stop auto capture" else "Start auto capture"
                    },
            )
        }

        // ===== Switch Camera Button (Right) =====
        FloatingActionButton(
            onClick = onSwitchCameraClick,
            modifier =
                Modifier
                    .size(UiConstants.SECONDARY_BUTTON_SIZE)
                    .align(Alignment.BottomEnd)
                    .padding(UiConstants.BOTTOM_PADDING_SECONDARY)
                    .semantics { contentDescription = "Switch camera" },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cached),
                contentDescription = null,
                modifier = Modifier.size(UiConstants.INNER_RING_SIZE),
            )
        }

        // ===== Gallery Button (Left) =====
        Box(
            modifier =
                Modifier
                    .size(UiConstants.SECONDARY_BUTTON_SIZE)
                    .align(Alignment.BottomStart)
                    .padding(UiConstants.BOTTOM_PADDING_SECONDARY)
                    .clip(CircleShape)
                    .clickable(
                        onClick = onGalleryClick,
                        indication = ripple(bounded = true),
                        interactionSource = remember { MutableInteractionSource() },
                    )
                    .semantics { contentDescription = "Open gallery" },
            contentAlignment = Alignment.Center,
        ) {
            if (lastGalleryBitmap != null) {
                val imageBitmap = remember(lastGalleryBitmap) { lastGalleryBitmap.asImageBitmap() }

                Image(
                    bitmap = imageBitmap,
                    contentDescription = "Last captured photo preview",
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

@Composable
fun CaptureButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier =
            modifier
                .size(UiConstants.PRIMARY_BUTTON_SIZE)
                .semantics { contentDescription = if (isActive) "Stop auto capture" else "Start auto capture" },
        containerColor = Color.Transparent,
        elevation = FloatingActionButtonDefaults.elevation(0.dp),
        shape = CircleShape,
    ) {
        Box(
            modifier =
                Modifier
                    .size(UiConstants.INNER_RING_SIZE)
                    .clip(CircleShape)
                    .background(
                        if (isActive) {
                            UiConstants.CAPTURE_ACTIVE_COLOR
                        } else {
                            UiConstants.CAPTURE_INACTIVE_COLOR
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(UiConstants.CENTER_DOT_SIZE)
                        .clip(CircleShape)
                        .background(Color.White),
            )
        }
    }
}
