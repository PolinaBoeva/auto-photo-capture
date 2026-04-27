package com.example.autophotopose

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.autophotopose.ui.CameraScreen

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    // Modern delegate for ViewModel initialization
    private val cameraViewModel: CameraViewModel by viewModels()

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Camera permission granted")
            } else {
                Log.w(TAG, "Camera permission denied. Closing app.")
                Toast.makeText(this, "Camera permission is required to use this app.", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Initializing MainActivity")

        if (!hasCameraPermission()) {
            Log.d(TAG, "Requesting camera permission")
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            Log.d(TAG, "Camera permission already granted")
        }

        setContent {
            CameraScreen(viewModel = cameraViewModel)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
