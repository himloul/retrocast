package com.retrocast.console

class FpsCounter(private val windowSize: Int = 30) {
    private val timestamps = LongArray(windowSize)
    private var index = 0
    private var count = 0

    fun update(): Float {
        val now = System.nanoTime()
        timestamps[index] = now
        index = (index + 1) % timestamps.size
        if (count < timestamps.size) count++
        if (count >= 2) {
            val newest = timestamps[(index - 1 + timestamps.size) % timestamps.size]
            val oldest = timestamps[(index - count + timestamps.size) % timestamps.size]
            val elapsed = newest - oldest
            if (elapsed > 0) {
                return (count - 1).toFloat() / (elapsed / 1_000_000_000f)
            }
        }
        return 0f
    }

    fun reset() {
        index = 0
        count = 0
    }
}
