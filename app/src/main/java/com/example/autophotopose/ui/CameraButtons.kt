package com.example.autophotopose.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.autophotopose.R

@Composable
fun CameraButtons(
    isCaptureActive: Boolean,
    bestScore: Float?,
    lastGalleryBitmap: Bitmap?,
    onCaptureClick: () -> Unit,
    onSwitchCameraClick: () -> Unit,
    onGalleryClick: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // ===== Capture Button =====
        CaptureButton(
            isActive = isCaptureActive,
            onClick = onCaptureClick,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
        )

        // ===== Switch Camera =====
        FloatingActionButton(
            onClick = onSwitchCameraClick,
            modifier =
                Modifier
                    .size(88.dp)
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
            containerColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_cached),
                contentDescription = "Switch Camera",
                modifier = Modifier.size(72.dp),
            )
        }

        // ===== Gallery Button =====
        FloatingActionButton(
            onClick = onGalleryClick,
            modifier =
                Modifier
                    .size(88.dp)
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
            containerColor = Color.Transparent,
            elevation = FloatingActionButtonDefaults.elevation(0.dp),
        ) {
            if (lastGalleryBitmap != null) {
                Image(
                    bitmap = lastGalleryBitmap.asImageBitmap(),
                    contentDescription = "Last Photo",
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color.LightGray),
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
        modifier = modifier.size(80.dp),
        containerColor = Color.Transparent,
        elevation = FloatingActionButtonDefaults.elevation(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(if (isActive) Color(0xFF4CAF50) else Color(0xFFEEEEEE)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White),
            )
        }
    }
}
