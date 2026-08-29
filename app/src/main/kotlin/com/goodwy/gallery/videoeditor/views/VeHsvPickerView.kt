/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app editor — HSV color picker view (foss flavor).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * "HDR" color panel body (reference: color_panel.png): vertical HUE bar with
 * a white thumb line (left), a big saturation/value square with a selector
 * circle (center) and a vertical OPACITY slider over a checkerboard (right).
 * One view with three touch regions keeps the panel layout trivial.
 */
class VeHsvPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val hsv = floatArrayOf(210f, 0.85f, 0.9f)
    private var alpha = 1f

    var onColorChanged: ((Int) -> Unit)? = null

    private val hueRect = RectF()
    private val sqRect = RectF()
    private val alphaRect = RectF()

    private enum class Drag { NONE, HUE, SQUARE, ALPHA }
    private var dragging = Drag.NONE

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private var checker: BitmapShader? = null

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        alpha = Color.alpha(color) / 255f
        invalidate()
    }

    fun getColor(): Int = Color.HSVToColor((alpha * 255).toInt().coerceIn(0, 255), hsv)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = resources.displayMetrics.density
        val pad = 6f * d
        val barW = w * 0.05f
        val gap = w * 0.045f
        hueRect.set(pad, pad, pad + barW, h - pad)
        alphaRect.set(w - pad - barW, pad, w - pad, h - pad)
        sqRect.set(hueRect.right + gap, pad, alphaRect.left - gap, h - pad)
        // transparency checkerboard (8px tiles)
        val size = (4 * d).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = 0xFF454545.toInt()
        c.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), p)
        p.color = 0xFFBDBDBD.toInt()
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        c.drawRect(size.toFloat(), size.toFloat(), (size * 2).toFloat(), (size * 2).toFloat(), p)
        checker = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density

        // --- hue bar (top = 0°, bottom = 360°)
        val hues = IntArray(13) { i -> Color.HSVToColor(floatArrayOf(i * 30f, 1f, 1f)) }
        paint.shader = LinearGradient(
            hueRect.left, hueRect.top, hueRect.left, hueRect.bottom,
            hues, null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(hueRect, paint)
        paint.shader = null
        val hueY = hueRect.top + (hsv[0] / 360f).coerceIn(0f, 1f) * hueRect.height()
        canvas.drawRoundRect(
            hueRect.left - 3f * density, hueY - 1.5f * density,
            hueRect.right + 3f * density, hueY + 1.5f * density,
            2f * density, 2f * density, thumbPaint,
        )

        // --- saturation/value square
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = LinearGradient(
            sqRect.left, sqRect.top, sqRect.right, sqRect.top,
            Color.WHITE, pure, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(sqRect, paint)
        paint.shader = LinearGradient(
            sqRect.left, sqRect.top, sqRect.left, sqRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(sqRect, paint)
        paint.shader = null
        val selX = sqRect.left + hsv[1].coerceIn(0f, 1f) * sqRect.width()
        val selY = sqRect.top + (1f - hsv[2].coerceIn(0f, 1f)) * sqRect.height()
        thumbPaint.style = Paint.Style.STROKE
        thumbPaint.strokeWidth = 3f * density
        canvas.drawCircle(selX, selY, 10f * density, thumbPaint)
        thumbPaint.style = Paint.Style.FILL

        // --- opacity slider over its checkerboard
        checker?.let { paint.shader = it }
        canvas.drawRect(alphaRect, paint)
        val opaque = Color.HSVToColor(hsv)
        paint.shader = LinearGradient(
            alphaRect.left, alphaRect.top, alphaRect.left, alphaRect.bottom,
            opaque and 0x00FFFFFF, opaque, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(alphaRect, paint)
        paint.shader = null
        // fader direction INVERTED per request (M19p): top = fully
        // transparent, bottom = fully opaque — the gradient above already
        // reads that way, the thumb position and touch mapping follow suit
        val alphaY = alphaRect.top + alpha.coerceIn(0f, 1f) * alphaRect.height()
        canvas.drawRoundRect(
            alphaRect.left - 3f * density, alphaY - 1.5f * density,
            alphaRect.right + 3f * density, alphaY + 1.5f * density,
            2f * density, 2f * density, thumbPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    expand(hueRect).contains(event.x, event.y) -> Drag.HUE
                    expand(sqRect).contains(event.x, event.y) -> Drag.SQUARE
                    expand(alphaRect).contains(event.x, event.y) -> Drag.ALPHA
                    else -> Drag.NONE
                }
            }
        }
        when (dragging) {
            Drag.HUE -> {
                hsv[0] = ((event.y - hueRect.top) / hueRect.height() * 360f).coerceIn(0f, 360f)
            }
            Drag.SQUARE -> {
                hsv[1] = ((event.x - sqRect.left) / sqRect.width()).coerceIn(0f, 1f)
                hsv[2] = (1f - (event.y - sqRect.top) / sqRect.height()).coerceIn(0f, 1f)
            }
            Drag.ALPHA -> {
                // inverted fader: bottom = opaque (see onDraw note)
                alpha = ((event.y - alphaRect.top) / alphaRect.height()).coerceIn(0f, 1f)
            }
            Drag.NONE -> return false
        }
        invalidate()
        onColorChanged?.invoke(getColor())
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            dragging = Drag.NONE
        }
        return true
    }

    /** Fat-finger grab margin around the two slim bars. */
    private fun expand(r: RectF): RectF {
        val m = 14f * resources.displayMetrics.density
        return RectF(r.left - m, r.top - m, r.right + m, r.bottom + m)
    }
}
