package com.example.autophotopose

import android.graphics.Bitmap
import kotlin.math.sqrt

data class Landmark(val x: Float, val y: Float)

class PoseStabilityDetector(
    private val bufferSize: Int = 15,
    private val threshold: Float = 0.02f,
    private val stableFramesNeeded: Int = 5,
) {
    private val frameBuffer = Array<Bitmap?>(bufferSize) { null }
    private var bufferIndex = 0

    private var previousLandmarks: List<Landmark>? = null
    private var stableCounter = 0

    /** Callback для события стабильной позы */
    var onStablePose: ((List<Bitmap>) -> Unit)? = null

    /** Добавляем новый кадр и его ключевые точки */
    fun pushFrame(
        frame: Bitmap,
        landmarks: List<Landmark>,
    ) {
        frameBuffer[bufferIndex] = frame
        bufferIndex = (bufferIndex + 1) % bufferSize

        previousLandmarks?.let { prev ->
            val delta = calcLandmarksDelta(prev, landmarks)
            if (delta < threshold) {
                stableCounter++
            } else {
                stableCounter = 0
            }

            if (stableCounter >= stableFramesNeeded) {
                // Генерируем событие для подписчиков
                val stableFrames = mutableListOf<Bitmap>()
                for (i in 0 until bufferSize) {
                    val idx = (bufferIndex + i) % bufferSize
                    frameBuffer[idx]?.let { stableFrames.add(it) }
                }

                // Сброс после срабатывания
                stableCounter = 0
                previousLandmarks = null

                // Вызываем callback
                onStablePose?.invoke(stableFrames)
            }
        }

        previousLandmarks = landmarks
    }

    /** Среднеквадратичное изменение L2 между точками */
    private fun calcLandmarksDelta(
        prev: List<Landmark>,
        curr: List<Landmark>,
    ): Float {
        if (prev.size != curr.size) return Float.MAX_VALUE
        var sum = 0f
        for (i in curr.indices) {
            val dx = curr[i].x - prev[i].x
            val dy = curr[i].y - prev[i].y
            sum += dx * dx + dy * dy
        }
        return sqrt(sum / curr.size)
    }
}
