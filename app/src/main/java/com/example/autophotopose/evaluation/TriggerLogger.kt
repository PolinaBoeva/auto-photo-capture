package com.example.autophotopose.evaluation

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.util.Locale

class TriggerLogger(context: Context, videoName: String) {
    private val logDir: File = File(context.getExternalFilesDir(null), "test_logs")
    private val logFile: File = File(logDir, "${videoName}_triggers.csv")
    private val writer: FileWriter

    init {
        logDir.mkdirs()
        writer = FileWriter(logFile, false)
        writer.appendLine("video_time_us,score,velocity,landmarks_count")
    }

    fun log(
        timeUs: Long,
        score: Float,
        velocity: Float,
        landmarksCount: Int,
    ) {
        writer.appendLine(
            "$timeUs,${String.format(Locale.US, "%.4f", score)},${String.format(Locale.US, "%.4f", velocity)},$landmarksCount",
        )
    }

    fun close() {
        writer.flush()
        writer.close()
    }

    val filePath: String get() = logFile.absolutePath
}
