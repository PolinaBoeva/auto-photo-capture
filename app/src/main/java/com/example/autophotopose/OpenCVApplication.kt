package com.example.autophotopose

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class OpenCVApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        if (OpenCVLoader.initDebug()) {
            Log.d("OpenCV", "OpenCV успешно загружен")
        } else {
            Log.e("OpenCV", "Ошибка загрузки OpenCV")
        }
    }
}
