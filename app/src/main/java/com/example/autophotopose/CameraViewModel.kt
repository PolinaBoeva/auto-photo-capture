package com.example.autophotopose

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.example.autophotopose.metrics.PerformanceMetricsCollector
import com.example.autophotopose.ui.AestheticPredictor
import com.example.autophotopose.ui.CameraUiState
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "CameraVM"
        private const val FOLDER_NAME = "AutoPose"
        private const val PREVIEW_SIZE = 300
        private const val JPEG_QUALITY_HIGH = 100
        private const val JPEG_QUALITY_SAVE = 95
        private val ANALYSIS_RESOLUTION = Size(1280, 960)
    }

    private var currentAnalysisWidth: Int = 0
    private var currentAnalysisHeight: Int = 0

    // ========================= UI STATE =========================
    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState

    private val _captureTrigger = MutableStateFlow(0)
    val captureTrigger: StateFlow<Int> = _captureTrigger

    var poseResults by mutableStateOf<PoseLandmarkerHelper.ResultBundle?>(null)
        private set

    private val appContext = getApplication<Application>()

    // ========================= ML & PROCESSING =========================
    private val aestheticPredictor = AestheticPredictor(appContext)
    private lateinit var poseHelper: PoseLandmarkerHelper
    private lateinit var captureProcessor: AutoCaptureProcessor

    // ========================= METRICS =========================
    private var metricsCollector: PerformanceMetricsCollector? = null

    // ========================= CAMERA X =========================
    private val cameraExecutor: ExecutorService =
        Executors.newSingleThreadExecutor {
            Thread(it, "CameraAnalysis").apply { isDaemon = true }
        }

    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var imageCapture: ImageCapture

    // Camera reference for focus control
    private var currentCamera: Camera? = null

    // Focus components
    private var meteringPointFactory: SurfaceOrientedMeteringPointFactory? = null
    private var focusController: PersonFocusController? = null

    // ========================= INITIALIZATION =========================
    init {
        Log.d(TAG, "Initializing ViewModel")
        initializeMLComponents()
        loadLastPhotoFromGallery()
    }

    private fun initializeMLComponents() {
        poseHelper =
            PoseLandmarkerHelper(
                context = appContext,
                runningMode = RunningMode.LIVE_STREAM,
                poseLandmarkerHelperListener =
                    object : PoseLandmarkerHelper.LandmarkerListener {
                        override fun onError(error: String, errorCode: Int) {
                            Log.e(TAG, "Pose Landmarker error [$errorCode]: $error")
                        }

                        override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
                            handlePoseResult(resultBundle)
                        }
                    },
            )

        metricsCollector = PerformanceMetricsCollector(getApplication())
        Log.d("MetricsDebug", "MetricsCollector created: ${metricsCollector != null}")
        Log.d("MetricsDebug", "File path: ${metricsCollector?.getFilePath()}")

        captureProcessor =
            AutoCaptureProcessor(
                aestheticPredictor = aestheticPredictor,
                onCaptureTriggered = { triggerCapture() },
                focusController = null, // Will be set via setFocusController() after bind
                metricsCollector = metricsCollector,
                )
    }

    // ========================= CAMERA BINDING =========================
    fun bindCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner,
    ) {
        Log.d(TAG, "Binding camera to lifecycle owner")

        if (!::imageCapture.isInitialized) createImageCapture()
        if (!::imageAnalysis.isInitialized) createImageAnalysis()

        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            val provider = future.get()

            val preview =
                Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                }

            val cameraSelector =
                if (_uiState.value.isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

            try {
                provider.unbindAll()
                currentCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )

                // Initialize focus components
                initializeFocusComponents()

                Log.d(TAG, "Camera bound successfully. Front: ${_uiState.value.isFrontCamera}")
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    /**
     * Initializes focus control components after camera is bound.
     */
    private fun initializeFocusComponents() {
        val camera = currentCamera ?: return

        meteringPointFactory = SurfaceOrientedMeteringPointFactory(
            ANALYSIS_RESOLUTION.width.toFloat(),
            ANALYSIS_RESOLUTION.height.toFloat()
        )

        focusController = PersonFocusController(
            cameraControl = camera.cameraControl,
            meteringPointFactory = meteringPointFactory!!
        )

        captureProcessor.setFocusController(focusController)

        Log.d(TAG, "Focus components initialized")
    }

    private fun createImageAnalysis(): ImageAnalysis {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    ANALYSIS_RESOLUTION,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        return ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                imageAnalysis = analysis
                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    currentAnalysisWidth = proxy.width
                    currentAnalysisHeight = proxy.height

                    poseHelper.detectLiveStream(proxy, _uiState.value.isFrontCamera)
                }
            }
    }

    @androidx.annotation.OptIn(ExperimentalZeroShutterLag::class)
    private fun createImageCapture(): ImageCapture {
        val captureMode =
            if (_uiState.value.isFrontCamera) {
                ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            } else {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
            }

        imageCapture =
            ImageCapture.Builder()
                .setCaptureMode(captureMode)
                .setJpegQuality(JPEG_QUALITY_HIGH)
                .build()

        Log.d(TAG, "ImageCapture created. Mode: $captureMode")
        return imageCapture
    }

    // ========================= LOGIC HANDLERS =========================
    private fun handlePoseResult(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        poseResults = resultBundle

        if (!_uiState.value.isCaptureActive) return

        val bitmap = poseHelper.lastFrameBitmap
        if (bitmap == null) {
            Log.w(TAG, "Skipping frame: lastFrameBitmap is null")
            return
        }

        captureProcessor.processFrame(
            bitmap = bitmap,
            resultBundle = resultBundle,
            imageAnalysisWidth = ANALYSIS_RESOLUTION.width,
            imageAnalysisHeight = ANALYSIS_RESOLUTION.height
        )
    }

    private fun triggerCapture() {
        if (!captureProcessor.isReady) {
            Log.d(TAG, "Capture skipped: Processor not ready")
            return
        }

        // val delayMs = AutoCaptureProcessor.Config().targetDelayMs
        // Log.d(TAG, "=== CAPTURE TRIGGERED === (Delay: ${delayMs}ms)")
        Log.d(TAG, "=== CAPTURE TRIGGERED ===")
        captureProcessor.notifyCaptureStarted()
        _captureTrigger.value++

        viewModelScope.launch {
            // delay(delayMs)
            takePicture()
        }
    }

    private fun takePicture() {
        if (!::imageCapture.isInitialized) {
            Log.e(TAG, "Cannot take picture: ImageCapture not initialized")
            return
        }

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    Log.d(TAG, "Picture captured successfully. Processing...")
                    viewModelScope.launch(Dispatchers.IO) {
                        processAndSaveImage(image)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    captureProcessor.notifyCaptureFinished()
                }
            },
        )
    }

    private fun processAndSaveImage(image: ImageProxy) {
        try {
            val jpegBytes = ByteArray(image.planes[0].buffer.remaining())
            image.planes[0].buffer.get(jpegBytes)

            val rotation = image.imageInfo.rotationDegrees

            val bitmapRaw =
                BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                    ?: throw IllegalStateException("Failed to decode JPEG bytes")

            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())

            // Mirror for front camera
            if (_uiState.value.isFrontCamera) {
                matrix.postScale(-1f, 1f)
            }

            val bitmap =
                Bitmap.createBitmap(
                    bitmapRaw,
                    0,
                    0,
                    bitmapRaw.width,
                    bitmapRaw.height,
                    matrix,
                    true,
                )

            saveBitmapToGallery(bitmap)
            captureProcessor.notifyCaptureFinished()
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            captureProcessor.notifyCaptureFinished()
        } finally {
            image.close()
        }
    }

    // ========================= STORAGE =========================
    private fun loadLastPhotoFromGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val projection = arrayOf(MediaStore.Images.Media._ID)
                val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf("Pictures/$FOLDER_NAME/")
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                val cursor =
                    appContext.contentResolver.query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder,
                    )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        val imageUri =
                            android.content.ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id,
                            )
                        val thumbnail = loadThumbnailSafely(imageUri, PREVIEW_SIZE)

                        if (thumbnail != null) {
                            _uiState.value = _uiState.value.copy(lastGalleryBitmap = thumbnail)
                            Log.d(TAG, "Last photo loaded from gallery: ${thumbnail.width}x${thumbnail.height}")
                        } else {
                            Log.w(TAG, "Failed to load thumbnail: result is null")
                        }
                    } else {
                        Log.d(TAG, "Gallery is empty or no photos in folder")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load last photo", e)
            }
        }
    }

    private fun loadThumbnailSafely(uri: android.net.Uri, targetSize: Int): Bitmap? {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    appContext.contentResolver.loadThumbnail(
                        uri,
                        Size(targetSize, targetSize),
                        null,
                    )
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    val source =
                        android.graphics.ImageDecoder.createSource(
                            appContext.contentResolver,
                            uri,
                        )
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.setTargetSampleSize(
                            calculateSampleSize(info.size.width, info.size.height, targetSize),
                        )
                    }
                }
                else -> {
                    val options =
                        BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                            appContext.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it, null, this)
                            }
                            inSampleSize = calculateSampleSize(outWidth, outHeight, targetSize)
                            inJustDecodeBounds = false
                            inPreferredConfig = Bitmap.Config.RGB_565
                        }
                    appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream, null, options)
                        if (bitmap != null && (bitmap.width != targetSize || bitmap.height != targetSize)) {
                            val scaled = bitmap.scale(targetSize, targetSize, false)
                            if (scaled != bitmap) bitmap.recycle()
                            scaled
                        } else {
                            bitmap
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading thumbnail from $uri", e)
            null
        }
    }

    private fun calculateSampleSize(originalWidth: Int, originalHeight: Int, targetSize: Int): Int {
        var sampleSize = 1
        if (originalHeight > targetSize || originalWidth > targetSize) {
            val halfHeight = originalHeight / 2
            val halfWidth = originalWidth / 2
            while (halfHeight / sampleSize >= targetSize && halfWidth / sampleSize >= targetSize) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        try {
            val filename = "AutoPose_${System.currentTimeMillis()}.jpg"
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/$FOLDER_NAME",
                        )
                    }
                }

            val uri =
                appContext.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues,
                ) ?: throw IllegalStateException("Failed to create MediaStore entry")

            appContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY_SAVE, outputStream)
                outputStream.flush()
            }

            val previewBitmap = bitmap.scale(PREVIEW_SIZE, PREVIEW_SIZE, false)
            _uiState.value = _uiState.value.copy(lastGalleryBitmap = previewBitmap)

            Log.d(TAG, "Photo saved successfully: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap to gallery", e)
        }
    }

    // ========================= USER ACTIONS =========================
    fun toggleCapture() {
        val newState = !_uiState.value.isCaptureActive
        _uiState.value = _uiState.value.copy(isCaptureActive = newState)

        if (!newState) {
            Log.d(TAG, "Capture stopped. Resetting processor.")
            captureProcessor.reset()
        } else {
            Log.d(TAG, "Capture started")
        }
    }

    fun switchCamera() {
        val newFrontState = !_uiState.value.isFrontCamera
        Log.d(TAG, "Switching camera. Front: $newFrontState")

        _uiState.value = _uiState.value.copy(isFrontCamera = newFrontState)
        captureProcessor.reset()
        createImageCapture()
    }

    // ========================= CLEANUP =========================
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared. Shutting down executor and ML helpers.")
        cameraExecutor.shutdown()
        poseHelper.clearPoseLandmarker()
        aestheticPredictor.close()
        metricsCollector?.close()
        currentCamera = null
        focusController?.reset()
    }
}
