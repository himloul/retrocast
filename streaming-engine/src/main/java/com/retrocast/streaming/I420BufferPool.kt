package com.retrocast.streaming

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class PooledI420Buffer(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val yPlane: ByteBuffer,
    private val uPlane: ByteBuffer,
    private val vPlane: ByteBuffer,
    private val yStrideVal: Int,
    private val uvStrideVal: Int
) : VideoFrame.I420Buffer {

    private val refCount = AtomicInteger(1)
    var onRelease: (() -> Unit)? = null

    override fun getWidth(): Int = frameWidth
    override fun getHeight(): Int = frameHeight
    override fun getDataY(): ByteBuffer = yPlane
    override fun getDataU(): ByteBuffer = uPlane
    override fun getDataV(): ByteBuffer = vPlane
    override fun getStrideY(): Int = yStrideVal
    override fun getStrideU(): Int = uvStrideVal
    override fun getStrideV(): Int = uvStrideVal

    override fun retain() { refCount.incrementAndGet() }

    override fun release() {
        if (refCount.decrementAndGet() <= 0) {
            onRelease?.invoke()
        }
    }

    override fun toI420(): VideoFrame.I420Buffer = this

    override fun cropAndScale(
        sx: Int, sy: Int, sw: Int, sh: Int,
        dw: Int, dh: Int
    ): VideoFrame.Buffer {
        if (sx == 0 && sy == 0 && sw == frameWidth && sh == frameHeight && dw == frameWidth && dh == frameHeight) {
            retain()
            return this
        }
        val scaled = JavaI420Buffer.allocate(dw, dh)
        val dstY = scaled.strideY
        val dstU = scaled.strideU
        for (y in 0 until dh) {
            val srcRow = sy + y * sh / dh
            for (x in 0 until dw) {
                val srcCol = sx + x * sw / dw
                scaled.getDataY().put(y * dstY + x, yPlane.get(srcRow * yStrideVal + srcCol))
            }
        }
        for (y in 0 until dh / 2) {
            val srcRow = sy / 2 + y * (sh / 2) / (dh / 2)
            for (x in 0 until dw / 2) {
                val srcCol = sx / 2 + x * (sw / 2) / (dw / 2)
                scaled.getDataU().put(y * dstU + x, uPlane.get(srcRow * uvStrideVal + srcCol))
                scaled.getDataV().put(y * dstU + x, vPlane.get(srcRow * uvStrideVal + srcCol))
            }
        }
        return scaled
    }
}

class I420BufferPool {
    private val available = ArrayDeque<PooledI420Buffer>()

    @Synchronized
    fun acquire(w: Int, h: Int, yStride: Int, uvStride: Int): PooledI420Buffer {
        val existing = available.poll()
        if (existing != null) {
            existing.getDataY().clear()
            existing.getDataU().clear()
            existing.getDataV().clear()
            return existing
        }
        val ySize = yStride * h
        val uvSize = uvStride * (h / 2)
        val buf = PooledI420Buffer(
            w, h,
            ByteBuffer.allocateDirect(ySize),
            ByteBuffer.allocateDirect(uvSize),
            ByteBuffer.allocateDirect(uvSize),
            yStride, uvStride
        )
        buf.onRelease = { returnToPool(buf) }
        return buf
    }

    @Synchronized
    private fun returnToPool(buf: PooledI420Buffer) {
        available.add(buf)
    }
}
