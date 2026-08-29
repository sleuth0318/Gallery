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
import com.goodwy.gallery.videoeditor.model.OverlayGeometry
import com.goodwy.gallery.videoeditor.model.VeFocusMode
import com.goodwy.gallery.videoeditor.model.VideoEditState
import com.goodwy.gallery.videoeditor.model.VideoFilterDefs
import com.google.common.collect.ImmutableList
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
        if (state.aspectOption.id == VideoEditState.ASPECT_FREE.id) {
            val fc = state.freeCrop ?: return 1f to 1f
            return (fc.right - fc.left).coerceIn(0.05f, 1f) to (fc.bottom - fc.top).coerceIn(0.05f, 1f)
        }
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
     * @param includeOverlays brush strokes + stickers/text as baked-in
     *   BitmapOverlays. EXPORT passes true. The live preview passes false:
     *   OverlayStageView already draws items/strokes in the View layer on top
     *   of the video, so baking them into the preview chain would double-render
     *   them AND make drag/paint gestures wait on full composition rebuilds
     *   (visible lag-behind). Preview without overlays + stage rendering =
     *   real-time gestures; export with overlays = identical final pixels.
     * @param brushLayer lazily rendered bitmap of all brush strokes at frame size.
     */
    fun assemble(
        state: VideoEditState,
        videoWidth: Int,
        videoHeight: Int,
        includeOverlays: Boolean = true,
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

        // 1b) straighten: arbitrary-angle rotate INSIDE the frame + uniform
        //     auto-zoom so the rotated content still covers every corner
        //     (media3's ScaleAndRotateTransformation would instead letterbox
        //     the rotated AABB in black — unusable for straighten). The zoom
        //     uses the full-frame corner math (never the crop window), so the
        //     interactive free-crop preview and the committed crop show the
        //     identical content coverage. Positive angle = clockwise on
        //     screen; media3 rotates counter-clockwise, hence the sign flip.
        if (state.straightenAngle != 0f) {
            val rad = Math.toRadians(abs(state.straightenAngle).toDouble())
            val c = cos(rad)
            val s = sin(rad)
            // worst-case corner lies on the longer axis (aspect always >= 1)
            val a = maxOf(rotW.toFloat() / rotH, rotH.toFloat() / rotW)
            val cover = (c + s * a).toFloat()
            effects.add(StraightenEffect(rotationDegreesCcw = -state.straightenAngle, scale = cover))
        }

        // 2) crop in the rotated space: preset ratios center-crop symmetrically,
        //    the "Free" option crops to the user's arbitrary window (full frame
        //    = identity, no effect)
        val fc = state.freeCrop
        if (state.aspectOption.id == VideoEditState.ASPECT_FREE.id && fc != null &&
            (fc.left > 0f || fc.top > 0f || fc.right < 1f || fc.bottom < 1f)
        ) {
            effects.add(Crop(fc.left * 2f - 1f, fc.right * 2f - 1f, 1f - fc.bottom * 2f, 1f - fc.top * 2f))
        } else if (cropFracX != 1f || cropFracY != 1f) {
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

        // 8) brush layer + stickers/text on top — EXPORT ONLY. The preview
        //    renders these in OverlayStageView (View layer) instead; see the
        //    includeOverlays KDoc above.
        if (includeOverlays) {
            val overlays = ImmutableList.builder<TextureOverlay>()
            if (state.strokes.isNotEmpty()) {
                overlays.add(BitmapOverlay.createStaticBitmapOverlay(brushLayer()))
            }
            for (item in state.items) {
                // single source of truth shared with the preview stage box
                // (OverlayGeometry guarantees preview == export geometry)
                val (wF, hF) = OverlayGeometry.itemBoxSize(item, outW.toFloat(), outH.toFloat())
                val isText = item.kind == com.goodwy.gallery.videoeditor.model.VeOverlayItem.Kind.TEXT
                val wPx = wF.roundToInt().coerceAtLeast(24)
                val hPx = hF.roundToInt().coerceAtLeast(if (isText) 18 else 24)
                val bitmap = OverlayBitmapFactory.renderItem(item, wPx, hPx)
                // export hard requirement: the background anchor must stay
                // within [-1, 1] — items stranded past the frame edge (old
                // states, frames shrunk by a crop) used to fail the WHOLE
                // export. Clamp the center so the rotation-aware AABB of the
                // item stays fully inside the output frame.
                val rad = Math.toRadians(item.rotationDegrees.toDouble())
                val ca = abs(cos(rad)).toFloat()
                val sa = abs(sin(rad)).toFloat()
                val aabbHwN = (wPx / 2f * ca + hPx / 2f * sa) / outW
                val aabbHhN = (wPx / 2f * sa + hPx / 2f * ca) / outH
                val cx = if (aabbHwN >= 0.5f) 0.5f else item.centerX.coerceIn(aabbHwN, 1f - aabbHwN)
                val cy = if (aabbHhN >= 0.5f) 0.5f else item.centerY.coerceIn(aabbHhN, 1f - aabbHhN)
                val settings = StaticOverlaySettings.Builder()
                    .setOverlayFrameAnchor(0f, 0f)
                    .setBackgroundFrameAnchor(cx * 2f - 1f, (1f - cy) * 2f - 1f)
                    // Convention flip: the preview draws items with
                    // Canvas.rotate() where positive degrees are CLOCKWISE
                    // (y-axis-down), but media3's OverlaySettings rotation is
                    // documented "counter-clockwise" and applied in y-up GL
                    // space (OverlayMatrixProvider). Negate so exported
                    // frames match the editor preview.
                    .setRotationDegrees(-item.rotationDegrees)
                    .build()
                overlays.add(BitmapOverlay.createStaticBitmapOverlay(bitmap, settings))
            }
            val built = overlays.build()
            if (!built.isEmpty()) {
                effects.add(OverlayEffect(built))
            }
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
