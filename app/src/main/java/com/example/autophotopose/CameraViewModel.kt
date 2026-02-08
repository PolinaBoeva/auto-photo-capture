package com.example.autophotopose

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import com.example.autophotopose.ui.CameraUiState
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    // =======================
    // UI STATE
    // =======================

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState

    // =======================
    // CameraX
    // =======================

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var imageAnalysis: ImageAnalysis
    private lateinit var imageCapture: ImageCapture

    // =======================
    // Overlay
    // =======================

    private var overlayView: OverlayView? = null

    fun setOverlayView(view: OverlayView) {
        overlayView = view
    }

    // =======================
    // ML
    // =======================

    private val poseHelper: PoseLandmarkerHelper

    private val poseStabilityDetector = PoseStabilityDetector(
        bufferSize = 15,
        threshold = 0.02f,
        stableFramesNeeded = 7
    )

    private val aestheticPredictor = AestheticPredictor(application)

    // =======================
    // Best frames
    // =======================

    private data class ScoredBitmap(val bitmap: Bitmap, val score: Float)

    private val topFrames = mutableListOf<ScoredBitmap>()
    private val maxTopFrames = 3
    private var frameCounter = 0

    // =======================
    // Init
    // =======================

    init {
        poseHelper = PoseLandmarkerHelper(
            context = application,
            runningMode = RunningMode.LIVE_STREAM,
            poseLandmarkerHelperListener =
                object : PoseLandmarkerHelper.LandmarkerListener {

                    override fun onError(error: String, errorCode: Int) {
                        Log.e("PoseLandmarker", error)
                    }

                    override fun onResults(
                        resultBundle: PoseLandmarkerHelper.ResultBundle
                    ) {
                        // 1️⃣ Overlay (UI thread safe)
                        overlayView?.post {
                            overlayView?.setResults(
                                results = resultBundle.results,
                                imageHeight = resultBundle.inputImageHeight,
                                imageWidth = resultBundle.inputImageWidth
                            )
                        }

                        // 2️⃣ Smart capture logic
                        handlePoseResults(resultBundle)
                    }
                }
        )
    }

    // =======================
    // Camera binding
    // =======================

    fun bindCamera(
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner
    ) {
        if (!::imageCapture.isInitialized) createImageCapture()
        if (!::imageAnalysis.isInitialized) createImageAnalysis()

        val context = previewView.context
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val selector =
                if (uiState.value.isFrontCamera)
                    CameraSelector.DEFAULT_FRONT_CAMERA
                else
                    CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(context))
    }

    // =======================
    // CameraX helpers
    // =======================

    private fun createImageAnalysis(): ImageAnalysis {
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    poseHelper.detectLiveStream(
                        imageProxy,
                        isFrontCamera = uiState.value.isFrontCamera
                    )
                }
            }
        return imageAnalysis
    }

    private fun createImageCapture(): ImageCapture {
        imageCapture = ImageCapture.Builder().build()
        return imageCapture
    }

    // =======================
    // UI actions
    // =======================

    fun toggleCapture() {
        _uiState.value = _uiState.value.copy(
            isCaptureActive = !_uiState.value.isCaptureActive
        )
    }

    fun switchCamera() {
        _uiState.value = _uiState.value.copy(
            isFrontCamera = !_uiState.value.isFrontCamera
        )
    }

    // =======================
    // Pose + Aesthetic logic
    // =======================

    private fun handlePoseResults(
        resultBundle: PoseLandmarkerHelper.ResultBundle
    ) {
        if (!_uiState.value.isCaptureActive) return

        resultBundle.results.forEach { result ->
            val pose = result.landmarks()?.firstOrNull() ?: return@forEach
            val landmarks = pose.map { Landmark(it.x(), it.y()) }

            val frameBitmap = poseHelper.lastFrameBitmap ?: return@forEach

            val stableFrames =
                poseStabilityDetector.addFrame(frameBitmap, landmarks)
                    ?: return@forEach

            stableFrames.forEach { bmp ->
                frameCounter++
                if (frameCounter % 3 != 0) return@forEach

                val score = aestheticPredictor.predictAesthetic(bmp)

                topFrames.add(
                    ScoredBitmap(
                        bmp.copy(Bitmap.Config.ARGB_8888, true),
                        score
                    )
                )

                topFrames.sortByDescending { it.score }
                if (topFrames.size > maxTopFrames) {
                    topFrames.removeAt(topFrames.lastIndex)
                }

                _uiState.value = _uiState.value.copy(
                    bestScore = topFrames.firstOrNull()?.score
                )
            }
        }
    }

    // =======================
    // Gallery preview
    // =======================

    fun loadLastGalleryImage(context: Context) {
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                val bitmap =
                    if (Build.VERSION.SDK_INT >= 29) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(
                                context.contentResolver,
                                uri
                            )
                        )
                    } else {
                        MediaStore.Images.Media.getBitmap(
                            context.contentResolver,
                            uri
                        )
                    }

                _uiState.value = _uiState.value.copy(
                    lastGalleryBitmap = bitmap
                )
            }
        }
    }

    // =======================
    // Save frames
    // =======================

    fun saveTopFramesToGallery() {
        val resolver = getApplication<Application>().contentResolver

        topFrames.forEachIndexed { index, scored ->
            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "best_pose_${System.currentTimeMillis()}_$index.jpg"
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )

            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    scored.bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        95,
                        out
                    )
                }

                if (Build.VERSION.SDK_INT >= 29) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
            }
        }

        topFrames.clear()
    }

    // =======================
    // Cleanup
    // =======================

    override fun onCleared() {
        cameraExecutor.shutdown()
        poseHelper.clearPoseLandmarker()
        aestheticPredictor.close()
    }
}
