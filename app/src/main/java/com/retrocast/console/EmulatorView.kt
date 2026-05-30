package com.retrocast.console

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import java.nio.ByteBuffer

class EmulatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var frameBitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false }
    private val destRect = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (width <= 0 || height <= 0) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val baseWidth = 240; val baseHeight = 160
        val scaleX = width / baseWidth; val scaleY = height / baseHeight
        val scale = Math.max(1, Math.min(scaleX, scaleY))
        setMeasuredDimension(baseWidth * scale, baseHeight * scale)
    }

    fun setFrame(pixels: ByteBuffer, width: Int, height: Int) {
        val bitmap = frameBitmap
        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
            frameBitmap?.recycle()
            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        pixels.rewind()
        frameBitmap!!.copyPixelsFromBuffer(pixels)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = frameBitmap ?: return
        val w = width; val h = height
        val bw = bitmap.width; val bh = bitmap.height
        if (w <= 0 || h <= 0 || bw <= 0 || bh <= 0) return

        val scale = Math.min(w / bw, h / bh)
        val dw = bw * scale; val dh = bh * scale
        val dx = (w - dw) / 2; val dy = (h - dh) / 2
        destRect.set(dx, dy, dx + dw, dy + dh)
        canvas.drawBitmap(bitmap, null, destRect, paint)
    }
}
