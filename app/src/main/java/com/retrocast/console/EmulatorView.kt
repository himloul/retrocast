package com.retrocast.console

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer

open class EmulatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {
    private var frameBitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }
    private val destRect = Rect()
    private var showStats = false
    private val fpsCounter = FpsCounter()

    private val overlayBg = Paint().apply { color = 0x80000000.toInt() }
    private val overlayText = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    @Volatile
    private var surfaceValid = false

    init {
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceValid = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceValid = false
    }

    fun setShowStats(enabled: Boolean) { showStats = enabled }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    fun setFrame(pixels: ByteBuffer, width: Int, height: Int) {
        if (!surfaceValid) return

        val bmp = frameBitmap
        if (bmp == null || bmp.width != width || bmp.height != height) {
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        pixels.rewind()
        val ready = frameBitmap!!
        ready.copyPixelsFromBuffer(pixels)

        val canvas = try { holder.lockCanvas() } catch (_: Exception) { null }
        if (canvas != null) {
            try {
                val vw = canvas.width; val vh = canvas.height
                val bw = ready.width; val bh = ready.height
                val scale = Math.min(vw.toFloat() / bw, vh.toFloat() / bh)
                val dw = (bw * scale).toInt(); val dh = (bh * scale).toInt()
                val dx = (vw - dw) / 2; val dy = (vh - dh) / 2
                destRect.set(dx, dy, dx + dw, dy + dh)
                canvas.drawBitmap(ready, null, destRect, paint)
                if (showStats) {
                    val fps = fpsCounter.update()
                    canvas.drawRoundRect(8f, 8f, 160f, 52f, 8f, 8f, overlayBg)
                    canvas.drawText("FPS: %.0f".format(fps), 18f, 40f, overlayText)
                }
            } finally {
                try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
            }
        }
    }
}
