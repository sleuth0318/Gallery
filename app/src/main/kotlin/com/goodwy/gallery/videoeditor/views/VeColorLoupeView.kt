/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app editor — brush eyedropper magnifier (foss flavor).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * Magnifying color target (reference: color_picker_target.png). Works like a
 * real lens: the circle magnifies the content UNDER THE CIRCLE ITSELF (~6x,
 * nearest-neighbor), never the user's finger position — the finger only moves
 * the lens (it floats a bit above it, flipping below when there is no room,
 * and its center is clamped onto the media frame so it never samples the
 * surroundings). The circle's exact center is the sampled pixel, marked by
 * the white reticle; the single magnifier ring is DRAWN in that sampled
 * color, so it doubles as the color preview (no separate chip view). The
 * frozen preview frame is a PixelCopy snapshot taken when the eyedropper was
 * armed; the overlay is constrained exactly over the stage and the activity
 * feeds it touches (the stage routes them while armed).
 */
class VeColorLoupeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var snapshot: Bitmap? = null
    private val frame = RectF()
    private var touchX = -1f
    private var touchY = -1f
    private var pixelX = -1
    private var pixelY = -1
    private var radiusPx = 0f

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    /** The ONE magnifier ring: drawn in the currently sampled center color
     *  (white until the first touch), so it doubles as the color preview.
     *  Thickness doubled per M19q. */
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f * resources.displayMetrics.density
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val clipPath = Path()

    fun setSnapshot(bmp: Bitmap) {
        snapshot = bmp
        invalidate()
    }

    /** Media-frame rect in VIEW coords (stage's displayedRect — this overlay
     *  is constrained exactly like the stage, so coordinate spaces match). */
    fun setFrameRect(r: RectF) {
        frame.set(r)
    }

    fun setTouch(x: Float, y: Float) {
        touchX = x
        touchY = y
        computeSample()
        invalidate()
    }

    /** Currently sampled snapshot pixel, null until the first touch. */
    fun centerPixel(): Pair<Int, Int>? = if (pixelX >= 0) Pair(pixelX, pixelY) else null

    /** Drops the reticle/sample (armed-but-untouched state). */
    fun clear() {
        touchX = -1f
        touchY = -1f
        pixelX = -1
        pixelY = -1
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        radiusPx = (0.16f * minOf(w, h)).coerceAtLeast(48f * resources.displayMetrics.density)
    }

    /** Lens geometry: circle center from the finger (offset above, clamped
     *  onto the media frame), and the snapshot pixel under the CIRCLE CENTER. */
    private fun computeSample() {
        val bmp = snapshot
        if (bmp == null || touchX < 0f || radiusPx <= 0f || frame.width() <= 0f || frame.height() <= 0f) {
            pixelX = -1
            pixelY = -1
            return
        }
        val density = resources.displayMetrics.density
        val r = radiusPx

        var cx = touchX
        var cy = touchY - r - 14f * density
        if (cy - r < 0f) cy = touchY + r + 14f * density   // no room above -> flip below

        // EVERY media pixel must be reachable (M19q) — edges and corners
        // included: the lens CENTER clamps to the full frame (no radius
        // inset), so the circle may overhang the frame's border; the zoom
        // window below already shift-clamps to the snapshot bounds
        cx = cx.coerceIn(frame.left, frame.right)
        cy = cy.coerceIn(frame.top, frame.bottom)

        lensCx = cx
        lensCy = cy

        // the sampled pixel is what sits at the circle's center
        pixelX = ((cx - frame.left) / frame.width() * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
        pixelY = ((cy - frame.top) / frame.height() * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
    }

    private var lensCx = -1f
    private var lensCy = -1f

    override fun onDraw(canvas: Canvas) {
        val bmp = snapshot ?: return
        if (touchX < 0f || radiusPx <= 0f || pixelX < 0 || frame.width() <= 0f) return
        computeSample()
        val r = radiusPx
        val cx = lensCx
        val cy = lensCy

        // ~6x magnification vs the on-screen preview: one snapshot pixel shown
        // at (r/half)px must equal 6x its preview size (frame.width/bmp.width)
        var half = r * bmp.width / (ZOOM * frame.width())
        half = half.coerceAtLeast(4f)
        val src = Rect(
            (pixelX - half).roundToInt(),
            (pixelY - half).roundToInt(),
            (pixelX + half).roundToInt(),
            (pixelY + half).roundToInt(),
        )
        if (src.left < 0) { src.right -= src.left; src.left = 0 }
        if (src.top < 0) { src.bottom -= src.top; src.top = 0 }
        if (src.right > bmp.width) { src.left -= src.right - bmp.width; src.right = bmp.width }
        if (src.bottom > bmp.height) { src.top -= src.bottom - bmp.height; src.bottom = bmp.height }

        val dst = RectF(cx - r, cy - r, cx + r, cy + r)
        clipPath.rewind()
        clipPath.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(bmp, src, dst, imagePaint)
        canvas.restore()
        // ring in the sampled center color (a single source pixel IS the
        // color, so this previews exactly what OK would apply)
        ringPaint.color = bmp.getPixel(pixelX, pixelY) or 0xFF000000.toInt()
        canvas.drawCircle(cx, cy, r, ringPaint)

        // center reticle: exactly ONE source pixel blown up at the center —
        // its on-screen size is one lens cell (r/half), never fudged bigger,
        // so the marked area is the exact spot the sampler reads
        val cell = r / half
        canvas.drawRect(cx - cell / 2f, cy - cell / 2f, cx + cell / 2f, cy + cell / 2f, reticlePaint)
    }

    companion object {
        /** Magnification of the lens content vs the on-screen preview. */
        private const val ZOOM = 6f
    }
}
