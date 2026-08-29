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
import com.goodwy.gallery.videoeditor.model.OverlayGeometry
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

    val SHAPES: List<String> = listOf(
        "heart", "star", "circle", "square", OverlayGeometry.SHAPE_RECTANGLE, "triangle", "pentagon",
        "hexagon", "octagon", "oval", "ring", "plus", "arrow", "bubble",
    )

    fun shapeBitmap(shapeId: String, color: Int, sizePx: Int): Bitmap {
        // the rectangle is the one WIDE shape (M19r); all others stay square
        val h = if (shapeId == OverlayGeometry.SHAPE_RECTANGLE) {
            (sizePx * OverlayGeometry.RECT_ASPECT).toInt().coerceAtLeast(1)
        } else {
            sizePx
        }
        val bmp = transparent(sizePx, h)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        val s = sizePx.toFloat()
        val rh = h.toFloat()
        val pad = s * 0.06f
        when (shapeId) {
            "circle" -> c.drawCircle(s / 2, s / 2, s / 2 - pad, p)
            "square" -> c.drawRoundRect(pad, pad, s - pad, s - pad, s * 0.08f, s * 0.08f, p)
            OverlayGeometry.SHAPE_RECTANGLE ->
                c.drawRoundRect(pad, pad, s - pad, rh - pad, rh * 0.14f, rh * 0.14f, p)
            "triangle" -> c.drawPath(
                Path().apply {
                    moveTo(s / 2, pad); lineTo(s - pad, s - pad); lineTo(pad, s - pad); close()
                }, p,
            )
            "pentagon" -> c.drawPath(regularPolygon(s, pad, 5, -90f), p)
            "octagon" -> c.drawPath(regularPolygon(s, pad, 8, -67.5f), p)
            "oval" -> c.drawOval(pad, s * 0.2f, s - pad, s * 0.8f, p)
            "ring" -> {
                c.drawCircle(s / 2, s / 2, s / 2 - pad, p)
                val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                }
                c.drawCircle(s / 2, s / 2, s * 0.26f, hole)
            }
            "plus" -> {
                val t = s * 0.16f
                c.drawPath(
                    Path().apply {
                        moveTo(s / 2 - t, pad); lineTo(s / 2 + t, pad); lineTo(s / 2 + t, s / 2 - t)
                        lineTo(s - pad, s / 2 - t); lineTo(s - pad, s / 2 + t); lineTo(s / 2 + t, s / 2 + t)
                        lineTo(s / 2 + t, s - pad); lineTo(s / 2 - t, s - pad); lineTo(s / 2 - t, s / 2 + t)
                        lineTo(pad, s / 2 + t); lineTo(pad, s / 2 - t); lineTo(s / 2 - t, s / 2 - t)
                        close()
                    }, p,
                )
            }
            "arrow" -> {
                val sh = s * 0.15f
                c.drawPath(
                    Path().apply {
                        moveTo(pad, s / 2 - sh); lineTo(s * 0.55f, s / 2 - sh); lineTo(s * 0.55f, pad)
                        lineTo(s - pad, s / 2); lineTo(s * 0.55f, s - pad); lineTo(s * 0.55f, s / 2 + sh)
                        lineTo(pad, s / 2 + sh); close()
                    }, p,
                )
            }
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

    /** Regular [sides]-gon path of radius s/2-pad centered in the bitmap. */
    private fun regularPolygon(s: Float, pad: Float, sides: Int, startDeg: Float): Path {
        val path = Path()
        val r = s / 2 - pad
        for (i in 0 until sides) {
            val a = Math.toRadians((360f / sides * i + startDeg).toDouble())
            val x = s / 2 + (r * cos(a)).toFloat()
            val y = s / 2 + (r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    // ------------------------------------------------------------ item render

    /**
     * Renders one overlay item (emoji / shape / text) into a transparent bitmap
     * of [widthPx]x[heightPx]. Text is auto-sized to fit the box, supports
     * multiline content, a filled box background and left/center/right alignment.
     */
    fun renderItem(item: VeOverlayItem, widthPx: Int, heightPx: Int): Bitmap {
        val bmp = transparent(widthPx, heightPx)
        val c = Canvas(bmp)
        when (item.kind) {
            VeOverlayItem.Kind.SHAPE -> {
                val shape = shapeBitmap(item.content, item.color, widthPx)
                c.drawBitmap(shape, null, android.graphics.Rect(0, 0, widthPx, heightPx), null)
            }
            VeOverlayItem.Kind.EMOJI -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = item.color
                    textAlign = Paint.Align.CENTER
                }
                val text = item.content
                if (text.isEmpty()) return bmp
                // fit the glyph into the smaller side, then STRETCH it to fill
                // the box so the free-form handles distort stickers like text
                val fitSide = min(widthPx, heightPx)
                var size = fitSide.toFloat()
                paint.textSize = size
                val bounds = android.graphics.Rect()
                paint.getTextBounds(text, 0, text.length, bounds)
                val widthRatio = if (bounds.width() > 0) (fitSide * 0.92f) / bounds.width() else 1f
                val heightRatio = if (bounds.height() > 0) (fitSide * 0.85f) / bounds.height() else 1f
                size *= min(widthRatio, heightRatio).coerceIn(0.05f, 1f)
                paint.textSize = size
                val glyph = transparent(fitSide, fitSide)
                val gc = Canvas(glyph)
                val y = fitSide / 2f - (paint.descent() + paint.ascent()) / 2f
                gc.drawText(text, fitSide / 2f, y, paint)
                c.drawBitmap(glyph, null, android.graphics.Rect(0, 0, widthPx, heightPx), null)
            }
            VeOverlayItem.Kind.TEXT -> drawTextItem(c, item, widthPx, heightPx)
            VeOverlayItem.Kind.IMAGE -> {
                // gallery sticker: cached EXIF-corrected decode (warmed at
                // pick time); null = undecodable source -> stays transparent
                ImageStickerCache.get(item.content)?.let { src ->
                    c.drawBitmap(src, null, android.graphics.Rect(0, 0, widthPx, heightPx), null)
                }
            }
        }
        return bmp
    }

    private fun drawTextItem(c: Canvas, item: VeOverlayItem, widthPx: Int, heightPx: Int) {
        val text = item.content
        if (text.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.color
            textAlign = when (item.textAlign) {
                0 -> Paint.Align.LEFT
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            if (item.fontFamily != null) {
                typeface = Typeface.create(item.fontFamily, Typeface.NORMAL)
            }
        }
        val lines = text.split('\n')
        val padX = widthPx * 0.06f

        // box background first
        if (item.backgroundColor != android.graphics.Color.TRANSPARENT) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = item.backgroundColor }
            val r = heightPx * 0.10f
            c.drawRoundRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), r, r, bg)
        }

        // find the biggest text size fitting every line and the whole stack.
        // Lines come ONLY from the user's explicit Enter breaks — the box
        // resize scales the font, it never re-wraps (M13i spec).
        paint.textSize = fitTextSize(paint, lines, widthPx, heightPx)

        val lineHeight = (paint.descent() - paint.ascent()) * LINE_SPACING
        val totalH = lineHeight * lines.size
        var y = heightPx / 2f - totalH / 2f - paint.ascent()
        for (line in lines) {
            val x = when (item.textAlign) {
                0 -> padX
                2 -> widthPx - padX
                else -> widthPx / 2f
            }
            c.drawText(line, x, y, paint)
            y += lineHeight
        }
    }

    /**
     * Biggest text size (px) whose lines (explicit \n breaks only) all fit the
     * given box — width per line AND total line-stack height. Used BOTH by the
     * renderer and the inline text editor (re-fitted on every keystroke) so
     * they look identical.
     */
    fun fitTextSize(paint: Paint, lines: List<String>, widthPx: Int, heightPx: Int): Float {
        val bounds = android.graphics.Rect()
        fun fits(s: Float): Boolean {
            paint.textSize = s
            val lineHeight = (paint.descent() - paint.ascent()) * LINE_SPACING
            if (lineHeight * lines.size > heightPx * 0.82f) return false
            for (line in lines) {
                paint.getTextBounds(line, 0, line.length, bounds)
                if (bounds.width() > widthPx * 0.88f) return false
            }
            return true
        }
        var size = heightPx.toFloat()
        while (size > heightPx * 0.05f && !fits(size)) size *= 0.92f
        return size
    }

    private const val LINE_SPACING = 1.05f

    /** Thumbnail helper: applies a gradient wash of a preset to a video frame. */
    fun tintedThumb(src: Bitmap, presetIndex: Int): Bitmap {
        if (presetIndex == 0) return src
        val overlay = PRESETS[presetIndex].render(src.width, src.height)
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(bmp).drawBitmap(overlay, 0f, 0f, null)
        return bmp
    }
}
