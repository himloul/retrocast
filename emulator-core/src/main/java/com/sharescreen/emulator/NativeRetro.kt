package com.sharescreen.emulator

import android.view.Surface

class NativeRetro {
    companion object {
        init {
            System.loadLibrary("emulator-core")
        }
    }

    /**
     * Interface for receiving video frames directly from Libretro.
     * Pixels are in XRGB8888 (32-bit) format.
     */
    interface VideoListener {
        fun onFrameAvailable(pixels: IntArray, width: Int, height: Int)
    }

    private var videoListener: VideoListener? = null

    fun setVideoListener(listener: VideoListener?) {
        this.videoListener = listener
    }

    // Called from JNI
    private fun onNativeFrame(pixels: IntArray, width: Int, height: Int) {
        videoListener?.onFrameAvailable(pixels, width, height)
    }

    /**
     * Get the current version of the native bridge.
     */
    external fun getCoreVersion(): String

    /**
     * Load a Libretro core from a shared library (.so)
     */
    external fun loadCore(corePath: String): Boolean

    /**
     * Load a ROM file and start the game within the loaded core.
     */
    external fun loadGame(gamePath: String): Boolean

    /**
     * Run a single frame of emulation.
     */
    external fun runFrame()

    /**
     * Update the input state for a specific device.
     */
    external fun setInputState(port: Int, device: Int, index: Int, id: Int, value: Int)

    /**
     * Set the surface to draw on.
     */
    external fun setSurface(surface: Surface?)

    /**
     * Pull buffered audio samples from the core.
     * Returns PCM 16-bit interleaved stereo samples.
     */
    external fun pullAudio(): ShortArray?
}
