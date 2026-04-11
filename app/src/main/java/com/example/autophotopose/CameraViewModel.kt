package com.example.autophotopose

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.autophotopose.ui.CameraUiState
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    // UI state
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState

    private val appContext = getApplication<Application>()

    private val aestheticPredictor = AestheticPredictor(appContext)

    // PoseLandmarker
    private val poseHelper: PoseLandmarkerHelper

    // CameraX
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var imageCapture: ImageCapture

    // Топовые кадры
    private val topFrames = mutableListOf<Pair<Bitmap, Float>>() // Pair<Bitmap, score>
    private val maxTopFrames = 3

    // Adaptive cooldown
    private var lastSavedTime = 0L
    private var lastSavedScore = 5f // начальное значение
    private val baseCooldown = 5000L // 5 секунд
    // Live pose results для Compose
    var poseResults by mutableStateOf<PoseLandmarkerHelper.ResultBundle?>(null)
        private set

    private fun isValidPose(resultBundle: PoseLandmarkerHelper.ResultBundle): Boolean {
        val result = resultBundle.results.firstOrNull() ?: return false
        val landmarks = result.landmarks()?.firstOrNull() ?: return false

        if (landmarks.size < 15) return false

        val reliablePoints = landmarks.count { landmark ->
            landmark.visibility().orElse(0f) > 0.65f &&
                landmark.presence().orElse(0f) > 0.55f
        }

        val xs = landmarks.map { it.x() }
        val ys = landmarks.map { it.y() }
        val width = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
        val height = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f)

        val isBigEnough = width > 0.22f && height > 0.30f

        return reliablePoints >= 16 && isBigEnough
    }

    private val poseStabilityDetector = PoseStabilityDetector().apply {
        onStablePose = { stableFrames ->
            val bestFrame = stableFrames.last()

            val isSharp = !isBlurred(bestFrame)
            if (isSharp) {
                evaluateAndStoreTopFrame(bestFrame)
            }
        }
    }



    private fun isBlurred(
        bitmap: Bitmap,
        threshold: Double = 100.0 // подбирается (пример: 80–150)
    ): Boolean {
        if (bitmap.width < 64 || bitmap.height < 64) return true

        var mat: org.opencv.core.Mat? = null
        var gray: org.opencv.core.Mat? = null
        var gradX: org.opencv.core.Mat? = null
        var gradY: org.opencv.core.Mat? = null
        var magnitude: org.opencv.core.Mat? = null

        try {
            val targetWidth = 320
            val targetHeight = (bitmap.height * (targetWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)

            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

            mat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(resizedBitmap, mat)

            // 🔹 grayscale
            gray = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(
                mat,
                gray,
                org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY
            )

            // 🔹 Sobel gradients
            gradX = org.opencv.core.Mat()
            gradY = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.Sobel(gray, gradX, org.opencv.core.CvType.CV_64F, 1, 0)
            org.opencv.imgproc.Imgproc.Sobel(gray, gradY, org.opencv.core.CvType.CV_64F, 0, 1)

            // 🔹 magnitude = sqrt(gx^2 + gy^2)
            magnitude = org.opencv.core.Mat()
            org.opencv.core.Core.magnitude(gradX, gradY, magnitude)

            val mean = org.opencv.core.Core.mean(magnitude).`val`[0]
            return mean < threshold

        } catch (e: Exception) {
            Log.e("BlurCheck", "Ошибка Tenengrad", e)
            return true
        } finally {
            mat?.release()
            gray?.release()
            gradX?.release()
            gradY?.release()
            magnitude?.release()
        }
    }

    init {
        poseHelper =
            PoseLandmarkerHelper(
                context = application,
                runningMode = RunningMode.LIVE_STREAM,
                poseLandmarkerHelperListener =
                    object : PoseLandmarkerHelper.LandmarkerListener {
                        override fun onError(error: String, errorCode: Int) {
                            Log.e("PoseLandmarker", error)
                        }

                        override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
                            poseResults = resultBundle

                            val hasPerson = isValidPose(resultBundle)

                            if (_uiState.value.isCaptureActive && hasPerson) {
                                val landmarksList = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull()
                                if (!landmarksList.isNullOrEmpty()) {
                                    val landmarks = landmarksList.map { Landmark(it.x(), it.y()) }
                                    poseHelper.lastFrameBitmap?.let { bitmap ->
                                        poseStabilityDetector.pushFrame(bitmap, landmarks)
                                    }
                                }
                            }
                        }
                    }
            )
    }

    // =========================
    // Camera binding
    // =========================
    fun bindCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        if (!::imageCapture.isInitialized) createImageCapture()
        if (!::imageAnalysis.isInitialized) createImageAnalysis()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            val selector =
                if (_uiState.value.isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture,
                imageAnalysis,
            )
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    private fun createImageAnalysis(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    poseHelper.detectLiveStream(
                        imageProxy,
                        isFrontCamera = _uiState.value.isFrontCamera,
                    )
                }
            }.also { imageAnalysis = it }
    }

    private fun createImageCapture(): ImageCapture {
        imageCapture =
            ImageCapture.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .setAllowedResolutionMode(
                            ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE,
                        )
                        .build(),
                )
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .build()

        return imageCapture
    }

    // =========================
    // UI actions
    // =========================
    fun toggleCapture() {
        _uiState.value =
            _uiState.value.copy(
                isCaptureActive = !_uiState.value.isCaptureActive,
            )
    }

    fun switchCamera() {
        _uiState.value =
            _uiState.value.copy(
                isFrontCamera = !_uiState.value.isFrontCamera,
            )
    }

    private fun getPersonRoi(
        bitmap: Bitmap,
        resultBundle: PoseLandmarkerHelper.ResultBundle?,
    ): android.graphics.Rect? {
        val results = resultBundle?.results ?: return null
        val firstResult = results.firstOrNull() ?: return null

        val landmarksList = firstResult.landmarks() ?: return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        landmarksList.forEach { landmarkList ->
            landmarkList.forEach { landmark ->
                val x = landmark.x() * bitmap.width
                val y = landmark.y() * bitmap.height

                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }

        // Если нет точек — пропускаем
        if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) return null

        val width = maxX - minX
        val height = maxY - minY
        val padX = width * 0.25f
        val padY = height * 0.35f

        val left = (minX - padX).toInt().coerceAtLeast(0)
        val top = (minY - padY).toInt().coerceAtLeast(0)
        val right = (maxX + padX).toInt().coerceAtMost(bitmap.width)
        val bottom = (maxY + padY).toInt().coerceAtMost(bitmap.height)

        return android.graphics.Rect(left, top, right, bottom)
    }

    private fun evaluateAndStoreTopFrame(bitmap: Bitmap) {
        val roiRect = getPersonRoi(bitmap, poseResults) ?: return
        val roiBitmap = Bitmap.createBitmap(bitmap, roiRect.left, roiRect.top, roiRect.width(), roiRect.height())
        val score = aestheticPredictor.predictAesthetic(roiBitmap)

        // 1️⃣ Добавляем в topFrames, если лучше существующих
        topFrames.add(Pair(roiBitmap, score))
        topFrames.sortByDescending { it.second }

        if (topFrames.size > maxTopFrames) {
            topFrames.removeAt(topFrames.lastIndex)
        }

        // 2️⃣ Проверяем adaptive cooldown
        val now = System.currentTimeMillis()
        val adaptiveCooldown = (baseCooldown / (score / lastSavedScore)).toLong().coerceAtLeast(1000L)

        if (now - lastSavedTime < adaptiveCooldown) return

        // 3️⃣ Сохраняем лучший кадр в галерею
        val topFrame = topFrames.first()
        saveBitmapToGallery(topFrame.first)

        lastSavedTime = now
        lastSavedScore = topFrame.second
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = "AutoPose_${System.currentTimeMillis()}.jpg"

                val contentValues =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(
                                MediaStore.Images.Media.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + "/AutoPose",
                            )
                        }
                    }

                val uri =
                    appContext.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues,
                    ) ?: return@launch

                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
        poseHelper.clearPoseLandmarker()
        aestheticPredictor.close()
    }
}
