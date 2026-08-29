/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app editor — straighten angle slider (foss flavor).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

/**
 * Straighten slider of the Transform tool (reference: angle.png): a tape-style
 * dotted horizontal scale fading out toward both ends — increasing the angle
 * scrolls the dots and the small 0° tick to the RIGHT, decreasing scrolls
 * them LEFT. 0° is marked by a tiny 6dp vertical line on the tape; the current
 * angle value is always drawn centered and ON TOP, with the dots/tick clipped
 * out underneath it. Dragging sweeps the full -90°..+90° range across the
 * half-width of the view; the reported value is quantized to 0.1° so the
 * label never disagrees with the applied rotation. Live moves report
 * finished=false; the gesture end reports finished=true (the undo-commit
 * point).
 */
class VeAngleSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** (angle, finished) — fired on every live change and once at gesture end. */
    var onAngleChanged: ((Float, Boolean) -> Unit)? = null

    private var angle = 0f
    private var dragging = false
    private var lastX = 0f

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** External sync (undo/redo, tool open, aspect swap): never fires the
     *  callback and never fights an ongoing drag. */
    fun setAngle(a: Float) {
        if (dragging) return
        angle = a.coerceIn(-MAX_ANGLE, MAX_ANGLE)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                lastX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val halfW = width / 2f
                if (halfW <= 0f) return true
                setLiveAngle(angle + (event.x - lastX) / halfW * MAX_ANGLE)
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onAngleChanged?.invoke(angle, true)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun setLiveAngle(a: Float) {
        var next = a.coerceIn(-MAX_ANGLE, MAX_ANGLE)
        if (abs(next) < SNAP_ZERO) next = 0f      // tactile zero-stop
        next = round(next * 10f) / 10f            // one decimal, label == value
        if (next != angle) {
            angle = next
            invalidate()
            onAngleChanged?.invoke(angle, false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val halfW = cx
        if (halfW <= 0f) return

        textPaint.textSize = 13f * d
        val label = label()
        val labelHalf = textPaint.measureText(label) / 2f + 8f * d
        val spacing = 9f * d
        val dotR = 1.4f * d

        // tape offset (REVERSED per M19q): +angle scrolls the scale RIGHT,
        // -angle scrolls it LEFT
        val shift = (angle / MAX_ANGLE) * halfW

        // label always on top: everything of the tape is clipped out under the
        // label rect and the text is painted last
        canvas.save()
        canvas.clipOutRect(cx - labelHalf, 0f, cx + labelHalf, height.toFloat())

        // dotted tape, overdrawn past both edges so dots never pop in/out
        val kMin = floor((-spacing - cx - shift).toDouble() / spacing).toInt()
        val kMax = ceil((width + spacing - cx - shift).toDouble() / spacing).toInt()
        for (k in kMin..kMax) {
            drawDot(canvas, cx + shift + k * spacing, cy, cx, halfW, dotR)
        }

        // 0° = tiny 6dp line on the tape (scrolls with it)
        val tickX = cx + shift
        val tickHalfH = 3f * d
        if (tickX > -d && tickX < width + d) {
            canvas.drawRect(tickX - 0.75f * d, cy - tickHalfH, tickX + 0.75f * d, cy + tickHalfH, tickPaint)
        }
        canvas.restore()

        val ty = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, ty, textPaint)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, cx: Float, halfW: Float, r: Float) {
        val t = (abs(x - cx) / halfW).coerceIn(0f, 1f)      // 0 center -> 1 edge
        val fade = (1f - t).pow(1.6f)
        dotPaint.color = Color.WHITE
        dotPaint.alpha = (255 * (0.12f + 0.88f * fade)).toInt()
        canvas.drawCircle(x, y, r * (0.7f + 0.3f * fade), dotPaint)
    }

    private fun label(): String = String.format(Locale.US, "%.1f", angle)

    companion object {
        /** full straighten capability, both directions (M19p) */
        private const val MAX_ANGLE = 90f
        private const val SNAP_ZERO = 0.25f
    }
}
