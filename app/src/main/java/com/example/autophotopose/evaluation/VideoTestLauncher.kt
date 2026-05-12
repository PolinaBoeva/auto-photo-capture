package com.example.autophotopose.evaluation

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.autophotopose.AutoCaptureProcessor
import com.example.autophotopose.PoseLandmarkerHelper
import com.example.autophotopose.core.VideoFrameSource
import com.example.autophotopose.core.VirtualTimeProvider
import com.example.autophotopose.ui.AestheticPredictor
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object VideoTestLauncher {
    private const val TAG = "VideoTestLauncher"
    private const val TEST_FOLDER = "test_videos"

    fun startEvaluation(context: Context) =
        CoroutineScope(Dispatchers.IO).launch {
            val testDir = File(context.getExternalFilesDir(null), TEST_FOLDER)
            if (!testDir.exists() || testDir.listFiles().isNullOrEmpty()) {
                Log.e(TAG, "Test directory not found or empty: ${testDir.absolutePath}")
                return@launch
            }

            val videoFiles = testDir.listFiles { f -> f.extension.equals("mp4", ignoreCase = true) }?.toList() ?: emptyList()
            if (videoFiles.isEmpty()) {
                Log.e(TAG, "No MP4 files found in ${testDir.absolutePath}")
                return@launch
            }
            Log.d(TAG, "Found ${videoFiles.size} video(s) for testing")

            val aestheticPredictor = AestheticPredictor(context)
            val timeProvider = VirtualTimeProvider()

            val processor =
                AutoCaptureProcessor(
                    timeProvider = timeProvider,
                    aestheticPredictor = aestheticPredictor,
                    onCaptureTriggered = {},
                    config = AutoCaptureProcessor.Config(),
                )

            val poseHelper =
                PoseLandmarkerHelper(
                    context = context,
                    runningMode = RunningMode.IMAGE,
                    poseLandmarkerHelperListener =
                        object : PoseLandmarkerHelper.LandmarkerListener {
                            override fun onError(
                                error: String,
                                errorCode: Int,
                            ) {
                                Log.e(TAG, "Pose error: $error")
                            }

                            override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
                            }
                        },
                )

            for (videoFile in videoFiles) {
                Log.d(TAG, "Starting: ${videoFile.name}")
                processor.reset()
                timeProvider.setTimeFromPresentationTimeUs(0)
                runSingleVideo(context, videoFile, processor, timeProvider, poseHelper)
            }

            aestheticPredictor.close()
            poseHelper.clearPoseLandmarker()
            Log.d(TAG, "Test completed.")
        }

    private suspend fun runSingleVideo(
        context: Context,
        videoFile: File,
        processor: AutoCaptureProcessor,
        timeProvider: VirtualTimeProvider,
        poseHelper: PoseLandmarkerHelper,
    ) = withContext(Dispatchers.Default) {
        val logger = TriggerLogger(context, videoFile.nameWithoutExtension)
        val frameSource = VideoFrameSource(videoFile.absolutePath)

        var frameCount = 0
        var triggerCount = 0

        try {
            while (true) {
                val frame = frameSource.nextFrame() ?: break
                if (frame.isEndOfStream) break
                frameCount++

                if (frame.bitmap.isRecycled) {
                    Log.e(TAG, "Source frame already recycled!")
                    continue
                }

                val safeBitmap =
                    Bitmap.createBitmap(
                        frame.bitmap.width,
                        frame.bitmap.height,
                        Bitmap.Config.ARGB_8888,
                    )
                val pixels = IntArray(frame.bitmap.width * frame.bitmap.height)
                frame.bitmap.getPixels(pixels, 0, frame.bitmap.width, 0, 0, frame.bitmap.width, frame.bitmap.height)
                safeBitmap.setPixels(pixels, 0, frame.bitmap.width, 0, 0, frame.bitmap.width, frame.bitmap.height)

                timeProvider.setTimeFromPresentationTimeUs(frame.presentationTimeUs)

                val mpBitmap = safeBitmap.copy(Bitmap.Config.ARGB_8888, false)

                try {
                    val resultBundle = poseHelper.detectSyncBitmap(mpBitmap)
                    if (resultBundle == null) continue

                    val triggered =
                        processor.processFrame(
                            bitmap = safeBitmap,
                            resultBundle = resultBundle,
                            imageAnalysisWidth = frame.bitmap.width,
                            imageAnalysisHeight = frame.bitmap.height,
                        )

                    if (triggered) {
                        val last = processor.getLastAnalysisFrame()
                        if (last != null) {
                            logger.log(frame.presentationTimeUs, last.score, last.velocity, last.landmarks.size)
                            triggerCount++
                            Log.d(TAG, "Trigger at ${frame.presentationTimeUs}us")

                            processor.notifyCaptureStarted()
                            processor.notifyCaptureFinished(score = last.score)
                        }
                    }
                } finally {
                    if (!mpBitmap.isRecycled) mpBitmap.recycle()
                    if (!safeBitmap.isRecycled) safeBitmap.recycle()
                }

                if (frameCount % 50 == 0) {
                    Log.d(TAG, "Progress: frames=$frameCount, triggers=$triggerCount")
                }
            }
            Log.d(TAG, "DONE: $frameCount frames, $triggerCount triggers -> ${logger.filePath}")
        } finally {
            frameSource.close()
            logger.close()
            processor.reset()
        }
    }
}
