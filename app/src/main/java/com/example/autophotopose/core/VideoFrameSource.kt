package com.example.autophotopose.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer

data class VideoFrame(
    val bitmap: Bitmap,
    val presentationTimeUs: Long,
    val isEndOfStream: Boolean = false,
)

interface FrameSource : Closeable {
    fun nextFrame(): VideoFrame?
}

class VideoFrameSource(private val filePath: String) : FrameSource {
    companion object {
        private const val TAG = "VideoFrameSource"
        private const val TIMEOUT_US = 10_000L
    }

    private val extractor = MediaExtractor()
    private lateinit var codec: MediaCodec
    private var trackIndex = -1
    private var isInitialized = false
    private var sawEOS = false
    private var width = 0
    private var height = 0
    private var colorFormat = 0
    private val bufferInfo = MediaCodec.BufferInfo()
    private val jpegOutStream = ByteArrayOutputStream()
    private var nv21Buffer: ByteArray? = null

    init {
        try {
            extractor.setDataSource(filePath)
            trackIndex = findVideoTrack()
            require(trackIndex != -1) { "Video track not found in $filePath" }
            extractor.selectTrack(trackIndex)
            initCodec()
            Log.d(TAG, "Initialized successfully for $filePath")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            release()
            throw e
        }
    }

    private fun findVideoTrack(): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun initCodec() {
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("MIME type is null")

        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)

        codec = MediaCodec.createDecoderByType(mime)

        // Configure without Surface to access raw output buffers directly
        codec.configure(format, null, null, 0)
        codec.start()
        isInitialized = true
    }

    override fun nextFrame(): VideoFrame? {
        if (!isInitialized) return null

        while (!sawEOS) {
            // 1. Feed input buffer
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex)
                if (buffer != null) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // 2. Drain output buffer
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when (outIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    width = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                    height = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    colorFormat = newFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                    Log.d(TAG, "Output format changed: ${width}x$height, color=0x${colorFormat.toString(16)}")
                    continue
                }
                else -> {
                    if (outIndex >= 0) {
                        val isEOS = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val timeUs = bufferInfo.presentationTimeUs

                        if (!isEOS) {
                            val outputBuffer = codec.getOutputBuffer(outIndex)
                            if (outputBuffer != null) {
                                val bitmap = convertOutputBufferToBitmap(outputBuffer, bufferInfo)
                                codec.releaseOutputBuffer(outIndex, false)
                                if (bitmap != null) return VideoFrame(bitmap, timeUs, false)
                            }
                        } else {
                            codec.releaseOutputBuffer(outIndex, false)
                            sawEOS = true
                            return null
                        }
                    }
                }
            }
        }
        return null
    }

    private fun convertOutputBufferToBitmap(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ): Bitmap? {
        return try {
            // Reuse buffer to reduce allocations
            val nv21 = nv21Buffer ?: ByteArray(width * height * 3 / 2).also { nv21Buffer = it }

            when (colorFormat) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, // I420
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, // NV12
                    -> {
                    // Read Y plane
                    buffer.position(info.offset)
                    buffer.get(nv21, 0, width * height)

                    // Read UV planes and interleave into NV21 format (V first, then U)
                    val uvSize = width * height / 4
                    val uvOffset = width * height
                    for (i in 0 until uvSize) {
                        val u = buffer.get().toInt() and 0xFF
                        val v = buffer.get().toInt() and 0xFF
                        // Swap to NV21: V, U
                        nv21[uvOffset + i * 2] = v.toByte()
                        nv21[uvOffset + i * 2 + 1] = u.toByte()
                    }
                }
                else -> {
                    Log.w(TAG, "Unsupported color format: 0x${colorFormat.toString(16)}. Skipping frame.")
                    return null
                }
            }

            // Convert NV21 to Bitmap via YuvImage + JPEG decode
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            jpegOutStream.reset()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, jpegOutStream)

            val bytes = jpegOutStream.toByteArray()
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            // Create independent immutable copy to prevent recycling/sharing issues
            val safeBitmap = decoded.copy(Bitmap.Config.ARGB_8888, false)
            if (!decoded.isRecycled) decoded.recycle()

            safeBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Frame conversion failed", e)
            null
        }
    }

    override fun close() {
        release()
    }

    private fun release() {
        try {
            if (isInitialized) {
                codec.stop()
                codec.release()
                isInitialized = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Codec release failed", e)
        }
        try {
            extractor.release()
        } catch (e: Exception) {
            Log.e(TAG, "Extractor release failed", e)
        }
        try {
            jpegOutStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Stream close failed", e)
        }
        nv21Buffer = null
    }
}
