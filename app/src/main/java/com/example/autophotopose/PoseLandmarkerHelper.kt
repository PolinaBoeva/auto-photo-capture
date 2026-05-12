package com.example.autophotopose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    var minPoseDetectionConfidence: Float = DEFAULT_POSE_DETECTION_CONFIDENCE,
    var minPoseTrackingConfidence: Float = DEFAULT_POSE_TRACKING_CONFIDENCE,
    var minPosePresenceConfidence: Float = DEFAULT_POSE_PRESENCE_CONFIDENCE,
    var currentModel: Int = MODEL_POSE_LANDMARKER_FULL,
    var currentDelegate: Int = DELEGATE_CPU,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    val poseLandmarkerHelperListener: LandmarkerListener? = null,
) {
    companion object {
        private const val TAG = "PoseLandmarker"

        const val DEFAULT_POSE_DETECTION_CONFIDENCE = 0.5f
        const val DEFAULT_POSE_TRACKING_CONFIDENCE = 0.5f
        const val DEFAULT_POSE_PRESENCE_CONFIDENCE = 0.5f

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1

        const val MODEL_POSE_LANDMARKER_FULL = 0
        const val MODEL_POSE_LANDMARKER_LITE = 1
        const val MODEL_POSE_LANDMARKER_HEAVY = 2
    }

    private var poseLandmarker: PoseLandmarker? = null

    /**
     * Last processed frame bitmap for use in AutoCaptureProcessor.
     */
    @Volatile
    var lastFrameBitmap: Bitmap? = null
        private set

    init {
        setupPoseLandmarker()
    }

    fun clearPoseLandmarker() {
        Log.d(TAG, "Closing PoseLandmarker")
        poseLandmarker?.close()
        poseLandmarker = null
        lastFrameBitmap = null
    }

    fun setupPoseLandmarker() {
        Log.d(TAG, "Setting up PoseLandmarker. Model: $currentModel, Delegate: $currentDelegate")

        val baseOptionBuilder = BaseOptions.builder()

        when (currentDelegate) {
            DELEGATE_CPU -> baseOptionBuilder.setDelegate(Delegate.CPU)
            DELEGATE_GPU -> baseOptionBuilder.setDelegate(Delegate.GPU)
            else -> {
                Log.w(TAG, "Unknown delegate ($currentDelegate), falling back to CPU")
                baseOptionBuilder.setDelegate(Delegate.CPU)
            }
        }

        val modelName =
            when (currentModel) {
                MODEL_POSE_LANDMARKER_LITE -> "pose_landmarker_lite.task"
                MODEL_POSE_LANDMARKER_HEAVY -> "pose_landmarker_heavy.task"
                else -> "pose_landmarker_full.task"
            }

        baseOptionBuilder.setModelAssetPath(modelName)

        try {
            val optionsBuilder =
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptionBuilder.build())
                    .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                    .setMinTrackingConfidence(minPoseTrackingConfidence)
                    .setMinPosePresenceConfidence(minPosePresenceConfidence)
                    .setRunningMode(runningMode)

            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }

            poseLandmarker =
                PoseLandmarker.createFromOptions(
                    context,
                    optionsBuilder.build(),
                )
            Log.d(TAG, "PoseLandmarker initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize PoseLandmarker", e)
            poseLandmarkerHelperListener?.onError("Initialization failed: ${e.message}")
        }
    }

    /**
     * Processes ImageProxy from CameraX.
     * Converts to Bitmap, applies rotation/mirror, and sends to MediaPipe.
     */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun detectLiveStream(
        imageProxy: ImageProxy,
        isFrontCamera: Boolean,
    ) {
        val startTime = SystemClock.uptimeMillis()

        // 1. Convert ImageProxy to Bitmap using KTX function
        val bitmapBuffer =
            createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888,
            )
        bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)

        // 2. Apply rotation and mirroring
        val matrix =
            Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    // Mirror horizontally around the center
                    postScale(-1f, 1f, bitmapBuffer.width / 2f, bitmapBuffer.height / 2f)
                }
            }

        val rotatedBitmap =
            Bitmap.createBitmap(
                bitmapBuffer,
                0,
                0,
                bitmapBuffer.width,
                bitmapBuffer.height,
                matrix,
                true,
            )

        // Recycle temporary buffer immediately
        bitmapBuffer.recycle()
        lastFrameBitmap = rotatedBitmap

        try {
            // 3. Create MPImage from Bitmap and send to MediaPipe
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            poseLandmarker?.detectAsync(mpImage, startTime)
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe processing failed", e)
        } finally {
            imageProxy.close()
        }

        val processingTime = SystemClock.uptimeMillis() - startTime
        if (processingTime > 50) {
            Log.w(TAG, "Slow frame pipeline: ${processingTime}ms")
        }
    }

    private fun returnLivestreamResult(
        result: PoseLandmarkerResult,
        input: MPImage,
    ) {
        val inferenceTime = SystemClock.uptimeMillis() - result.timestampMs()

        poseLandmarkerHelperListener?.onResults(
            ResultBundle(
                listOf(result),
                inferenceTime,
                input.height,
                input.width,
            ),
        )
    }

    private fun returnLivestreamError(error: RuntimeException) {
        Log.e(TAG, "MediaPipe error: ${error.message}")
        poseLandmarkerHelperListener?.onError(error.message ?: "Unknown MediaPipe error")
    }

    data class ResultBundle(
        val results: List<PoseLandmarkerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface LandmarkerListener {
        fun onError(
            error: String,
            errorCode: Int = 0,
        )

        fun onResults(resultBundle: ResultBundle)
    }
}
