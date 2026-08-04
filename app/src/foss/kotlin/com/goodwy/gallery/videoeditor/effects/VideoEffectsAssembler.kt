/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Maps one immutable VideoEditState snapshot to the concrete Media3 effect
 * chain. The exact same list is handed to the live preview (CompositionPlayer
 * composition swap) and to Transformer (export), so what you see is what you
 * get. GPLv3, see LICENSE.
 */
package com.goodwy.gallery.videoeditor.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import androidx.media3.common.Effect
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.Crop
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import com.goodwy.gallery.videoeditor.model.VeFocusMode
import com.goodwy.gallery.videoeditor.model.VideoEditState
import com.goodwy.gallery.videoeditor.model.VideoFilterDefs
import com.google.common.collect.ImmutableList
import kotlin.math.roundToInt

object VideoEffectsAssembler {

    private fun even(v: Int) = if (v % 2 == 0) v else v - 1

    private fun even(v: Float) = even(v.roundToInt()).coerceAtLeast(2)

    /** Rotated dims for a given edit state (90/270 swap width & height). */
    fun rotatedSize(state: VideoEditState, videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        val quarterTurns = ((state.rotationDegrees.toInt() % 360) + 360) % 360
        val rotated90 = quarterTurns == 90 || quarterTurns == 270
        return if (rotated90) videoHeight to videoWidth else videoWidth to videoHeight
    }

    /** Crop fractions (0..1) applied by the aspect tool in rotated space. */
    fun cropFractions(state: VideoEditState, rotW: Int, rotH: Int): Pair<Float, Float> {
        val r = state.aspectOption.ratio ?: return 1f to 1f
        val srcAspect = rotW.toFloat() / rotH.toFloat()
        return if (srcAspect > r) {
            ((r * rotH) / rotW) to 1f
        } else {
            1f to (rotW / (r * rotH))
        }
    }

    /** Final encoded frame size (even numbers, encoder-friendly). */
    fun outputSize(state: VideoEditState, videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        val (rotW, rotH) = rotatedSize(state, videoWidth, videoHeight)
        val (fx, fy) = cropFractions(state, rotW, rotH)
        return even(rotW * fx) to even(rotH * fy)
    }

    /**
     * @param videoWidth/videoHeight the DECODED input frame size (from the
     *   MediaMetadataRetriever probe, display-rotation corrected).
     * @param brushLayer lazily rendered bitmap of all brush strokes at frame size.
     */
    fun assemble(
        state: VideoEditState,
        videoWidth: Int,
        videoHeight: Int,
        brushLayer: () -> Bitmap,
    ): List<Effect> {
        val effects = ArrayList<Effect>()
        val (rotW, rotH) = rotatedSize(state, videoWidth, videoHeight)
        val (cropFracX, cropFracY) = cropFractions(state, rotW, rotH)

        // 1) geometry: rotate + mirror
        run {
            val b = ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(state.rotationDegrees)
            if (state.flipHorizontal || state.flipVertical) {
                b.setScale(
                    if (state.flipHorizontal) -1f else 1f,
                    if (state.flipVertical) -1f else 1f,
                )
            }
            effects.add(b.build())
        }

        // 2) aspect crop in the rotated space
        if (cropFracX != 1f || cropFracY != 1f) {
            effects.add(Crop(-cropFracX, cropFracX, -cropFracY, cropFracY))
        }

        // 3) normalize output size (also guarantees encoder-friendly even dims)
        val (outW, outH) = outputSize(state, videoWidth, videoHeight)
        effects.add(
            Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)
        )

        // 4) color preset filter
        VideoFilterDefs.FILTERS.getOrNull(state.filterIndex)?.let { filter ->
            if (!filter.isIdentity) effects.add(CurveFilterEffect(filter))
        }

        // 5) manual adjustments (Adjust tool)
        val adj = state.adjustments
        if (adj.hasAdvanced()) effects.add(AdjustEffect(adj))
        if (adj.brightness != 0) effects.add(Brightness(adj.brightness / 100f))
        if (adj.contrast != 0) effects.add(Contrast(adj.contrast / 100f))
        if (adj.saturation != 0) {
            effects.add(HslAdjustment.Builder().adjustSaturation(adj.saturation.toFloat()).build())
        }

        // 6) focus blur (applies to the video only, stickers stay sharp)
        if (state.focusMode != VeFocusMode.NONE) {
            effects.add(TiltShiftEffect(state.focusMode, state.focusStrength, state.focusCenterX, state.focusCenterY))
        }

        // 7) full-frame overlay preset ("light leaks")
        if (state.overlayIndex > 0) {
            OverlayBitmapFactory.PRESETS.getOrNull(state.overlayIndex)?.let { preset ->
                val bitmap = preset.render(outW, outH)
                effects.add(
                    OverlayEffect(
                        ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(bitmap))
                    )
                )
            }
        }

        // 8) brush layer + stickers/text on top
        val overlays = ImmutableList.builder<TextureOverlay>()
        if (state.strokes.isNotEmpty()) {
            overlays.add(BitmapOverlay.createStaticBitmapOverlay(brushLayer()))
        }
        for (item in state.items) {
            val isText = item.kind == com.goodwy.gallery.videoeditor.model.VeOverlayItem.Kind.TEXT
            val baseW = if (isText) 0.55f else 0.24f
            val wPx = (outW * baseW * item.scale).roundToInt().coerceAtLeast(24)
            val hPx = if (isText) {
                (outH * 0.20f * item.scale).roundToInt().coerceAtLeast(18)
            } else {
                (wPx.toFloat() * rotH / rotW).roundToInt().coerceAtLeast(24)
            }
            val bitmap = OverlayBitmapFactory.renderItem(item, wPx, hPx)
            val settings = StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(0f, 0f)
                .setBackgroundFrameAnchor(item.centerX * 2f - 1f, (1f - item.centerY) * 2f - 1f)
                .setRotationDegrees(item.rotationDegrees)
                .build()
            overlays.add(BitmapOverlay.createStaticBitmapOverlay(bitmap, settings))
        }
        val built = overlays.build()
        if (!built.isEmpty()) {
            effects.add(OverlayEffect(built))
        }

        return effects
    }

    /** Renders all brush strokes into one transparent frame-size bitmap. */
    fun renderBrushLayer(state: VideoEditState, frameW: Int, frameH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (stroke in state.strokes) {
            if (stroke.points.size < 2) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color
                style = Paint.Style.STROKE
                strokeWidth = (stroke.sizeFraction * frameW).coerceAtLeast(2f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            stroke.points.forEachIndexed { i, p: PointF ->
                val x = p.x * frameW
                val y = p.y * frameH
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        return bmp
    }
}
