package com.example.autophotopose

import android.graphics.PointF
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPointFactory
import java.util.concurrent.TimeUnit

/**
 * Manages camera auto-focus based on the person's ROI center.
 */
class PersonFocusController(
    private val cameraControl: CameraControl,
    private val meteringPointFactory: MeteringPointFactory,
    private val config: Config = Config()
) {
    companion object {
        private const val TAG = "PersonFocus"
    }

    data class Config(
        val focusThrottleMs: Long = 800L,        // Minimum interval between focus requests
        val minMoveThreshold: Float = 0.02f      // Ignore center shifts smaller than 2% of frame size
    )

    private var lastFocusTime = 0L
    private var lastNormalizedCenter = PointF(0.5f, 0.5f) // Normalized coordinates (0.0 to 1.0)

    /**
     * Called when the person's ROI center changes significantly.
     * @param normCenterX Normalized X coordinate of the ROI center (0.0 - 1.0)
     * @param normCenterY Normalized Y coordinate of the ROI center (0.0 - 1.0)
     * @param imageWidth Width of the ImageAnalysis frame in pixels
     * @param imageHeight Height of the ImageAnalysis frame in pixels
     */
    fun onRoiCenterChanged(
        normCenterX: Float,
        normCenterY: Float,
        imageWidth: Int,
        imageHeight: Int
    ) {
        val now = System.currentTimeMillis()

        // 1. Throttle: Prevent spamming the camera with focus requests
        if (now - lastFocusTime < config.focusThrottleMs) return

        // 2. Ignore micro-shifts (dead zone for stability)
        val dx = normCenterX - lastNormalizedCenter.x
        val dy = normCenterY - lastNormalizedCenter.y
        if (dx * dx + dy * dy < config.minMoveThreshold * config.minMoveThreshold) return

        // 3. Convert normalized coordinates to pixel coordinates for MeteringPointFactory
        val pixelX = normCenterX * imageWidth
        val pixelY = normCenterY * imageHeight

        lastFocusTime = now
        lastNormalizedCenter.set(normCenterX, normCenterY)

        // 4. Build and submit focus request (AF only, to avoid exposure shifts)
        val point = meteringPointFactory.createPoint(pixelX, pixelY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        cameraControl.startFocusAndMetering(action)
        Log.d(TAG, "Focus updated: (${pixelX.toInt()}, ${pixelY.toInt()}) | interval=${now - lastFocusTime}ms")
    }

    /** Resets internal state. Call when camera is closed or reinitialized. */
    fun reset() {
        lastFocusTime = 0L
        lastNormalizedCenter.set(0.5f, 0.5f)
    }
}
