package com.example.autophotopose.core

interface TimeProvider {
    fun currentTimeMillis(): Long
}

class SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis() = System.currentTimeMillis()
}

class VirtualTimeProvider : TimeProvider {
    @Volatile
    private var virtualTimeMs: Long = 0L

    override fun currentTimeMillis() = virtualTimeMs

    fun setTimeFromPresentationTimeUs(timeUs: Long) {
        virtualTimeMs = timeUs / 1000L
    }
}
