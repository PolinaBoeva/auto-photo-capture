package com.example.autophotopose

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.example.autophotopose.ui.AestheticPredictor
import kotlin.math.hypot

// ========================= DATA CLASS =========================

/**
 * Lightweight data class for landmark coordinates.
 */
data class Landmark(val x: Float, val y: Float)

class AutoCaptureProcessor(
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
        val peakRatio: Float = 0.85f,
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
        val continuousStableMs: Long = 200L,
        val continuousStableVelocityThreshold: Float = 0.10f,
        val finalFrameVelocityThreshold: Float = 0.10f,
        val minConsecutiveStableFrames: Int = 4,
        val freezeWindowFrames: Int = 4,
        val freezeAvgThreshold: Float = 0.16f,
        val freezeMaxThreshold: Float = 0.22f,
        val maxAllowedSpikes: Int = 4,
        val landmarkSmoothingAlpha: Float = 0.3f,
        val farFieldMinPersonArea: Float = 0.12f,
        val farFieldStableVelocityThreshold: Float = 0.18f,
        val farFieldResetMultiplier: Float = 1.8f,
        val farFieldFinalVelocityThreshold: Float = 0.18f,
    )

    private data class AnalysisFrame(
        val timestamp: Long,
        val score: Float,
        val velocity: Float,
        val landmarks: List<Landmark>,
    )

    // ================= STATE =================
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
    private var smoothedLandmarks: List<Landmark>? = null

    private var isPoseLockedFarField: Boolean = false

    // FPS estimation for adaptive frame-based thresholds
    private var avgFrameIntervalMs: Float = 33f
    private var lastFrameTimestamp: Long = 0L

    // ================= PUBLIC API =================
    val isReady: Boolean get() = !isCapturing

    /**
     * Processes a new frame with detected pose.
     * @return true if frame was accepted into buffer, false if rejected early
     */
    fun processFrame(
        bitmap: Bitmap,
        resultBundle: PoseLandmarkerHelper.ResultBundle,
        imageAnalysisWidth: Int = 0,
        imageAnalysisHeight: Int = 0,
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
        val rawLandmarks = landmarksList.map { Landmark(it.x(), it.y()) }
        val landmarks = smoothLandmarks(rawLandmarks)

        // 2. Pose Change Detection
        val velocity = calculateVelocity(landmarks, lastLandmarks)
        val isPoseChanged = velocity > config.poseChangeThreshold

        if (isPoseChanged) {
            analysisBuffer.clear()
            lastLandmarks = landmarks
            shotsInSession = 0
            resetSmoothing()
            val area = calculatePersonArea(landmarks)
            isPoseLockedFarField = area < config.farFieldMinPersonArea
            Log.d(TAG, "New pose detected → mode locked to farField=$isPoseLockedFarField (area=${area.format(2)})")
            Log.d(TAG, "New pose detected → buffer cleared")
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

        // 3. ROI + Aesthetic Score
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

        // Recycle immediately to save memory
        roiBitmap.recycle()

        // 4. Add to Buffer
        val now = System.currentTimeMillis()
        analysisBuffer.addLast(AnalysisFrame(now, score, velocity, landmarks))
        updateFrameIntervalEstimate(now)
        trimBuffer(now)
        lastLandmarks = landmarks

        Log.d(
            TAG,
            "Frame buffered | score=${score.format(2)}, vel=${velocity.format(3)}, " +
                "buffer=${analysisBuffer.size}",
        )

        val personArea = calculatePersonArea(landmarks)
        updateStabilityWindow(velocity, now, isPoseLockedFarField)

        // 5. Check Trigger
        if (shouldTrigger(isPoseLockedFarField)) {
            onCaptureTriggered()
            return true
        }

        return true
    }

    /**
     * Updates the continuous stability window state based on current velocity.
     */
    private fun updateStabilityWindow(
        velocity: Float,
        now: Long,
        isFarField: Boolean,
    ) {
        val (stableThreshold, resetMultiplier) =
            if (isFarField) {
                config.farFieldStableVelocityThreshold to config.farFieldResetMultiplier
            } else {
                config.continuousStableVelocityThreshold to 2.0f
            }

        val resetThreshold = stableThreshold * resetMultiplier

        if (velocity > resetThreshold) {
            stabilityWindowStartMs = 0L
            consecutiveStableFrames = 0
            shotsInSession = 0
            Log.d(TAG, "Stability window HARD RESET (vel=${velocity.format(3)} > ${resetThreshold.format(2)}, farField=$isFarField)")
        } else {
            if (consecutiveStableFrames == 0) {
                stabilityWindowStartMs = now
                Log.d(TAG, "Stability window STARTED at $now (vel=${velocity.format(3)}, farField=$isFarField)")
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
        lastTriggerTime = System.currentTimeMillis()
        Log.d(TAG, "Capture started. Processor locked.")
    }

    fun notifyCaptureFinished(score: Float? = null) {
        isCapturing = false

        // Reset stability window after capture
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
        resetSmoothing()
        isPoseLockedFarField = false
        Log.d(TAG, "Processor reset.")
    }

    // ================= TRIGGER LOGIC =================
    private fun shouldTrigger(isFarField: Boolean): Boolean {
        val now = System.currentTimeMillis()

        if (analysisBuffer.size < config.minBufferFrames) {
            return false
        }

        val recent = analysisBuffer.takeLast(config.recentFramesCount)
        if (recent.isEmpty()) return false

        val currentFrame = recent.last()

        // === 1. CONTINUOUS STABILITY WINDOW CHECK ===
        val stabilityDuration =
            if (stabilityWindowStartMs > 0L) {
                now - stabilityWindowStartMs
            } else {
                0L
            }

        // Calculate required frames based on current FPS
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

        // STRICT FINAL FRAME CHECK
        val effectiveFinalThreshold =
            if (isFarField) {
                config.farFieldFinalVelocityThreshold
            } else {
                config.finalFrameVelocityThreshold
            }

        if (currentFrame.velocity > effectiveFinalThreshold) {
            Log.d(TAG, "Trigger REJECTED: final frame velocity too high (${currentFrame.velocity.format(3)}, farField=$isFarField)")
            return false
        }

        // 3. VALID FRAMES COUNT
        val validFrames = recent.filter { it.velocity < config.stableVelocityThreshold }
        if (validFrames.size < config.minValidFrames) {
            Log.d(TAG, "Trigger REJECTED: not enough valid frames (${validFrames.size})")
            return false
        }

        // 4. PEAK DETECTION
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

        // 5. VELOCITY-ADAPTIVE COOLDOWN
        val timeSinceLast = now - lastTriggerTime

        // Base cooldown depends on score quality
        val isBetterThanLast = currentFrame.score > lastSavedScore * config.scoreImprovementFactor
        val isHighQuality = currentFrame.score > config.highQualityThreshold
        val baseCooldown = if (isBetterThanLast || isHighQuality) config.cooldownImproved else config.cooldownNormal

        // Adaptive cooldown: shorter when pose is more stable (direct velocity mapping)
        val adaptiveCooldown =
            when {
                currentFrame.velocity < 0.04f -> 800L // Rock steady: fast burst
                currentFrame.velocity < 0.08f -> 1200L // Very stable: moderate pace
                currentFrame.velocity < 0.15f -> 2000L // Slight movement: slower
                else -> baseCooldown // Moving: normal cooldown
            }.coerceAtMost(baseCooldown) // Never exceed base cooldown

        // 6. SESSION SHOT LIMIT
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

        // Update session counter
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

    // ================= HELPERS =================
    private fun calculatePersonArea(landmarks: List<Landmark>): Float {
        if (landmarks.isEmpty()) return 0f
        val xs = landmarks.map { it.x }
        val ys = landmarks.map { it.y }
        return (xs.max() - xs.min()) * (ys.max() - ys.min())
    }

    /**
     * Applies Exponential Moving Average smoothing to landmarks.
     * Formula: smoothed = prev * (1 - alpha) + current * alpha
     * Higher alpha = more responsive, less smoothing.
     */
    private fun smoothLandmarks(current: List<Landmark>): List<Landmark> {
        val alpha = config.landmarkSmoothingAlpha

        // First frame: no smoothing, just initialize
        if (smoothedLandmarks == null || current.size != smoothedLandmarks!!.size) {
            smoothedLandmarks = current.map { Landmark(it.x, it.y) }
            return current
        }

        // Apply EMA to each landmark
        return current.mapIndexed { i, landmark ->
            val prev = smoothedLandmarks!![i]
            Landmark(
                x = prev.x * (1f - alpha) + landmark.x * alpha,
                y = prev.y * (1f - alpha) + landmark.y * alpha,
            )
        }.also { smoothedLandmarks = it }
    }

    /**
     * Resets the smoothing state (call on pose change, reset, etc.)
     */
    private fun resetSmoothing() {
        smoothedLandmarks = null
    }

    /**
     * Sets or updates the focus controller after processor initialization.
     */
    fun setFocusController(controller: PersonFocusController?) {
        this.focusController = controller
        Log.d(TAG, "Focus controller ${if (controller != null) "attached" else "detached"}")
    }

    private fun calculateVelocity(
        current: List<Landmark>,
        previous: List<Landmark>?,
    ): Float {
        if (previous == null || current.size < 29 || current.size != previous.size) {
            return 1.0f
        }
        // Normalize displacement by shoulder width for scale invariance
        val shoulderWidth =
            hypot(
                (current[12].x - current[11].x).toDouble(),
                (current[12].y - current[11].y).toDouble(),
            ).toFloat().coerceAtLeast(0.08f)

        // Helper: compute normalized Euclidean distance for a landmark index
        fun dist(i: Int): Float {
            val dx = (current[i].x - previous[i].x).toDouble()
            val dy = (current[i].y - previous[i].y).toDouble()
            return hypot(dx, dy).toFloat() / shoulderWidth
        }

        val core = listOf(0, 11, 12, 23, 24) // Nose, shoulders, hips (high stability weight)
        val secondary = listOf(13, 14, 25, 26) // Elbows, knees (moderate movement tolerance)
        val extremities = listOf(15, 16, 27, 28) // Wrists, ankles (ignore tremor, wind, footwear noise)

        // Average velocity per group
        val coreVel = core.map { dist(it) }.average().toFloat()
        val secondaryVel = secondary.map { dist(it) }.average().toFloat()
        val extremitiesVel = extremities.map { dist(it) }.average().toFloat()

        // Weighted average: core dominates, extremities have minimal impact
        val avg = coreVel * 0.6f + secondaryVel * 0.3f + extremitiesVel * 0.1f

        // Safety fallback: if any core point moves significantly,
        // ensure overall velocity reflects it (prevents "still hands, moving torso" false positives)
        val maxCore = core.maxOf { dist(it) }

        // Return the higher of weighted avg or core-max scaled, to avoid masking body motion
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

    /**
     * Updates the estimated frame interval using exponential moving average.
     * Filters out anomalies (accepts 5-60 FPS range: 16ms - 200ms).
     */
    private fun updateFrameIntervalEstimate(now: Long) {
        if (lastFrameTimestamp > 0L) {
            val interval = now - lastFrameTimestamp
            if (interval in 16..200) {
                // EMA: 80% previous value + 20% new measurement for smooth convergence
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
}
