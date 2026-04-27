package com.example.autophotopose.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.scale
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AestheticPredictor(private val context: Context) {
    companion object {
        private const val TAG = "AestheticPredictor"

        // Model configuration constants
        private const val MODEL_FILENAME = "nima_aesthetic_mobilenet.tflite"
        private const val INPUT_SIZE = 224 // MobileNet input dimension
        private const val PIXEL_CHANNELS = 3 // RGB
        private const val FLOAT_BYTES = 4 // Float32 = 4 bytes
        private const val BATCH_SIZE = 1

        // NIMA output configuration
        private const val OUTPUT_CLASSES = 10 // Scores 1..10
        private const val MIN_SCORE = 1f
        private const val MAX_SCORE = 10f

        // Performance thresholds
        private const val SLOW_INFERENCE_THRESHOLD_MS = 100L
    }

    private var interpreter: Interpreter? = null
    private val inferenceLock = ReentrantLock()

    init {
        try {
            Log.d(TAG, "Loading TFLite model: $MODEL_FILENAME")
            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
            Log.i(TAG, "TFLite model loaded successfully. Interpreter initialized.")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load TFLite model: $MODEL_FILENAME", e)
            interpreter = null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during model initialization", e)
            interpreter = null
        }
    }

    /**
     * Loads TFLite model from assets into a direct ByteBuffer.
     * Uses FileChannel mapping for efficient zero-copy loading.
     */
    @Throws(IOException::class)
    private fun loadModelFile(): ByteBuffer {
        context.assets.openFd(MODEL_FILENAME).use { fileDescriptor ->
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            inputStream.channel.use { channel ->
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength,
                )
            }
        }
    }

    /**
     * Converts Bitmap to normalized RGB ByteBuffer for TFLite input.
     * Expects input in [0..1] float range, RGB order.
     *
     * @param bitmap Source bitmap (will be scaled to INPUT_SIZE)
     * @return ByteBuffer ready for interpreter.run()
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // Validate input
        if (bitmap.isRecycled) {
            Log.w(TAG, "Bitmap is already recycled, returning empty buffer")
            return ByteBuffer.allocateDirect(0).apply { order(ByteOrder.nativeOrder()) }
        }

        // Scale to model input size
        val scaledBitmap = bitmap.scale(INPUT_SIZE, INPUT_SIZE)

        // Allocate direct buffer
        val bufferSize = BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * PIXEL_CHANNELS * FLOAT_BYTES
        val byteBuffer =
            ByteBuffer.allocateDirect(bufferSize).apply {
                order(ByteOrder.nativeOrder())
            }

        // Extract pixels and normalize to [0..1]
        val pixelBuffer = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaledBitmap.getPixels(pixelBuffer, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixelBuffer) {
            // Extract RGB components (Android uses ARGB format)
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        // Prepare buffer for reading
        byteBuffer.rewind()

        // Recycle temporary bitmap to free memory immediately
        scaledBitmap.recycle()

        return byteBuffer
    }

    /**
     * Predicts aesthetic score for the given bitmap using NIMA model.
     * Returns mean score in range [1.0 .. 10.0].
     *
     * Thread-safe via ReentrantLock.
     *
     * @param bitmap Input image (any size, will be scaled)
     * @return Mean aesthetic score, or -1f on error
     */
    fun predictAesthetic(bitmap: Bitmap): Float {
        // Thread-safe inference
        return inferenceLock.withLock {
            val startTime = System.currentTimeMillis()

            try {
                // Validate interpreter state
                val interpreter =
                    interpreter ?: run {
                        Log.w(TAG, "Interpreter not initialized, returning default score")
                        return@withLock -1f
                    }

                // Validate input
                if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
                    Log.w(TAG, "Invalid bitmap input, returning default score")
                    return@withLock -1f
                }

                // Prepare input
                val inputBuffer = bitmapToByteBuffer(bitmap)
                if (inputBuffer.capacity() == 0) {
                    return@withLock -1f
                }

                // Prepare output buffer: [batch][classes]
                val outputBuffer = Array(BATCH_SIZE) { FloatArray(OUTPUT_CLASSES) }

                // Run inference
                interpreter.run(inputBuffer, outputBuffer)

                // Calculate mean score from probability distribution
                val probabilities = outputBuffer[0]
                val meanScore = calculateMeanScore(probabilities)

                val inferenceTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "Inference completed: score=${meanScore.format(2)}, time=${inferenceTime}ms")

                if (inferenceTime > SLOW_INFERENCE_THRESHOLD_MS) {
                    Log.w(TAG, "Slow inference: ${inferenceTime}ms > ${SLOW_INFERENCE_THRESHOLD_MS}ms threshold")
                }

                return@withLock meanScore
            } catch (e: Exception) {
                Log.e(TAG, "Error during aesthetic prediction", e)
                return@withLock -1f
            }
        }
    }

    /**
     * Calculates mean score from NIMA probability distribution.
     * Formula: Σ(probability[i] * (i + 1)) for i in [0..9]
     */
    private fun calculateMeanScore(probabilities: FloatArray): Float {
        if (probabilities.size != OUTPUT_CLASSES) {
            Log.w(TAG, "Unexpected output size: ${probabilities.size}, expected $OUTPUT_CLASSES")
            return -1f
        }

        var meanScore = 0f
        for (i in probabilities.indices) {
            // NIMA classes represent scores 1..10, so index 0 → score 1, etc.
            val scoreValue = MIN_SCORE + i
            meanScore += probabilities[i] * scoreValue
        }

        // Clamp to valid range just in case of numerical errors
        return meanScore.coerceIn(MIN_SCORE, MAX_SCORE)
    }

    /**
     * Releases TFLite interpreter resources.
     * Idempotent.
     */
    fun close() {
        inferenceLock.withLock {
            interpreter?.let {
                Log.d(TAG, "Closing TFLite interpreter")
                try {
                    it.close()
                    Log.d(TAG, "Interpreter closed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing interpreter", e)
                } finally {
                    interpreter = null
                }
            }
        }
    }

    // Helper extension for consistent float formatting in logs
    private fun Float.format(digits: Int): String = "%.${digits}f".format(this)
}
