package com.retrocast.streaming

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

    override fun retain() {
        val prev = refCount.getAndUpdate { v -> if (v > 0) v + 1 else v }
        if (prev <= 0) {
            throw IllegalStateException("retain() called on an object with refcount < 1")
        }
    }

    override fun release() {
        val prev = refCount.getAndUpdate { v -> if (v > 0) v - 1 else v }
        if (prev <= 0) {
            throw IllegalStateException("release() called on an object with refcount < 1")
        }
        if (prev == 1) {
            onRelease?.invoke()
        }
    }

    override fun toI420(): VideoFrame.I420Buffer = this

    override fun cropAndScale(
        sx: Int, sy: Int, sw: Int, sh: Int,
        dw: Int, dh: Int
    ): VideoFrame.Buffer {
        retain()
        return this
    }

    fun resetRefCount() {
        refCount.set(1)
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
            existing.resetRefCount()
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
