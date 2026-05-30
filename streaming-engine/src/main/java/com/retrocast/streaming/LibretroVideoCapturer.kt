package com.retrocast.streaming

import org.webrtc.*

/**
 * A custom WebRTC capturer that receives frames directly from Libretro.
 */
class LibretroVideoCapturer : VideoCapturer {
    private var capturerObserver: CapturerObserver? = null

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: android.content.Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        // Ready to receive frames
    }

    override fun stopCapture() {
        // Cleanup if needed
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
    }

    override fun isScreencast(): Boolean = true

    override fun dispose() {
    }

    /**
     * Called when Libretro has a new frame.
     */
    fun onFrameCaptured(frame: VideoFrame) {
        capturerObserver?.onFrameCaptured(frame)
    }
}
