package com.retrocast.streaming

import org.webrtc.*

/**
 * A custom WebRTC capturer that receives frames directly from Libretro.
 */
class LibretroVideoCapturer : VideoCapturer {
    companion object {
        private const val MIN_FRAME_INTERVAL_NS = 16_666_666L
    }

    private var capturerObserver: CapturerObserver? = null
    private var lastFrameTimestampNs = 0L

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: android.content.Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
    }

    override fun stopCapture() {
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
    }

    override fun isScreencast(): Boolean = true

    override fun dispose() {
    }

    fun onFrameCaptured(frame: VideoFrame) {
        val now = System.nanoTime()
        if (now - lastFrameTimestampNs < MIN_FRAME_INTERVAL_NS) {
            frame.release()
            return
        }
        lastFrameTimestampNs = now
        capturerObserver?.onFrameCaptured(frame)
    }
}
