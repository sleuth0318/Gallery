/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Procedurally generated overlay presets (light-leak / vignette / flare style,
 * the FOSS counterpart of the reference editor's basic overlay pack) and
 * bitmap renderers for stickers (emoji + vector shapes) and text items.
 * The SAME renderer feeds the on-screen preview and the exported overlays,
 * which keeps the editor WYSIWYG. GPLv3, see LICENSE.
 */
package com.goodwy.gallery.videoeditor.effects

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.goodwy.gallery.videoeditor.model.VeOverlayItem
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object OverlayBitmapFactory {

    data class OverlayPreset(val name: String, val render: (w: Int, h: Int) -> Bitmap)

    /** index 0 = None; thumbnails are cheap because they are generated tiny. */
    val PRESETS: List<OverlayPreset> = listOf(
        OverlayPreset("None") { w, h -> transparent(w, h) },
        OverlayPreset("Warm leak") { w, h -> cornerLeak(w, h, intArrayOf(0x66FF7043, 0x22FFB74D, 0), topLeft = false) },
        OverlayPreset("Cool leak") { w, h -> cornerLeak(w, h, intArrayOf(0x664FC3F7, 0x224FC3F7, 0), topLeft = true) },
        OverlayPreset("Flare") { w, h -> flare(w, h) },
        OverlayPreset("Rose") { w, h -> cornerLeak(w, h, intArrayOf(0x55F06292, 0x22F8BBD0, 0), topLeft = true) },
        OverlayPreset("Vignette") { w, h -> softVignette(w, h) },
        OverlayPreset("Prism") { w, h -> prism(w, h) },
    )

    private fun transparent(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    private fun cornerLeak(w: Int, h: Int, colors: IntArray, topLeft: Boolean): Bitmap {
        val bmp = transparent(w, h)
        val c = Canvas(bmp)
        val cx = if (topLeft) 0f else w.toFloat()
        val cy = if (topLeft) 0f else h.toFloat()
        val radius = (min(w, h) * 1.15f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, radius, colors, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    private fun flare(w: Int, h: Int): Bitmap {
        val bmp = transparent(w, h)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.22f, h * 0.25f, min(w, h) * 0.9f,
                intArrayOf(0x59FFF59D, 0x26FFCC80, 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    private fun softVignette(w: Int, h: Int): Bitmap {
        val bmp = transparent(w, h)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h / 2f, min(w, h) * 0.75f,
                intArrayOf(0, 0x33000000, 0x8F000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    private fun prism(w: Int, h: Int): Bitmap {
        val bmp = transparent(w, h)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(0x40EF5350, 0x40FFEE58, 0x4066BB6A, 0x4042A5F5, 0x40AB47BC),
                null, Shader.TileMode.CLAMP,
            )
            alpha = 0x55
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return bmp
    }

    // ---------------------------------------------------------------- shapes

    val SHAPES: List<String> = listOf("heart", "star", "circle", "square", "diamond", "triangle", "hexagon", "bubble")

    fun shapeBitmap(shapeId: String, color: Int, sizePx: Int): Bitmap {
        val bmp = transparent(sizePx, sizePx)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val s = sizePx.toFloat()
        val pad = s * 0.06f
        when (shapeId) {
            "circle" -> c.drawCircle(s / 2, s / 2, s / 2 - pad, p)
            "square" -> c.drawRoundRect(pad, pad, s - pad, s - pad, s * 0.08f, s * 0.08f, p)
            "diamond" -> c.drawPath(
                Path().apply {
                    moveTo(s / 2, pad); lineTo(s - pad, s / 2); lineTo(s / 2, s - pad); lineTo(pad, s / 2); close()
                }, p,
            )
            "triangle" -> c.drawPath(
                Path().apply {
                    moveTo(s / 2, pad); lineTo(s - pad, s - pad); lineTo(pad, s - pad); close()
                }, p,
            )
            "hexagon" -> {
                val path = Path()
                val r = s / 2 - pad
                for (i in 0 until 6) {
                    val a = Math.toRadians((60 * i - 30).toDouble())
                    val x = s / 2 + (r * cos(a)).toFloat()
                    val y = s / 2 + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }
            "star" -> {
                val path = Path()
                val rOut = s / 2 - pad
                val rIn = rOut * 0.42f
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) rOut else rIn
                    val a = Math.toRadians((36 * i - 90).toDouble())
                    val x = s / 2 + (r * cos(a)).toFloat()
                    val y = s / 2 + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }
            "bubble" -> {
                c.drawRoundRect(pad, pad, s - pad, s * 0.78f, s * 0.18f, s * 0.18f, p)
                c.drawPath(
                    Path().apply {
                        moveTo(s * 0.3f, s * 0.76f); lineTo(s * 0.18f, s - pad); lineTo(s * 0.5f, s * 0.76f); close()
                    }, p,
                )
            }
            else -> { // "heart"
                val path = Path()
                path.moveTo(s / 2, s - pad)
                path.cubicTo(-pad, s * 0.55f, s * 0.12f, -pad * 0.5f, s / 2, s * 0.32f)
                path.cubicTo(s * 0.88f, -pad * 0.5f, s + pad, s * 0.55f, s / 2, s - pad)
                path.close()
                c.drawPath(path, p)
            }
        }
        return bmp
    }

    // ------------------------------------------------------------ item render

    /**
     * Renders one overlay item (emoji / shape / text) into a transparent bitmap
     * of [widthPx]x[heightPx]. Text is auto-sized to fit the box.
     */
    fun renderItem(item: VeOverlayItem, widthPx: Int, heightPx: Int): Bitmap {
        val bmp = transparent(widthPx, heightPx)
        val c = Canvas(bmp)
        when (item.kind) {
            VeOverlayItem.Kind.SHAPE -> {
                val shape = shapeBitmap(item.content, item.color, widthPx)
                c.drawBitmap(shape, null, android.graphics.Rect(0, 0, widthPx, heightPx), null)
            }
            VeOverlayItem.Kind.EMOJI, VeOverlayItem.Kind.TEXT -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = item.color
                    textAlign = Paint.Align.CENTER
                    if (item.fontFamily != null) {
                        typeface = Typeface.create(item.fontFamily, Typeface.NORMAL)
                    }
                }
                val text = item.content
                if (text.isEmpty()) return bmp
                // find the biggest text size fitting the box
                var size = heightPx.toFloat()
                paint.textSize = size
                val bounds = android.graphics.Rect()
                paint.getTextBounds(text, 0, text.length, bounds)
                val widthRatio = if (bounds.width() > 0) (widthPx * 0.92f) / bounds.width() else 1f
                val heightRatio = if (bounds.height() > 0) (heightPx * 0.85f) / bounds.height() else 1f
                size *= min(widthRatio, heightRatio).coerceAtMost(1f).coerceAtLeast(0.05f)
                paint.textSize = size
                val y = heightPx / 2f - (paint.descent() + paint.ascent()) / 2f
                c.drawText(text, widthPx / 2f, y, paint)
            }
        }
        return bmp
    }

    /** Thumbnail helper: applies a gradient wash of a preset to a video frame. */
    fun tintedThumb(src: Bitmap, presetIndex: Int): Bitmap {
        if (presetIndex == 0) return src
        val overlay = PRESETS[presetIndex].render(src.width, src.height)
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(bmp).drawBitmap(overlay, 0f, 0f, null)
        return bmp
    }
}
