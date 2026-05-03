package com.example.autophotopose.metrics

import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects performance metrics for the auto-capture pipeline.
 * Writes data asynchronously to a CSV file for later analysis.
 *
 * @param context Application context for file access
 * @param warmupFrames Number of initial frames to skip (default: 30)
 * @param pssSampleInterval Sample memory usage every N frames (default: 10)
 * @param flushInterval Flush to disk every N lines (default: 20)
 */
class PerformanceMetricsCollector(
    context: Context,
    private val warmupFrames: Int = 30,
    private val pssSampleInterval: Int = 10,
    private val flushInterval: Int = 20
) {
    companion object {
        private const val TAG = "MetricsCollector"
        private const val MEMORY_NOT_SAMPLED = -1f
        private const val LATENCY_NOT_MEASURED = -1f
    }

    private val csvFile: File
    private val scope = CoroutineScope(Dispatchers.IO + CoroutineName("MetricsWriter"))
    private val channel = Channel<String>(capacity = Channel.UNLIMITED)

    // State tracking
    private val frameCount = AtomicLong(0)
    private var lastFrameTimeNs = 0L
    private var avgFps = 0f
    private var writer: BufferedWriter? = null
    private var linesSinceFlush = 0
    private var isWarmupComplete = false
    private var droppedLogs = 0L
    private lateinit var writerJob: Job

    init {
        Log.d(TAG, "Initializing PerformanceMetricsCollector")

        val dir = context.getExternalFilesDir(null)
            ?: context.filesDir.also {
                Log.w(TAG, "External storage unavailable, falling back to internal")
            }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(java.util.Date())
        val randomSuffix = (1000..9999).random()
        val fileName = "perf_metrics_${timestamp}_$randomSuffix.csv"

        csvFile = File(dir, fileName)

        Log.d(TAG, "Output directory: ${dir.absolutePath}")
        Log.d(TAG, "Output file: ${csvFile.absolutePath}")
        Log.d(TAG, "Directory exists: ${dir.exists()}, can write: ${dir.canWrite()}")

        try {
            csvFile.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }

            writer = csvFile.bufferedWriter().apply {
                write("timestamp_ms,frame_id,event,latency_ms,processing_fps,velocity,score,memory_mb,decision,reason\n")
                flush()
            }
            Log.d(TAG, "CSV header written successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CSV writer", e)
        }

        writerJob = scope.launch {
            for (line in channel) {
                writer?.write(line)
                linesSinceFlush++
                if (linesSinceFlush >= flushInterval) {
                    writer?.flush()
                    linesSinceFlush = 0
                }
            }
        }
        Log.d(TAG, "Background writer coroutine started")
    }

    /**
     * Logs a processed frame to the metrics file.
     *
     * @param velocity Normalized pose velocity
     * @param score Aesthetic prediction score
     * @param decision Frame decision: accepted/rejected/processed
     * @param reason Reason for rejection (if applicable)
     * @param latencyMs Processing time in milliseconds
     */
    fun logFrame(
        velocity: Float,
        score: Float,
        decision: String = "processed",
        reason: String = "none",
        latencyMs: Float = LATENCY_NOT_MEASURED
    ) {
        val now = System.currentTimeMillis()
        val id = frameCount.incrementAndGet()

        // Skip warmup frames to avoid initialization noise
        if (!isWarmupComplete) {
            if (id > warmupFrames) {
                isWarmupComplete = true
                Log.d(TAG, "Warmup complete, starting metric collection")
            }
            return
        }

        // Calculate FPS using nanosecond precision
        val nowNs = System.nanoTime()
        val instantFps = if (lastFrameTimeNs > 0) {
            1_000_000_000f / (nowNs - lastFrameTimeNs)
        } else 0f
        avgFps = if (avgFps == 0f) instantFps else avgFps * 0.8f + instantFps * 0.2f
        lastFrameTimeNs = nowNs

        // Sample memory usage periodically
        val memMb = if (id % pssSampleInterval == 0L) {
            Debug.getPss() / 1024f
        } else {
            MEMORY_NOT_SAMPLED
        }

        // Format CSV line with US locale for consistent decimal separator
        val csvLine = buildString {
            append("$now,$id,frame,")
            append("${latencyMs.format(2)},")
            append("${avgFps.format(1)},")
            append("${velocity.format(3)},")
            append("${score.format(1)},")
            append("$memMb,")
            append("$decision,")
            append("$reason\n")
        }

        // Try to send to channel (non-blocking)
        val result = channel.trySend(csvLine)
        if (!result.isSuccess) {
            droppedLogs++
            Log.w(TAG, "Dropped log line, total dropped: $droppedLogs")
        }
    }

    /**
     * Logs a capture trigger event with split latency components.
     *
     * @param decisionTimestampMs Timestamp of the decision
     * @param decisionLatencyMs Time spent in the decision algorithm
     * @param cameraPipelineLatencyMs Time spent in the camera pipeline
     * @param velocity Pose velocity at trigger time
     * @param score Aesthetic score at trigger time
     */
    fun logTrigger(
        decisionTimestampMs: Long,
        decisionLatencyMs: Float,
        cameraPipelineLatencyMs: Float,
        velocity: Float,
        score: Float
    ) {
        val id = frameCount.get()

        // Log decision event
        val decisionLine = buildString {
            append("$decisionTimestampMs,$id,decision,")
            append("${decisionLatencyMs.format(2)},0,")
            append("${velocity.format(3)},${score.format(1)},")
            append("$MEMORY_NOT_SAMPLED,accepted,algorithm\n")
        }
        if (!channel.trySend(decisionLine).isSuccess) droppedLogs++

        // Log capture event
        val captureLine = buildString {
            append("${decisionTimestampMs + decisionLatencyMs.toLong()},$id,capture,")
            append("${cameraPipelineLatencyMs.format(2)},0,")
            append("${velocity.format(3)},${score.format(1)},")
            append("$MEMORY_NOT_SAMPLED,captured,camera_x\n")
        }
        if (!channel.trySend(captureLine).isSuccess) droppedLogs++
    }

    /**
     * Closes the collector and ensures all pending data is flushed to disk.
     * Must be called before the application terminates.
     */
    fun close() = runBlocking {
        Log.d(TAG, "Closing PerformanceMetricsCollector")

        channel.close()
        writerJob.join()

        writer?.apply {
            flush()
            close()
        }

        if (droppedLogs > 0) {
            Log.w(TAG, "Closed with $droppedLogs dropped log lines")
        } else {
            Log.d(TAG, "Closed successfully, all data flushed")
        }

        scope.cancel()
    }

    /**
     * Returns collection statistics for reporting.
     */
    fun getStats(): MetricsStats {
        return MetricsStats(
            totalFrames = frameCount.get(),
            droppedLogs = droppedLogs,
            dropRate = if (frameCount.get() > 0) droppedLogs.toDouble() / frameCount.get() else 0.0
        )
    }

    /**
     * Returns the absolute path to the output CSV file.
     */
    fun getFilePath(): String = csvFile.absolutePath

    /**
     * Statistics about the collection session.
     */
    data class MetricsStats(
        val totalFrames: Long,
        val droppedLogs: Long,
        val dropRate: Double
    )

    /**
     * Extension function for consistent float formatting with US locale.
     * Ensures decimal point instead of comma regardless of device region.
     */
    private fun Float.format(digits: Int): String =
        String.format(Locale.US, "%.${digits}f", this)
}
