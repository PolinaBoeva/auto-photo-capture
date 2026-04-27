package com.example.autophotopose

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.autophotopose.ui.AestheticPredictor
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

// ========================= DATA CLASS =========================

/**
 * Lightweight data class for landmark coordinates.
 */
data class Landmark(val x: Float, val y: Float)

class AutoCaptureProcessor(
    private val aestheticPredictor: AestheticPredictor,
    private val onCaptureTriggered: () -> Unit,
    private val config: Config = Config(),
) {
    companion object {
        private const val TAG = "AutoCapture"
    }

    data class Config(
        val minStableMs: Long = 300L,
        val poseChangeThreshold: Float = 0.6f,
        val stableVelocityThreshold: Float = 0.45f,
        val peakRatio: Float = 0.92f,
        val targetDelayMs: Long = 80L,
        val bufferWindowMs: Long = 1200L,
        val minBufferFrames: Int = 6,
        val recentFramesCount: Int = 12,
        val minValidFrames: Int = 3,
        val stabilityRatioThreshold: Float = 0.55f,
        val scoreImprovementFactor: Float = 1.05f,
        val highQualityThreshold: Float = 7.5f,
        val cooldownNormal: Long = 3200L,
        val cooldownImproved: Long = 1800L,
    )

    private data class AnalysisFrame(
        val timestamp: Long,
        val score: Float,
        val velocity: Float,
        val isBlur: Boolean,
        val landmarks: List<Landmark>,
    )

    // ================= STATE =================
    private val analysisBuffer = ArrayDeque<AnalysisFrame>(40)
    private var lastLandmarks: List<Landmark>? = null
    private var lastSavedScore = 5f
    private var lastTriggerTime = 0L
    private var isCapturing = false

    // ================= PUBLIC API =================
    val isReady: Boolean get() = !isCapturing

    /**
     * Processes a new frame with detected pose.
     * @return true if frame was accepted into buffer, false if rejected early
     */
    fun processFrame(
        bitmap: Bitmap,
        resultBundle: PoseLandmarkerHelper.ResultBundle,
        isBlurDetectorEnabled: Boolean = true,
    ): Boolean {
        if (isCapturing) return false

        // Validate bitmap before processing
        if (bitmap.isRecycled) {
            Log.w(TAG, "Skipping frame: bitmap is already recycled")
            return false
        }

        // 1. Pose Validation
        if (!isValidPose(resultBundle)) return false

        val landmarksList = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull() ?: return false
        val landmarks = landmarksList.map { Landmark(it.x(), it.y()) }

        // 2. Pose Change Detection
        val velocity = calculateVelocity(landmarks, lastLandmarks)
        if (velocity > config.poseChangeThreshold) {
            analysisBuffer.clear()
            lastLandmarks = landmarks
            Log.d(TAG, "New pose detected → buffer cleared")
            return false
        }

        // 3. ROI + Aesthetic Score
        val roiRect = getPersonRoi(bitmap, resultBundle) ?: return false

        val roiBitmap =
            try {
                Bitmap.createBitmap(
                    bitmap,
                    roiRect.left,
                    roiRect.top,
                    roiRect.width(),
                    roiRect.height(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create ROI bitmap", e)
                return false
            }

        val score = aestheticPredictor.predictAesthetic(roiBitmap)
        val blur = if (isBlurDetectorEnabled) isBlurred(roiBitmap) else false

        // Recycle immediately to save memory
        roiBitmap.recycle()

        // 4. Add to Buffer
        val now = System.currentTimeMillis()
        analysisBuffer.addLast(AnalysisFrame(now, score, velocity, blur, landmarks))
        trimBuffer(now)
        lastLandmarks = landmarks

        Log.d(
            TAG,
            "Frame buffered | score=${score.format(2)}, vel=${velocity.format(3)}, " +
                "blur=$blur, buffer=${analysisBuffer.size}",
        )

        // 5. Check Trigger
        if (shouldTrigger()) {
            onCaptureTriggered()
            return true
        }

        return true
    }

    fun notifyCaptureStarted() {
        isCapturing = true
        lastTriggerTime = System.currentTimeMillis()
        Log.d(TAG, "Capture started. Processor locked.")
    }

    fun notifyCaptureFinished(score: Float? = null) {
        isCapturing = false
        score?.let {
            lastSavedScore = it
            Log.d(TAG, "Capture finished. Last saved score: ${it.format(2)}")
        } ?: run {
            Log.d(TAG, "Capture finished (no score update).")
        }
    }

    fun reset() {
        analysisBuffer.clear()
        lastLandmarks = null
        isCapturing = false
        Log.d(TAG, "Processor reset.")
    }

    // ================= TRIGGER LOGIC =================
    private fun shouldTrigger(): Boolean {
        val now = System.currentTimeMillis()

        if (analysisBuffer.size < config.minBufferFrames) {
            return false
        }

        val recent = analysisBuffer.takeLast(config.recentFramesCount)
        if (recent.isEmpty()) return false

        val currentFrame = recent.last()

        // 1. Stability Check
        val stableCount = recent.count { it.velocity < config.stableVelocityThreshold }
        val oldestTimestamp = recent.firstOrNull()?.timestamp ?: now
        val windowDuration = now - oldestTimestamp
        val stableRatio = stableCount.toFloat() / recent.size

        val isStableEnough =
            (stableRatio >= config.stabilityRatioThreshold) &&
                (windowDuration >= config.minStableMs)

        if (!isStableEnough) {
            Log.d(TAG, "Trigger REJECTED: unstable (ratio=$stableRatio, duration=${windowDuration}ms)")
            return false
        }

        // 2. Current Frame Quality
        if (currentFrame.velocity > config.stableVelocityThreshold || currentFrame.isBlur) {
            Log.d(TAG, "Trigger REJECTED: current frame poor quality")
            return false
        }

        // 3. Valid Frames Count
        val validFrames = recent.filter { !it.isBlur && it.velocity < config.stableVelocityThreshold }
        if (validFrames.size < config.minValidFrames) {
            Log.d(TAG, "Trigger REJECTED: not enough valid frames (${validFrames.size})")
            return false
        }

        // 4. Peak Detection
        val maxScore = validFrames.maxOf { it.score }
        val prevScore = recent.getOrNull(recent.lastIndex - 1)?.score ?: 0f
        val isPeak =
            currentFrame.score >= maxScore * config.peakRatio &&
                currentFrame.score >= prevScore - 0.04f

        if (!isPeak) {
            Log.d(TAG, "Trigger REJECTED: not a peak (curr=${currentFrame.score.format(2)}, max=${maxScore.format(2)})")
            return false
        }

        // 5. Cooldown Check
        val isBetterThanLast = currentFrame.score > lastSavedScore * config.scoreImprovementFactor
        val isHighQuality = currentFrame.score > config.highQualityThreshold
        val cooldown =
            if (isBetterThanLast || isHighQuality) {
                config.cooldownImproved
            } else {
                config.cooldownNormal
            }
        val timeSinceLast = now - lastTriggerTime

        if (timeSinceLast < cooldown) {
            Log.d(TAG, "Trigger REJECTED: cooldown active (${timeSinceLast}ms < ${cooldown}ms)")
            return false
        }

        Log.d(
            TAG,
            "Trigger ACCEPTED | score=${currentFrame.score.format(2)}, " +
                "vel=${currentFrame.velocity.format(3)}, stable=$stableCount/${recent.size}",
        )
        return true
    }

    // ================= HELPERS =================
    private fun calculateVelocity(
        current: List<Landmark>,
        previous: List<Landmark>?,
    ): Float {
        if (previous == null || current.size != previous.size) return 1.0f

        // Key points for movement: Nose, Shoulders, Hips, Knees
        val keyIndices = listOf(0, 11, 12, 23, 24, 25, 26)

        // Normalize by shoulder width to make velocity scale-invariant
        val shoulderWidth =
            hypot(
                (current[12].x - current[11].x).toDouble(),
                (current[12].y - current[11].y).toDouble(),
            ).toFloat().coerceAtLeast(0.08f)

        var sum = 0f
        var count = 0
        for (i in keyIndices) {
            if (i >= current.size) continue
            val dx = (current[i].x - previous[i].x).toDouble()
            val dy = (current[i].y - previous[i].y).toDouble()
            // hypot returns Double, so we cast to Float
            sum += hypot(dx, dy).toFloat() / shoulderWidth
            count++
        }
        return if (count == 0) 1.0f else sum / count
    }

    private fun getPersonRoi(
        bitmap: Bitmap,
        resultBundle: PoseLandmarkerHelper.ResultBundle,
    ): Rect? {
        val landmarks = resultBundle.results.firstOrNull()?.landmarks() ?: return null

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        landmarks.forEach { landmarkList ->
            landmarkList.forEach { landmark ->
                val x = landmark.x() * bitmap.width
                val y = landmark.y() * bitmap.height
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }

        if (minX == Float.MAX_VALUE) return null

        val width = maxX - minX
        val height = maxY - minY
        val padX = width * 0.25f
        val padY = height * 0.35f

        return Rect(
            (minX - padX).toInt().coerceAtLeast(0),
            (minY - padY).toInt().coerceAtLeast(0),
            (maxX + padX).toInt().coerceAtMost(bitmap.width),
            (maxY + padY).toInt().coerceAtMost(bitmap.height),
        )
    }

    private fun isValidPose(resultBundle: PoseLandmarkerHelper.ResultBundle): Boolean {
        val landmarks = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull() ?: return false
        if (landmarks.size < 16) return false

        val reliablePoints =
            landmarks.count {
                it.visibility().orElse(0f) > 0.6f && it.presence().orElse(0f) > 0.5f
            }

        val xs = landmarks.map { it.x() }
        val ys = landmarks.map { it.y() }
        val area = (xs.max() - xs.min()) * (ys.max() - ys.min())
        val spread = (xs.max() - xs.min()) + (ys.max() - ys.min())

        return reliablePoints >= 14 && area > 0.06f && spread > 0.6f
    }

    /**
     * Simple blur detection using Laplacian variance via OpenCV.
     * Returns true if image is blurred.
     */
    private fun isBlurred(
        bitmap: Bitmap,
        threshold: Double = 100.0,
    ): Boolean {
        val mat = Mat()
        val grayMat = Mat()
        val laplacianMat = Mat()

        val mean = MatOfDouble()
        val stddev = MatOfDouble()

        try {
            // Convert Bitmap to Mat
            org.opencv.android.Utils.bitmapToMat(bitmap, mat)

            // Convert to grayscale
            Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGBA2GRAY)

            // Apply Laplacian operator
            Imgproc.Laplacian(grayMat, laplacianMat, CvType.CV_64F)

            // Calculate mean and standard deviation
            Core.meanStdDev(laplacianMat, mean, stddev)

            // Get variance (stddev^2) - MatOfDouble stores values in array via get()
            val stddevValue = stddev.get(0, 0)[0]
            val variance = stddevValue * stddevValue

            // If variance is below threshold, image is blurry
            return variance < threshold
        } catch (e: Exception) {
            Log.e(TAG, "Error in blur detection", e)
            return false // Assume not blurred on error to avoid blocking capture
        } finally {
            // Release OpenCV resources
            mat.release()
            grayMat.release()
            laplacianMat.release()
            mean.release()
            stddev.release()
        }
    }

    private fun trimBuffer(now: Long) {
        while (analysisBuffer.isNotEmpty() &&
            now - analysisBuffer.first().timestamp > config.bufferWindowMs
        ) {
            analysisBuffer.removeFirst()
        }
    }

    private fun Float.format(digits: Int): String = "%.${digits}f".format(this)
}
