package com.example.autophotopose

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

/**
 * Copied pose landmarks for UI. MediaPipe results are only valid during the callback.
 */
data class PoseOverlayFrame(
    val poses: List<List<PointF>>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    companion object {
        fun from(resultBundle: PoseLandmarkerHelper.ResultBundle): PoseOverlayFrame {
            val poses =
                resultBundle.results.map { result ->
                    val landmarks = result.landmarks().firstOrNull().orEmpty()
                    landmarks.map { PointF(it.x(), it.y()) }
                }
            return PoseOverlayFrame(
                poses = poses,
                imageWidth = resultBundle.inputImageWidth,
                imageHeight = resultBundle.inputImageHeight,
            )
        }
    }
}

class OverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        companion object {
            private const val TAG = "OverlayView"

            // Visual constants
            private const val POINT_RADIUS_PX = 8f
            private const val LINE_WIDTH_PX = 6f
            private const val POINT_COLOR = Color.YELLOW
            private const val LINE_COLOR = Color.GREEN

            // Flash animation constants
            private const val FLASH_DURATION_MS = 120L
            private const val FLASH_MAX_ALPHA = 1f
            private const val FLASH_MIN_ALPHA_THRESHOLD = 0.01f
            private const val FLASH_OVERLAY_ALPHA = 180 // 0-255
        }

        private var poses: List<List<PointF>> = emptyList()
        private var imageWidth = 1
        private var imageHeight = 1

        // Scale and offset for mapping image coordinates to view coordinates
        private var scaleFactor = 1f
        private var offsetX = 0f
        private var offsetY = 0f

        // Paints for pose visualization
        private val pointPaint =
            Paint().apply {
                color = POINT_COLOR
                strokeWidth = POINT_RADIUS_PX * 2 // Radius * 2 for diameter if needed
                style = Paint.Style.FILL
                isAntiAlias = true
            }

        private val linePaint =
            Paint().apply {
                color = LINE_COLOR
                strokeWidth = LINE_WIDTH_PX
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        // Flash animation state
        private var flashAlpha = 0f
        private var flashAnimator: ValueAnimator? = null

        /**
         * Triggers the capture flash animation.
         */
        fun triggerCaptureFlash() {
            if (!isAttachedToWindow) {
                Log.w(TAG, "Cannot trigger flash: view not attached")
                return
            }

            // Cancel existing animation to prevent conflicts
            flashAnimator?.cancel()

            flashAlpha = FLASH_MAX_ALPHA
            flashAnimator =
                ValueAnimator.ofFloat(FLASH_MAX_ALPHA, 0f).apply {
                    duration = FLASH_DURATION_MS
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener { animation ->
                        flashAlpha = animation.animatedValue as Float
                        invalidate() // Request redraw on UI thread
                    }
                    start()
                }
        }

        /**
         * Updates pose landmarks and image dimensions.
         * Thread-safe: posts to UI thread if called from background.
         */
        fun setResults(
            poses: List<List<PointF>>,
            imageHeight: Int,
            imageWidth: Int,
        ) {
            if (!isAttachedToWindow) return

            if (Thread.currentThread() != context.mainLooper.thread) {
                post { setResults(poses, imageHeight, imageWidth) }
                return
            }

            this.poses = poses
            this.imageHeight = imageHeight
            this.imageWidth = imageWidth

            calculateScaleAndOffset()
            invalidate()
        }

        /**
         * Calculates scale factor and offsets to match PreviewView.ScaleType.FILL_CENTER.
         * This ensures overlay points align perfectly with camera preview.
         */
        private fun calculateScaleAndOffset() {
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()

            if (viewWidth <= 0 || viewHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                scaleFactor = 1f
                offsetX = 0f
                offsetY = 0f
                return
            }

            val widthScale = viewWidth / imageWidth
            val heightScale = viewHeight / imageHeight
            scaleFactor = maxOf(widthScale, heightScale)
            offsetX = (viewWidth - imageWidth * scaleFactor) / 2f
            offsetY = (viewHeight - imageHeight * scaleFactor) / 2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw flash overlay first (so pose is visible through it)
            if (flashAlpha > FLASH_MIN_ALPHA_THRESHOLD) {
                drawCaptureFlash(canvas)
            }

            if (poses.isEmpty()) return

            for (landmarks in poses) {
                if (landmarks.isEmpty()) continue

                for (lm in landmarks) {
                    val x = lm.x * imageWidth * scaleFactor + offsetX
                    val y = lm.y * imageHeight * scaleFactor + offsetY
                    canvas.drawCircle(x, y, POINT_RADIUS_PX, pointPaint)
                }

                PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                    val startIndex = connection.start()
                    val endIndex = connection.end()

                    if (startIndex >= landmarks.size || endIndex >= landmarks.size) return@forEach

                    val start = landmarks[startIndex]
                    val end = landmarks[endIndex]

                    val startX = start.x * imageWidth * scaleFactor + offsetX
                    val startY = start.y * imageHeight * scaleFactor + offsetY
                    val endX = end.x * imageWidth * scaleFactor + offsetX
                    val endY = end.y * imageHeight * scaleFactor + offsetY

                    canvas.drawLine(startX, startY, endX, endY, linePaint)
                }
            }
        }

        /**
         * Draws a semi-transparent black overlay for the capture flash effect.
         */
        private fun drawCaptureFlash(canvas: Canvas) {
            val alpha = (flashAlpha * FLASH_OVERLAY_ALPHA).toInt().coerceIn(0, 255)
            canvas.drawColor(Color.argb(alpha, 0, 0, 0))
        }

        /**
         * Clears all pose data and redraws the view.
         */
        fun clear() {
            if (Thread.currentThread() != context.mainLooper.thread) {
                post { clear() }
                return
            }
            poses = emptyList()
            invalidate()
        }

        /**
         * Lifecycle: Cancel animations when view is detached to prevent memory leaks.
         */
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            flashAnimator?.cancel()
            flashAnimator = null
        }
    }
