/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app editor — arbitrary-angle "straighten" transform (foss flavor).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.effects

import android.graphics.Matrix
import androidx.media3.common.util.Size
import androidx.media3.effect.MatrixTransformation

/**
 * Rotate-in-place straighten (Transform tool dotted angle slider). Media3's
 * own [androidx.media3.effect.ScaleAndRotateTransformation] EXPANDS the output
 * frame to the rotated content's bounding box (letterboxing the corners in
 * black); straighten wants the opposite — the frame keeps its exact size, the
 * content rotates inside it, pre-zoomed by [scale] (VideoEffectsAssembler
 * computes the corner-coverage factor) so no background ever shows: the
 * overflow is clipped by the NDC viewport, which [MatrixTransformation]
 * explicitly documents ("Transformed pixels that are moved outside of the
 * normal device coordinate range are clipped").
 *
 * Implemented against the android.graphics.Matrix flavour of
 * MatrixTransformation and mirroring ScaleAndRotateTransformation.configure's
 * NDC aspect normalization (pre/post aspect scale) — output size unchanged.
 * Used by the live preview (CompositionPlayer) and both exporters alike, so
 * what you see is what you get.
 */
class StraightenEffect(
    /** Counter-clockwise degrees (media3's convention; the assembler flips the
     *  sign of the editor's clockwise-positive slider value). */
    private val rotationDegreesCcw: Float,
    /** Uniform pre-zoom so the rotated content still covers every corner. */
    private val scale: Float,
) : MatrixTransformation {

    private var matrix = Matrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val m = Matrix()
        m.postScale(scale, scale)
        m.postRotate(rotationDegreesCcw)
        // NDC is a square (-1..1 both axes); wrap the user matrix in the same
        // aspect correction ScaleAndRotateTransformation applies, so a degree
        // here is a degree on screen regardless of the frame's aspect.
        val aspect = inputWidth.toFloat() / inputHeight.toFloat()
        m.preScale(aspect, 1f)
        m.postScale(1f / aspect, 1f)
        matrix = m
        // keep the frame size: content overflow clips at the viewport edges
        return Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix = matrix
}
