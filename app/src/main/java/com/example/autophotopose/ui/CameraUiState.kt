package com.example.autophotopose.ui

import android.graphics.Bitmap

data class CameraUiState(
    val isFrontCamera: Boolean = false,
    val isCaptureActive: Boolean = false,
    val lastGalleryBitmap: Bitmap? = null,
)
