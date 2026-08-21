package com.example.autophotopose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.autophotopose.ui.CameraScreen

class MainActivity : ComponentActivity() {
    private val cameraViewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cameraGrantedInitially = hasCameraPermission()

        setContent {
            var hasCameraPermission by remember { mutableStateOf(cameraGrantedInitially) }

            val permissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        hasCameraPermission = true
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Camera permission is required to use this app.",
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                    }
                }

            LaunchedEffect(Unit) {
                if (!hasCameraPermission) {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            if (hasCameraPermission) {
                CameraScreen(viewModel = cameraViewModel)
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
