/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app editor — shared overlay box geometry (foss flavor).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.model

/**
 * Single source of truth for overlay item box sizes (fractions of the frame).
 * The preview stage (OverlayStageView) and the export baker
 * (VideoEffectsAssembler) MUST agree on every item's box or the rendered
 * preview would drift from the exported video/image, so both delegate here.
 */
object OverlayGeometry {
    /** TEXT base box at scale=1: ~650x180 px on a full-1080-wide frame
     *  (requested M19p). BOTH dims are fractions of the frame WIDTH, so the
     *  650:180 pixel ratio holds on every 1080-wide frame regardless of the
     *  media's aspect (and scales proportionally with the export frame). */
    const val TEXT_BASE_FRACTION_X = 0.602f    // 650/1080
    const val TEXT_BASE_FRACTION_Y = 0.1667f   // 180/1080, frame-WIDTH referenced

    /** EMOJI+SHAPE shared square side as a fraction of the frame WIDTH:
     *  requested exactly 4x the old 32px-unit emoji default (M19p:
     *  "from previous 32px to 128px (4x size by default)"). */
    const val EMOJI_BASE_FRACTION = 0.356f
    const val STICKER_BASE_FRACTION = 0.356f

    /** The ONE non-square shape (M19r): free-form rectangle whose default
     *  height is 0.6x its width. Every other shape stays square-locked. */
    const val SHAPE_RECTANGLE = "rectangle"
    const val RECT_ASPECT = 0.6f

    /** IMAGE (gallery sticker): width fraction; height follows itemAspect. */
    const val IMAGE_BASE_FRACTION = 0.24f

    /** Full box size incl. the scale multiplier and free-form handle
     *  multipliers (sizeX/sizeY). */
    fun itemBoxSize(item: VeOverlayItem, frameW: Float, frameH: Float): Pair<Float, Float> = when (item.kind) {
        VeOverlayItem.Kind.TEXT ->
            frameW * TEXT_BASE_FRACTION_X * item.scale * item.sizeX to
                frameW * TEXT_BASE_FRACTION_Y * item.scale * item.sizeY

        VeOverlayItem.Kind.EMOJI -> {
            // square-locked, sized off the frame width so it stays a true
            // square regardless of the frame's aspect
            val side = frameW * EMOJI_BASE_FRACTION * item.scale * item.sizeX
            side to side
        }

        VeOverlayItem.Kind.IMAGE -> {
            val w = frameW * IMAGE_BASE_FRACTION * item.scale * item.sizeX
            w to w / item.itemAspect.coerceAtLeast(0.05f)
        }

        VeOverlayItem.Kind.SHAPE -> {
            if (item.content == SHAPE_RECTANGLE) {
                // the free-form rectangle: independent axes, wide default box
                frameW * STICKER_BASE_FRACTION * item.scale * item.sizeX to
                    frameW * STICKER_BASE_FRACTION * RECT_ASPECT * item.scale * item.sizeY
            } else {
                // square-locked like the emoji (resizable, always a square)
                val side = frameW * STICKER_BASE_FRACTION * item.scale * item.sizeX
                side to side
            }
        }
    }

    /** sizeX/sizeY == 1 size, used by the drag-handle resize math. */
    fun itemBaseSize(item: VeOverlayItem, frameW: Float, frameH: Float): Pair<Float, Float> =
        itemBoxSize(item.copy(sizeX = 1f, sizeY = 1f), frameW, frameH)

    /** Emoji, square shapes and gallery images (natural aspect) resize
     *  uniformly — the resize handler locks both axes to one multiplier.
     *  TEXT and the rectangle (M19r) are free-form. */
    fun isUniformResize(item: VeOverlayItem): Boolean =
        item.kind == VeOverlayItem.Kind.EMOJI ||
            (item.kind == VeOverlayItem.Kind.SHAPE && item.content != SHAPE_RECTANGLE) ||
            item.kind == VeOverlayItem.Kind.IMAGE
}
