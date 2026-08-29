/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Two-thumb trim slider with an optional filmstrip of video frames behind it.
 * Dependency-free custom view styled with the app's accent color. GPLv3.
 */
package com.goodwy.gallery.videoeditor.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class TrimSliderView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /** Called continuously while a thumb moves. */
        fun onTrimChanging(startUs: Long, endUs: Long)
        /** Called once when the user lifts the thumb. */
        fun onTrimChangeFinished(startUs: Long, endUs: Long)
    }

    var listener: Listener? = null

    private var durationUs = 0L
    private var startUs = 0L
    private var endUs = -1L

    /** Hard lower bound for the selectable clip length. */
    var minClipUs = 500_000L // 0.5 s

    private var accentColor = 0xFF66A3FF.toInt()
    private var thumbs: List<Bitmap> = emptyList()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private enum class Drag { NONE, START, END }
    private var dragging = Drag.NONE

    fun setColors(accent: Int) {
        accentColor = accent
        activePaint.color = accent
        invalidate()
    }

    fun setDuration(durationUs: Long) {
        this.durationUs = durationUs.coerceAtLeast(minClipUs)
        if (endUs <= 0L || endUs > this.durationUs) endUs = this.durationUs
        startUs = startUs.coerceIn(0L, this.durationUs - minClipUs)
        invalidate()
    }

    fun setThumbnails(thumbnails: List<Bitmap>) {
        thumbs = thumbnails
        invalidate()
    }

    fun getStartUs() = startUs
    fun getEndUs() = if (endUs <= 0L) durationUs else endUs

    fun setRange(startUs: Long, endUs: Long) {
        this.startUs = startUs.coerceIn(0L, max(0L, durationUs - minClipUs))
        this.endUs = if (endUs <= 0L) durationUs else endUs.coerceIn(this.startUs + minClipUs, durationUs)
        invalidate()
    }

    private fun contentRect(): RectF {
        val thumbW = dp(14f)
        return RectF(thumbW, height * 0.18f, width - thumbW, height * 0.82f)
    }

    private fun usToX(us: Long, r: RectF): Float =
        if (durationUs == 0L) r.left else r.left + (us.toFloat() / durationUs) * r.width()

    private fun xToUs(x: Float, r: RectF): Long =
        (((x - r.left) / r.width()) * durationUs).toLong().coerceIn(0L, durationUs)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (durationUs <= 0L) return
        val r = contentRect()
        val thumbW = dp(14f)

        // filmstrip
        if (thumbs.isNotEmpty()) {
            val strip = RectF(0f, r.top, width.toFloat(), r.bottom)
            val n = thumbs.size
            val each = strip.width() / n
            thumbs.forEachIndexed { i, bmp ->
                val dst = RectF(strip.left + i * each, strip.top, strip.left + (i + 1) * each, strip.bottom)
                canvas.drawBitmap(bmp, null, dst, imagePaint)
            }
        }

        val sx = usToX(startUs, r)
        val ex = usToX(getEndUs(), r)

        // dim outside selection
        canvas.drawRect(0f, r.top, sx, r.bottom, dimPaint)
        canvas.drawRect(ex, r.top, width.toFloat(), r.bottom, dimPaint)

        // track + selection outline
        canvas.drawRoundRect(RectF(r.left, r.centerY() - dp(1f), r.right, r.centerY() + dp(1f)), dp(1f), dp(1f), trackPaint)
        canvas.drawRect(sx, r.centerY() - dp(2f), ex, r.centerY() + dp(2f), activePaint)
        canvas.drawRect(sx, r.top, ex, r.top + dp(2f), activePaint)
        canvas.drawRect(sx, r.bottom - dp(2f), ex, r.bottom, activePaint)

        // thumbs
        val thumbTop = r.top - dp(2f)
        val thumbBottom = r.bottom + dp(2f)
        canvas.drawRoundRect(RectF(sx - thumbW / 2, thumbTop, sx + thumbW / 2, thumbBottom), dp(4f), dp(4f), thumbPaint)
        canvas.drawRoundRect(RectF(ex - thumbW / 2, thumbTop, ex + thumbW / 2, thumbBottom), dp(4f), dp(4f), thumbPaint)
        // grip lines
        val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt(); strokeWidth = dp(1.5f) }
        canvas.drawLine(sx, thumbTop + dp(8f), sx, thumbBottom - dp(8f), grip)
        canvas.drawLine(ex, thumbTop + dp(8f), ex, thumbBottom - dp(8f), grip)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationUs <= 0L) return false
        val r = contentRect()
        val grab = dp(28f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sx = usToX(startUs, r)
                val ex = usToX(getEndUs(), r)
                dragging = when {
                    abs(event.x - sx) < grab && abs(event.x - sx) <= abs(event.x - ex) -> Drag.START
                    abs(event.x - ex) < grab -> Drag.END
                    else -> {
                        // tap inside the strip jumps the nearest thumb
                        if (event.x < (sx + ex) / 2f) Drag.START else Drag.END
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                applyDrag(event.x, r)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != Drag.NONE) {
                    applyDrag(event.x, r)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging != Drag.NONE) {
                    listener?.onTrimChangeFinished(startUs, getEndUs())
                }
                dragging = Drag.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyDrag(x: Float, r: RectF) {
        val us = xToUs(x, r)
        when (dragging) {
            Drag.START -> startUs = min(us, getEndUs() - minClipUs).coerceAtLeast(0L)
            Drag.END -> endUs = max(us, startUs + minClipUs).coerceAtMost(durationUs)
            Drag.NONE -> return
        }
        invalidate()
        listener?.onTrimChanging(startUs, getEndUs())
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
