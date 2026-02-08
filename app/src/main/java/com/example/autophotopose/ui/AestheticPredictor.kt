package com.example.autophotopose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class AestheticPredictor(private val context: Context) {

    private var interpreter: Interpreter

    init {
        interpreter = Interpreter(loadModelFile("nima_aesthetic_mobilenet.tflite"))
    }

    // Загружаем модель из assets в ByteBuffer
    @Throws(IOException::class)
    private fun loadModelFile(filename: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val channel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    // Преобразуем Bitmap в input tensor
    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val inputSize = 224  // Размер для MobileNet
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4) // float32
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    // Предсказание эстетики
    fun predictAesthetic(bitmap: Bitmap): Float {
        val input = bitmapToByteBuffer(bitmap)
        val output = Array(1) { FloatArray(10) } // NIMA модель: 10 классов от 1 до 10

        interpreter.run(input, output)

        val scores = FloatArray(10) { i -> (i + 1).toFloat() } // 1..10
        var meanScore = 0f
        for (i in scores.indices) {
            meanScore += output[0][i] * scores[i]
        }
        return meanScore
    }

    // Можно закрыть интерпретатор при завершении
    fun close() {
        interpreter.close()
    }
}
