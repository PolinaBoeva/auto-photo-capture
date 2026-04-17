package com.example.autophotopose

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.example.autophotopose.ui.CameraUiState
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.camera.core.ExperimentalZeroShutterLag
import android.graphics.BitmapFactory
import android.graphics.Matrix

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    // ========================= UI =========================
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState
    private val appContext = getApplication<Application>()

    // ========================= ML =========================
    private val aestheticPredictor = AestheticPredictor(appContext)
    private val poseHelper: PoseLandmarkerHelper

    // ========================= CameraX =========================
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var imageCapture: ImageCapture

    // ========================= BUFFER =========================
    private val analysisBuffer = ArrayDeque<AnalysisFrame>(40)
    private var lastLandmarks: List<Landmark>? = null
    private data class AnalysisFrame(
        val timestamp: Long,
        val score: Float,
        val velocity: Float,
        val isBlur: Boolean,
        val landmarks: List<Landmark>
    )

    // ========================= STATE =========================
    private var lastSavedTime = 0L
    private var lastSavedScore = 5f
    private var isCapturing = false
    private var lastTriggerTime = 0L

    // ========================= TUNING =========================
    private val minStableMs = 300L
    private val poseChangeThreshold = 0.6f        // повысили
    private val stableVelocityThreshold = 0.45f   // главный фикс
    private val peakRatio = 0.92f
    private val targetDelayMs = 80L
    private val bufferWindowMs = 1200L             // чуть больше окна
    // ========================= OUTPUT =========================
    var poseResults by mutableStateOf<PoseLandmarkerHelper.ResultBundle?>(null)
        private set

    private fun isBlurred(bitmap: Bitmap, threshold: Double = 100.0): Boolean {
        Log.d("BLUR", "Blur check SKIPPED → returning false (safe stub)")
        return false
    }

    // ========================= INIT =========================
    init {
        poseHelper = PoseLandmarkerHelper(
            context = application,
            runningMode = RunningMode.LIVE_STREAM,
            poseLandmarkerHelperListener = object : PoseLandmarkerHelper.LandmarkerListener {
                override fun onError(error: String, errorCode: Int) {
                    Log.e("PoseLandmarker", error)
                }

                override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
                    Log.d("POSE", "onResults received | hasPose=${resultBundle.results.isNotEmpty()}")
                    poseResults = resultBundle
                    if (!_uiState.value.isCaptureActive) {
                        Log.d("POSE", "Capture inactive → skip")
                        return
                    }

                    if (!isValidPose(resultBundle)) {
                        Log.d("POSE", "isValidPose() = false → skip")
                        return
                    }

                    val landmarksList = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull() ?: return
                    val landmarks = landmarksList.map { Landmark(it.x(), it.y()) }
                    val bitmap = poseHelper.lastFrameBitmap
                    if (bitmap == null) {
                        Log.w("POSE", "lastFrameBitmap is null → cannot process")
                        return
                    }
                    Log.d("POSE", "Valid pose detected | landmarks=${landmarks.size}, bitmap=${bitmap.width}x${bitmap.height}")


                    val velocity = calculateVelocity(landmarks, lastLandmarks)

                    Log.d("POSE", "velocity=${velocity.format(3)} | threshold=$poseChangeThreshold")

                    if (velocity > poseChangeThreshold) {
                        analysisBuffer.clear()
                        Log.d("POSE", "NEW POSE detected → buffer cleared")
                        lastLandmarks = landmarks
                        return
                    }

                    val roiRect = getPersonRoi(bitmap, resultBundle)

                    if (roiRect == null) {
                        Log.w("POSE", "getPersonRoi returned null")
                        return
                    }

                    val roiBitmap = Bitmap.createBitmap(
                        bitmap, roiRect.left, roiRect.top, roiRect.width(), roiRect.height()
                    )

                    val score = aestheticPredictor.predictAesthetic(roiBitmap)
                    Log.d("POSE", "ROI created | score=${score.format(2)}")
                    val blur = isBlurred(roiBitmap)
                    roiBitmap.recycle()

                    val now = System.currentTimeMillis()
                    analysisBuffer.addLast(
                        AnalysisFrame(now, score, velocity, blur, landmarks)
                    )

                    trimBuffer(now)

                    if (shouldTrigger()) {
                        triggerCapture()
                    }

                    Log.d("BUFFER", "size=${analysisBuffer.size}, score=$score, vel=$velocity")
                }
            }
        )
    }

    // ========================= CAMERA =========================
    fun bindCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (!::imageCapture.isInitialized) createImageCapture()
        if (!::imageAnalysis.isInitialized) createImageAnalysis()

        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
            val selector = if (_uiState.value.isFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, imageAnalysis)
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    private fun createImageAnalysis(): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(cameraExecutor) { proxy ->
                poseHelper.detectLiveStream(proxy, _uiState.value.isFrontCamera)
            }}.also { imageAnalysis = it }
    }

    @androidx.annotation.OptIn(ExperimentalZeroShutterLag::class)
    private fun createImageCapture(): ImageCapture {
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
            .setJpegQuality(100)
            .build()
        return imageCapture
    }

    // ========================= TRIGGER LOGIC =========================
    private fun shouldTrigger(): Boolean {
        val now = System.currentTimeMillis()

        if (analysisBuffer.size < 6 || isCapturing) {
            Log.d("TRIGGER", "REJECTED: buffer=${analysisBuffer.size}, isCapturing=$isCapturing")
            return false
        }

        val recent = analysisBuffer.takeLast(12)
        if (recent.isEmpty()) {
            Log.d("TRIGGER", "REJECTED: recent is empty")
            return false
        }

        val currentFrame = recent.last()

        Log.d(
            "TRIGGER",
            "START | buffer=${analysisBuffer.size}, recent=${recent.size}, " +
                "vel=${currentFrame.velocity.format(3)}, blur=${currentFrame.isBlur}, score=${currentFrame.score.format(2)}"
        )

// 1. Стабильность окна
        val stableCount = recent.count { it.velocity < stableVelocityThreshold }
        val oldestTimestamp = recent.firstOrNull()?.timestamp ?: now
        val windowDuration = now - oldestTimestamp
        val stableRatio = stableCount.toFloat() / recent.size

        val isStableEnough = (stableRatio >= 0.55f) && (windowDuration >= minStableMs)

        Log.d("TRIGGER", "STABILITY | stable=$stableCount/${recent.size} (${stableRatio.format(2)}) duration=${windowDuration}ms | threshold=$stableVelocityThreshold | ok=$isStableEnough")
        if (!isStableEnough) {
            Log.d("TRIGGER", "REJECTED: not stable enough")
            return false
        }

        // 2. Текущий кадр
        val badVelocity = currentFrame.velocity > stableVelocityThreshold
        val isBlur = currentFrame.isBlur

        Log.d(
            "TRIGGER",
            "CURRENT FRAME | vel=${currentFrame.velocity.format(3)} " +
                "blur=$isBlur badVelocity=$badVelocity"
        )

        if (badVelocity || isBlur) {
            Log.d("TRIGGER", "REJECTED: current frame not good")
            return false
        }

        // 3. Валидные кадры
        val validFrames = recent.filter {
            !it.isBlur && it.velocity < stableVelocityThreshold
        }

        Log.d("TRIGGER", "VALID FRAMES | ${validFrames.size}/12")

        if (validFrames.size < 3) {
            Log.d("TRIGGER", "REJECTED: not enough valid frames")
            return false
        }

        // 4. Peak detection
        val maxScore = validFrames.maxOf { it.score }
        val prevScore = recent.getOrNull(recent.lastIndex - 1)?.score ?: 0f

        val isPeak =
            currentFrame.score >= maxScore * peakRatio &&
                currentFrame.score >= prevScore - 0.04f

        Log.d(
            "TRIGGER",
            "PEAK | current=${currentFrame.score.format(2)} " +
                "max=${maxScore.format(2)} prev=${prevScore.format(2)} " +
                "ok=$isPeak"
        )

        if (!isPeak) {
            Log.d("TRIGGER", "REJECTED: not peak")
            return false
        }

        // 5. Cooldown
        val isBetterThanLast = currentFrame.score > lastSavedScore * 1.05f
        val isHighQuality = currentFrame.score > 7.5f

        val cooldown = if (isBetterThanLast || isHighQuality) 1800L else 3200L
        val timeSinceLast = now - lastTriggerTime

        Log.d(
            "TRIGGER",
            "COOLDOWN | since=$timeSinceLast ms required=$cooldown ms " +
                "better=$isBetterThanLast high=$isHighQuality"
        )

        if (timeSinceLast < cooldown) {
            Log.d("TRIGGER", "REJECTED: cooldown active")
            return false
        }

        Log.d(
            "TRIGGER",
            "ACCEPTED 🎯 | score=${currentFrame.score.format(2)} " +
                "vel=${currentFrame.velocity.format(3)}"
        )

        return true
    }

    fun Float.format(digits: Int): String = "%.${digits}f".format(this)
    fun Double.format(digits: Int): String = "%.${digits}f".format(this)

    private fun triggerCapture() {
        if (isCapturing) {
            Log.d("CAPTURE", "Already capturing → skip")
            return
        }

        Log.d("CAPTURE", "=== TRIGGERED === Starting capture with delay=${targetDelayMs}ms")

        isCapturing = true
        lastTriggerTime = System.currentTimeMillis()

        viewModelScope.launch {
            delay(targetDelayMs)
            Log.d("CAPTURE", "Delay finished → calling takePicture()")
            imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d("CAPTURE", "onCaptureSuccess | ${image.width}x${image.height}")

                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            // Прямое сохранение JPEG
                            val jpegBytes = ByteArray(image.planes[0].buffer.remaining())
                            image.planes[0].buffer.get(jpegBytes)

                            val rotation = image.imageInfo.rotationDegrees

                            val bitmapRaw = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                                ?: run {
                                    Log.e("CAPTURE", "Failed to decode JPEG bytes")
                                    return@launch
                                }

                            val matrix = Matrix().apply {
                                postRotate(rotation.toFloat())
                            }

                            val bitmap = Bitmap.createBitmap(
                                bitmapRaw,
                                0,
                                0,
                                bitmapRaw.width,
                                bitmapRaw.height,
                                matrix,
                                true
                            )

                            Log.d("CAPTURE", "JPEG decoded → ${bitmap.width}x${bitmap.height}")
                            saveBitmapToGallery(bitmap)

                            lastSavedTime = System.currentTimeMillis()
                            lastSavedScore = analysisBuffer.lastOrNull()?.score ?: lastSavedScore

                            Log.d("CAPTURE", "Photo saved successfully | score=${lastSavedScore.format(2)}")

                        } catch (e: Exception) {
                            Log.e("CAPTURE", "Error saving photo", e)
                        } finally {
                            image.close()
                            isCapturing = false
                            Log.d("CAPTURE", "Capture finished, isCapturing=false")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CAPTURE", "ZSL Capture FAILED", exception)
                    isCapturing = false
                }
            }
            )
        }
    }

    private fun processFullRes(fullBitmap: Bitmap) {
        Log.d("CAPTURE", "processFullRes | bitmap=${fullBitmap.width}x${fullBitmap.height}")
        saveBitmapToGallery(fullBitmap)
        lastSavedTime = System.currentTimeMillis()
        lastSavedScore = analysisBuffer.lastOrNull()?.score ?: lastSavedScore
        Log.d("CAPTURE", "Photo saved | new lastSavedScore=${lastSavedScore.format(2)}")
    }

    // ========================= HELPERS =========================
    private fun calculateVelocity(
        current: List<Landmark>,
        previous: List<Landmark>?
    ): Float {
        if (previous == null || current.size != previous.size) {
            return 1.0f                     // первый кадр = сильное движение
        }

        // Ключевые стабильные точки тела
        val keyIndices = listOf(0, 11, 12, 23, 24, 25, 26)

        // Нормализация по ширине плеч (самый надёжный способ)
        val shoulderWidth = hypot(
            current[12].x - current[11].x,
            current[12].y - current[11].y
        ).coerceAtLeast(0.08f)

        var sum = 0f
        var count = 0

        for (i in keyIndices) {
            if (i >= current.size) continue

            val dx = current[i].x - previous[i].x
            val dy = current[i].y - previous[i].y
            sum += hypot(dx, dy) / shoulderWidth
            count++
        }

        return if (count == 0) 1.0f else sum / count
    }

    private fun trimBuffer(now: Long) {
        while (analysisBuffer.isNotEmpty() && now - analysisBuffer.first().timestamp > bufferWindowMs) {
            analysisBuffer.removeFirst()
        }
    }

    // ========================= UI =========================
    fun toggleCapture() {
        _uiState.value = _uiState.value.copy(isCaptureActive = !_uiState.value.isCaptureActive)
    }

    fun switchCamera() {
        _uiState.value = _uiState.value.copy(isFrontCamera = !_uiState.value.isFrontCamera)
    }

    // ========================= SAVE =========================
    private fun saveBitmapToGallery(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = "AutoPose_${System.currentTimeMillis()}.jpg"
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AutoPose")
                    }
                }
                val uri = appContext.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                ) ?: return@launch

                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ========================= REQUIRED FUNCTIONS =========================
    private fun getPersonRoi(bitmap: Bitmap, resultBundle: PoseLandmarkerHelper.ResultBundle?): Rect? {
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

        if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) return null

        val width = maxX - minX
        val height = maxY - minY
        val padX = width * 0.25f
        val padY = height * 0.35f

        val left = (minX - padX).toInt().coerceAtLeast(0)
        val top = (minY - padY).toInt().coerceAtLeast(0)
        val right = (maxX + padX).toInt().coerceAtMost(bitmap.width)
        val bottom = (maxY + padY).toInt().coerceAtMost(bitmap.height)

        return Rect(left, top, right, bottom)
    }

    private fun isValidPose(resultBundle: PoseLandmarkerHelper.ResultBundle): Boolean {
        val result = resultBundle.results.firstOrNull() ?: return false
        val landmarks = result.landmarks()?.firstOrNull() ?: return false

        if (landmarks.size < 16) return false

        val reliablePoints = landmarks.count {
            it.visibility().orElse(0f) > 0.6f &&
                it.presence().orElse(0f) > 0.5f
        }

        val xs = landmarks.map { it.x() }
        val ys = landmarks.map { it.y() }

        val width = (xs.max() - xs.min())
        val height = (ys.max() - ys.min())
        val area = width * height

        val spread = width + height

        val isBigEnough = area > 0.06f
        val isNotCollapsed = spread > 0.6f

        return reliablePoints >= 14 && isBigEnough && isNotCollapsed
    }


    // ========================= CLEANUP =========================
    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
        poseHelper.clearPoseLandmarker()
        aestheticPredictor.close()
    }
}
