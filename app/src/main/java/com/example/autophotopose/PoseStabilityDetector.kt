package com.example.autophotopose

import android.graphics.Bitmap
import kotlin.math.sqrt

// структура для хранения точки
data class Landmark(val x: Float, val y: Float)

class PoseStabilityDetector(
    private val bufferSize: Int = 15,        // размер буфера
    private val threshold: Float = 0.02f,   // порог L2 для стабильности
    private val stableFramesNeeded: Int = 5 // сколько кадров подряд должно быть стабильным
) {

    // буфер для кадров
    private val frameBuffer = Array<Bitmap?>(bufferSize) { null }
    private var bufferIndex = 0

    private var previousLandmarks: List<Landmark>? = null
    private var stableCounter = 0

    /**
     * Добавляем новый кадр и его ключевые точки.
     * @param frame текущий Bitmap кадра
     * @param landmarks ключевые точки
     * @return список кадров из буфера, если поза стабильна, иначе null
     */
    fun addFrame(frame: Bitmap, landmarks: List<Landmark>): List<Bitmap>? {
        frameBuffer[bufferIndex] = frame
        bufferIndex = (bufferIndex + 1) % bufferSize

        // Если есть предыдущие точки, считаем изменение
        previousLandmarks?.let { prev ->
            val delta = calcLandmarksDelta(prev, landmarks)
            if (delta < threshold) {
                stableCounter++
            } else {
                stableCounter = 0
            }

            if (stableCounter >= stableFramesNeeded) {
                val stableFrames = mutableListOf<Bitmap>()
                for (i in 0 until bufferSize) {
                    val idx = (bufferIndex + i) % bufferSize
                    frameBuffer[idx]?.let { stableFrames.add(it) }
                }
                stableCounter = 0 // сброс после триггера
                return stableFrames
            }
        }

        previousLandmarks = landmarks
        return null
    }

    /**
     * Считаем среднеквадратичное изменене (L2) между текущими и предыдущими точками
     */
    private fun calcLandmarksDelta(prev: List<Landmark>, curr: List<Landmark>): Float {
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
