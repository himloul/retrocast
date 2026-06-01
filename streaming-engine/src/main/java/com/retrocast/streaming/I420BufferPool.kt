package com.retrocast.streaming

import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class PooledI420Buffer(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val yPlane: ByteBuffer,
    private val uPlane: ByteBuffer,
    private val vPlane: ByteBuffer,
    private val yStrideVal: Int,
    private val uvStrideVal: Int,
    val pool: I420BufferPool?
) : VideoFrame.I420Buffer {

    private val released = AtomicBoolean(false)

    override fun getWidth(): Int = frameWidth
    override fun getHeight(): Int = frameHeight
    override fun getDataY(): ByteBuffer = yPlane
    override fun getDataU(): ByteBuffer = uPlane
    override fun getDataV(): ByteBuffer = vPlane
    override fun getStrideY(): Int = yStrideVal
    override fun getStrideU(): Int = uvStrideVal
    override fun getStrideV(): Int = uvStrideVal

    override fun retain() {
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            pool?.returnToPool(this)
        }
    }

    override fun toI420(): VideoFrame.I420Buffer = this

    override fun cropAndScale(
        sx: Int, sy: Int, sw: Int, sh: Int,
        dw: Int, dh: Int
    ): VideoFrame.Buffer {
        return this
    }

    fun reset() {
        released.set(false)
    }
}

class I420BufferPool {
    private val available = ArrayDeque<PooledI420Buffer>()
    private val inPool = mutableSetOf<PooledI420Buffer>()

    @Synchronized
    fun acquire(w: Int, h: Int, yStride: Int, uvStride: Int): PooledI420Buffer {
        val existing = available.poll()
        if (existing != null) {
            inPool.remove(existing)
            existing.getDataY().clear()
            existing.getDataU().clear()
            existing.getDataV().clear()
            existing.reset()
            return existing
        }
        val ySize = yStride * h
        val uvSize = uvStride * (h / 2)
        return PooledI420Buffer(
            w, h,
            ByteBuffer.allocateDirect(ySize),
            ByteBuffer.allocateDirect(uvSize),
            ByteBuffer.allocateDirect(uvSize),
            yStride, uvStride,
            this
        )
    }

    @Synchronized
    fun returnToPool(buf: PooledI420Buffer) {
        if (inPool.add(buf)) {
            available.add(buf)
        }
    }
}
