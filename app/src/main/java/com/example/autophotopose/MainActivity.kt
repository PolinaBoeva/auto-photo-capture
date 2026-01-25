package com.example.autophotopose

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: OverlayView

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var imageCapture: ImageCapture

    private lateinit var poseHelper: PoseLandmarkerHelper
    private lateinit var poseStabilityDetector: PoseStabilityDetector

    private var isFrontCamera = false
    private var isCaptureActive = false // флаг включения «умного захвата» - пока не реализовано

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        val startCaptureBtn: Button = findViewById(R.id.start_capture_button)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ---- Инициализация детектора стабильности позы ----
        poseStabilityDetector = PoseStabilityDetector(
            bufferSize = 15,
            threshold = 0.02f,
            stableFramesNeeded = 7
        )

        // ---- Инициализация MediaPipe PoseLandmarker ----
        poseHelper = PoseLandmarkerHelper(
            context = this,
            runningMode = RunningMode.LIVE_STREAM,
            poseLandmarkerHelperListener = object :
                PoseLandmarkerHelper.LandmarkerListener {

                override fun onError(error: String, errorCode: Int) {
                    Log.e("PoseLandmarker", "Error: $error ($errorCode)")
                }

                override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
                    // ---- Overlay ----
                    runOnUiThread {
                        overlay.setResults(
                            resultBundle.results,
                            resultBundle.inputImageHeight,
                            resultBundle.inputImageWidth
                        )
                    }

                    if (!isCaptureActive) return

                    // ---- Обработка точек ----
                    resultBundle.results.forEachIndexed { poseIndex, result ->
                        val landmarksLists = result.landmarks() ?: return@forEachIndexed
                        val mainPose = landmarksLists.firstOrNull() ?: return@forEachIndexed

                        // ---- Логирование точек ----
                        mainPose.forEachIndexed { i, lm ->
                            val px = lm.x() * resultBundle.inputImageWidth
                            val py = lm.y() * resultBundle.inputImageHeight
                            val z = lm.z()
                            val presence = lm.presence()

                            Log.d(
                                "PosePoints",
                                "Pose $poseIndex - Point $i: x=${lm.x()} (px=$px), y=${lm.y()} (px=$py), z=$z, presence=$presence"
                            )
                        }

                        // ---- Преобразование в Landmark для детектора ----
                        val landmarksForDetector = mainPose.map { lm ->
                            Landmark(lm.x(), lm.y())
                        }

                        // ---- Получение последнего кадра ----
                        val frameBitmap = poseHelper.lastFrameBitmap ?: return@forEachIndexed

                        // ---- Добавление в детектор ----
                        val stableFrames = poseStabilityDetector.addFrame(frameBitmap, landmarksForDetector)

                        if (stableFrames != null) {
                            Log.d("PoseStability", "ПОЗА СТАБИЛЬНА (${stableFrames.size} кадров)")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Поза стабильна!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        )

        // ---- Кнопка запуска/остановки умного захвата ----
        startCaptureBtn.setOnClickListener {
            isCaptureActive = !isCaptureActive
            val msg = if (isCaptureActive) "Smart capture ON" else "Smart capture OFF"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", msg)
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---- Проверка разрешений ----
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, "Нужен доступ к камере", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    private fun allPermissionsGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    // ---- Запуск CameraX ----
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        poseHelper.detectLiveStream(
                            imageProxy,
                            isFrontCamera = isFrontCamera
                        )
                    }
                }

            val cameraSelector =
                if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraX", "Camera bind failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseHelper.clearPoseLandmarker()
    }
}
