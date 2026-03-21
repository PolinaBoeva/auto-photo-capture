package com.example.autophotopose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class OverlayView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : View(context, attrs) {
        private var results: List<PoseLandmarkerResult> = emptyList()

        private val pointPaint =
            Paint().apply {
                color = Color.YELLOW
                strokeWidth = 10f
                style = Paint.Style.FILL
            }

        private val linePaint =
            Paint().apply {
                color = Color.GREEN
                strokeWidth = 6f
                style = Paint.Style.STROKE
            }

        private var imageWidth = 1
        private var imageHeight = 1

        private var scaleFactor = 1f
        private var offsetX = 0f
        private var offsetY = 0f

        fun setResults(
            results: List<PoseLandmarkerResult>,
            imageHeight: Int,
            imageWidth: Int,
        ) {
            this.results = results
            this.imageHeight = imageHeight
            this.imageWidth = imageWidth

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()

            val imageAspectRatio = imageWidth.toFloat() / imageHeight
            val viewAspectRatio = viewWidth / viewHeight

            if (viewAspectRatio > imageAspectRatio) {
                scaleFactor = viewWidth / imageWidth
                offsetY = (viewWidth / imageAspectRatio - viewHeight) / 2f
                offsetX = 0f
            } else {
                scaleFactor = viewHeight / imageHeight
                offsetX = (viewHeight * imageAspectRatio - viewWidth) / 2f
                offsetY = 0f
            }

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (results.isEmpty()) return

            for (result in results) {
                val landmarks = result.landmarks().firstOrNull() ?: continue

                for (lm in landmarks) {
                    val x = lm.x() * imageWidth * scaleFactor - offsetX
                    val y = lm.y() * imageHeight * scaleFactor - offsetY
                    canvas.drawCircle(x, y, 8f, pointPaint)
                }

                PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                    val start = landmarks[connection.start()]
                    val end = landmarks[connection.end()]

                    val startX = start.x() * imageWidth * scaleFactor - offsetX
                    val startY = start.y() * imageHeight * scaleFactor - offsetY
                    val endX = end.x() * imageWidth * scaleFactor - offsetX
                    val endY = end.y() * imageHeight * scaleFactor - offsetY

                    canvas.drawLine(
                        startX,
                        startY,
                        endX,
                        endY,
                        linePaint,
                    )
                }
            }
        }

        fun clear() {
            results = emptyList()
            invalidate()
        }
    }
