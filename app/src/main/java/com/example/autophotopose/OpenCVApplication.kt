package com.example.autophotopose

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class OpenCVApplication : Application() {
    companion object {
        private const val TAG = "OpenCVApp"

        /**
         * Global flag indicating whether OpenCV was successfully initialized.
         */
        @Volatile
        var isInitialized: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Initializing OpenCV...")

        try {
            // initDebug() tries to load OpenCV from the project's native libs.
            val isLoaded = OpenCVLoader.initDebug()

            if (isLoaded) {
                isInitialized = true
                val version = org.opencv.core.Core.VERSION
                Log.i(TAG, "OpenCV initialized successfully. Version: $version")
            } else {
                isInitialized = false
                Log.e(TAG, "OpenCV initialization failed: initDebug() returned false")
            }
        } catch (e: UnsatisfiedLinkError) {
            isInitialized = false
            Log.e(TAG, "OpenCV native library not found. Check your build.gradle and ABI filters.", e)
        } catch (e: Exception) {
            isInitialized = false
            Log.e(TAG, "Unexpected error during OpenCV initialization", e)
        }
    }
}
