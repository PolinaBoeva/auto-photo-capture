package com.example.autophotopose

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.example.autophotopose.core.TimeProvider
import com.example.autophotopose.ui.AestheticPredictor
import kotlin.math.hypot

data class Landmark(val x: Float, val y: Float)

data class AnalysisFrame(
    val timestamp: Long,
    val score: Float,
    val velocity: Float,
    val landmarks: List<Landmark>,
)

class AutoCaptureProcessor(
    private val timeProvider: TimeProvider,
    private val aestheticPredictor: AestheticPredictor,
    private val onCaptureTriggered: () -> Unit,
    private var focusController: PersonFocusController? = null,
    private val config: Config = Config(),
) {
    companion object {
        private const val TAG = "AutoCapture"
    }

    data class Config(
        val minStableMs: Long = 300L,
        val poseChangeThreshold: Float = 0.75f,
        val stableVelocityThreshold: Float = 0.28f,
        val peakRatio: Float = 0.92f,
        val targetDelayMs: Long = 80L,
        val bufferWindowMs: Long = 1200L,
        val minBufferFrames: Int = 8,
        val recentFramesCount: Int = 12,
        val minValidFrames: Int = 3,
        val stabilityRatioThreshold: Float = 0.8f,
        val scoreImprovementFactor: Float = 1.05f,
        val highQualityThreshold: Float = 7.5f,
        val cooldownNormal: Long = 3200L,
        val cooldownImproved: Long = 1800L,
        val continuousStableMs: Long = 250L,
        val continuousStableVelocityThreshold: Float = 0.10f,
        val finalFrameVelocityThreshold: Float = 0.10f,
        val minConsecutiveStableFrames: Int = 4,
        val freezeWindowFrames: Int = 4,
        val freezeAvgThreshold: Float = 0.035f,
        val freezeMaxThreshold: Float = 0.065f,
        val maxAllowedSpikes: Int = 1,
    )

    private val analysisBuffer = ArrayDeque<AnalysisFrame>(40)
    private var lastLandmarks: List<Landmark>? = null
    private var lastSavedScore = 5f
    private var lastTriggerTime = 0L
    private var isCapturing = false
    private var stabilityWindowStartMs: Long = 0L
    private var consecutiveStableFrames: Int = 0
    private var cachedRoi: Rect? = null
    private var roiNormalizedCenter = PointF(0.5f, 0.5f)
    private var shotsInSession: Int = 0
    private var avgFrameIntervalMs: Float = 33f
    private var lastFrameTimestamp: Long = 0L

    val isReady: Boolean get() = !isCapturing

    fun processFrame(
        bitmap: Bitmap,
        resultBundle: PoseLandmarkerHelper.ResultBundle,
        imageAnalysisWidth: Int = 0,
        imageAnalysisHeight: Int = 0,
    ): Boolean {
        if (bitmap.isRecycled) {
            Log.w(TAG, "Skipping frame: bitmap is already recycled")
            return false
        }

        if (isCapturing) return false

        if (!isValidPose(resultBundle)) return false

        val landmarksList = resultBundle.results.firstOrNull()?.landmarks()?.firstOrNull() ?: return false
        val landmarks = landmarksList.map { Landmark(it.x(), it.y()) }

        val velocity = calculateVelocity(landmarks, lastLandmarks)
        val isPoseChanged = velocity > config.poseChangeThreshold

        if (isPoseChanged) {
            analysisBuffer.clear()
            lastLandmarks = landmarks
            shotsInSession = 0
            Log.d(TAG, "New pose detected, buffer cleared")
            val newRoi = getPersonRoi(bitmap, resultBundle)
            if (newRoi != null) {
                cachedRoi = newRoi
                roiNormalizedCenter.x = newRoi.centerX().toFloat() / bitmap.width
                roiNormalizedCenter.y = newRoi.centerY().toFloat() / bitmap.height

                focusController?.onRoiCenterChanged(
                    normCenterX = roiNormalizedCenter.x,
                    normCenterY = roiNormalizedCenter.y,
                    imageWidth = imageAnalysisWidth,
                    imageHeight = imageAnalysisHeight,
                )
            }
            return false
        }

        val roiRect = cachedRoi ?: getPersonRoi(bitmap, resultBundle) ?: return false

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

        val now = timeProvider.currentTimeMillis()
        analysisBuffer.addLast(AnalysisFrame(now, score, velocity, landmarks))
        updateFrameIntervalEstimate(now)
        trimBuffer(now)
        lastLandmarks = landmarks

        Log.d(
            TAG,
            "Frame buffered | score=${score.format(2)}, vel=${velocity.format(3)}, buffer=${analysisBuffer.size}",
        )

        updateStabilityWindow(velocity, now)

        if (shouldTrigger()) {
            onCaptureTriggered()
            return true
        }

        return false
    }

    private fun updateStabilityWindow(
        velocity: Float,
        now: Long,
    ) {
        val stableThreshold = config.continuousStableVelocityThreshold
        val resetThreshold = stableThreshold * 1.6f

        val isStable = velocity < stableThreshold
        val isUnstable = velocity > resetThreshold

        if (isUnstable) {
            stabilityWindowStartMs = 0L
            consecutiveStableFrames = 0
            shotsInSession = 0
            Log.d(TAG, "Stability window HARD RESET (vel=${velocity.format(3)} > ${resetThreshold.format(2)})")
        } else if (isStable) {
            if (consecutiveStableFrames == 0) {
                stabilityWindowStartMs = now
                Log.d(TAG, "Stability window STARTED at $now (vel=${velocity.format(3)})")
            }
            consecutiveStableFrames++
        }

        if (consecutiveStableFrames > 0) {
            val duration = now - stabilityWindowStartMs
            Log.d(TAG, "Stability window: $consecutiveStableFrames frames, ${duration}ms continuous")
        }
    }

    fun notifyCaptureStarted() {
        isCapturing = true
        lastTriggerTime = timeProvider.currentTimeMillis()
        Log.d(TAG, "Capture started. Processor locked.")
    }

    fun notifyCaptureFinished(score: Float? = null) {
        isCapturing = false
        stabilityWindowStartMs = 0L
        consecutiveStableFrames = 0
        Log.d(TAG, "Stability window reset after capture")

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
        stabilityWindowStartMs = 0L
        consecutiveStableFrames = 0
        shotsInSession = 0
        cachedRoi = null
        roiNormalizedCenter.set(0.5f, 0.5f)
        focusController?.reset()
        avgFrameIntervalMs = 100f
        lastFrameTimestamp = 0L
        Log.d(TAG, "Processor reset.")
    }

    private fun shouldTrigger(): Boolean {
        val now = timeProvider.currentTimeMillis()

        if (analysisBuffer.size < config.minBufferFrames) {
            return false
        }

        val recent = analysisBuffer.takeLast(config.recentFramesCount)
        if (recent.isEmpty()) return false

        val currentFrame = recent.last()

        val stabilityDuration =
            if (stabilityWindowStartMs > 0L) {
                now - stabilityWindowStartMs
            } else {
                0L
            }

        val requiredStableFrames =
            ((config.continuousStableMs / avgFrameIntervalMs.coerceAtLeast(30f)) + 0.5f)
                .toInt()
                .coerceIn(3, 12)

        val isContinuouslyStable =
            stabilityDuration >= config.continuousStableMs &&
                consecutiveStableFrames >= requiredStableFrames

        if (!isContinuouslyStable) {
            Log.d(
                TAG,
                "Trigger REJECTED: continuous stability not met " +
                    "(duration=${stabilityDuration}ms < ${config.continuousStableMs}ms, " +
                    "frames=$consecutiveStableFrames < $requiredStableFrames)",
            )
            return false
        }

        val freezeFrames = analysisBuffer.takeLast(config.freezeWindowFrames)
        if (freezeFrames.size >= config.freezeWindowFrames) {
            val avgVel = freezeFrames.map { it.velocity }.average().toFloat()
            val maxVel = freezeFrames.maxOf { it.velocity }
            val spikes = freezeFrames.count { it.velocity > config.freezeAvgThreshold }

            if (avgVel > config.freezeAvgThreshold ||
                maxVel > config.freezeMaxThreshold ||
                spikes > config.maxAllowedSpikes
            ) {
                Log.d(TAG, "Freeze window REJECTED | avg=${avgVel.format(3)}, max=${maxVel.format(3)}, spikes=$spikes")
                return false
            }
        }

        if (currentFrame.velocity > config.finalFrameVelocityThreshold) {
            Log.d(TAG, "Trigger REJECTED: final frame velocity too high (${currentFrame.velocity.format(3)})")
            return false
        }

        val validFrames = recent.filter { it.velocity < config.stableVelocityThreshold }
        if (validFrames.size < config.minValidFrames) {
            Log.d(TAG, "Trigger REJECTED: not enough valid frames (${validFrames.size})")
            return false
        }

        val maxScore = validFrames.maxOf { it.score }
        val prevScore = recent.getOrNull(recent.lastIndex - 1)?.score ?: 0f

        val isPeak =
            currentFrame.score >= maxScore * config.peakRatio &&
                currentFrame.score >= prevScore - 0.04f

        val allowNearPeak = currentFrame.score >= maxScore * 0.88f

        val minAcceptableScore = kotlin.math.max(3.5f, lastSavedScore * 0.97f)
        val allowRelative = currentFrame.score >= minAcceptableScore

        if (!isPeak && !allowNearPeak && !allowRelative) {
            Log.d(
                TAG,
                "Trigger REJECTED: not peak/near-peak/relative " +
                    "(curr=${currentFrame.score.format(2)}, max=${maxScore.format(2)}, min=$minAcceptableScore)",
            )
            return false
        }

        val timeSinceLast = now - lastTriggerTime

        val isBetterThanLast = currentFrame.score > lastSavedScore * config.scoreImprovementFactor
        val isHighQuality = currentFrame.score > config.highQualityThreshold
        val baseCooldown = if (isBetterThanLast || isHighQuality) config.cooldownImproved else config.cooldownNormal

        val adaptiveCooldown =
            when {
                currentFrame.velocity < 0.04f -> 800L
                currentFrame.velocity < 0.08f -> 1200L
                currentFrame.velocity < 0.15f -> 2000L
                else -> baseCooldown
            }.coerceAtMost(baseCooldown)

        if (timeSinceLast > 5000L) {
            shotsInSession = 0
        }

        if (timeSinceLast < adaptiveCooldown || shotsInSession >= 4) {
            Log.d(
                TAG,
                "Trigger REJECTED: cooldown (${timeSinceLast}ms < ${adaptiveCooldown}ms) " +
                    "or session limit ($shotsInSession/4)",
            )
            return false
        }

        shotsInSession++

        Log.d(
            TAG,
            "Trigger ACCEPTED | score=${currentFrame.score.format(2)}, " +
                "vel=${currentFrame.velocity.format(3)}, " +
                "stable_window=${stabilityDuration}ms/$consecutiveStableFrames frames, " +
                "cooldown=${adaptiveCooldown}ms, session=$shotsInSession/4",
        )
        return true
    }

    fun setFocusController(controller: PersonFocusController?) {
        this.focusController = controller
        Log.d(TAG, "Focus controller ${if (controller != null) "attached" else "detached"}")
    }

    private fun calculateVelocity(
        current: List<Landmark>,
        previous: List<Landmark>?,
    ): Float {
        if (previous == null || current.size != previous.size) return 1.0f

        val shoulderWidth =
            hypot(
                (current[12].x - current[11].x).toDouble(),
                (current[12].y - current[11].y).toDouble(),
            ).toFloat().coerceAtLeast(0.08f)

        fun dist(i: Int): Float {
            val dx = (current[i].x - previous[i].x).toDouble()
            val dy = (current[i].y - previous[i].y).toDouble()
            return hypot(dx, dy).toFloat() / shoulderWidth
        }

        val core = listOf(0, 11, 12, 23, 24)
        val secondary = listOf(13, 14, 25, 26)
        val extremities = listOf(15, 16, 27, 28)

        val coreVel = core.map { dist(it) }.average().toFloat()
        val secondaryVel = secondary.map { dist(it) }.average().toFloat()
        val extremitiesVel = extremities.map { dist(it) }.average().toFloat()

        val avg = coreVel * 0.6f + secondaryVel * 0.3f + extremitiesVel * 0.1f
        val maxCore = core.maxOf { dist(it) }

        return maxOf(avg, maxCore * 0.9f)
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

    private fun updateFrameIntervalEstimate(now: Long) {
        if (lastFrameTimestamp > 0L) {
            val interval = now - lastFrameTimestamp
            if (interval in 16..200) {
                avgFrameIntervalMs = avgFrameIntervalMs * 0.8f + interval * 0.2f
            }
        }
        lastFrameTimestamp = now
    }

    private fun trimBuffer(now: Long) {
        while (analysisBuffer.isNotEmpty() &&
            now - analysisBuffer.first().timestamp > config.bufferWindowMs
        ) {
            analysisBuffer.removeFirst()
        }
    }

    private fun Float.format(digits: Int): String = "%.${digits}f".format(this)

    fun getLastAnalysisFrame() = analysisBuffer.lastOrNull()
}
