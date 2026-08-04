/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app video editor — immutable edit session state (foss flavor).
 * Inspired by the tool set of Simple Mobile Tools' Simple Gallery
 * (GPLv3), re-implemented from scratch on top of AndroidX Media3.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.model

import android.graphics.PointF

/** Focus tool modes (parity with the reference editor). */
enum class VeFocusMode { NONE, GAUSSIAN, RADIAL, LINEAR }

/** A sticker / text item placed on top of the video. */
data class VeOverlayItem(
    val id: Long,
    val kind: Kind,
    /** Emoji string (EMOJI), shape id (SHAPE) or user text (TEXT). */
    val content: String,
    val color: Int,
    /** Typeface family name for TEXT, null for the default font. */
    val fontFamily: String? = null,
    /** Normalized center on the video frame, 0..1. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Multiplier over the kind's base size. */
    val scale: Float = 1f,
    val rotationDegrees: Float = 0f,
) {
    enum class Kind { EMOJI, SHAPE, TEXT }

    fun moved(dx: Float, dy: Float) =
        copy(centerX = (centerX + dx).coerceIn(-0.5f, 1.5f), centerY = (centerY + dy).coerceIn(-0.5f, 1.5f))
}

/** One freehand brush stroke, points normalized to the video frame (0..1). */
data class VeBrushStroke(
    val color: Int,
    /** Stroke width as a fraction of the frame width, 0.002..0.08. */
    val sizeFraction: Float,
    val points: List<PointF>,
)

/**
 * One tonal/color adjustment slot of the Adjust tool. Every value is a signed
 * percentage in -100..100 (0 = untouched), matching the shared slider row.
 */
data class VeAdjustments(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val clarity: Int = 0,
    val shadows: Int = 0,
    val highlights: Int = 0,
    val exposure: Int = 0,
    val gamma: Int = 0,
    val blacks: Int = 0,
    val whites: Int = 0,
    val temperature: Int = 0,
    val sharpness: Int = 0,
) {
    fun isIdentity(): Boolean = this == VeAdjustments()

    /** Slots rendered by the custom GLSL pass (the rest map to Media3 built-ins). */
    fun hasAdvanced(): Boolean =
        exposure != 0 || gamma != 0 || temperature != 0 || shadows != 0 || highlights != 0 ||
            whites != 0 || blacks != 0 || clarity != 0 || sharpness != 0
}

/** Aspect-ratio preset for the Transform tool. [ratio] null keeps the original. */
data class VeAspectOption(
    val id: String,
    /** Current label shown on the chip, e.g. "2:1" (toggling swaps it to "1:2"). */
    val label: String,
    /** width/height ratio target, null = keep source aspect. */
    val ratio: Float?,
)

/**
 * Complete, immutable snapshot of one undoable edit-session state.
 * The undo/redo stacks are simple lists of these snapshots with a pointer.
 */
data class VideoEditState(
    // Trim (microseconds; trimEndUs == -1L -> until the end)
    val trimStartUs: Long = 0L,
    val trimEndUs: Long = -1L,

    // Transform
    val rotationDegrees: Float = 0f,         // 0 / 90 / 180 / 270 (clockwise button)
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val aspectOption: VeAspectOption = ASPECT_ORIGINAL,

    // Filter (index into VideoFilterDefs.FILTERS; 0 = None)
    val filterIndex: Int = 0,

    // Adjust (12 tonal/color slots, each -100..100; Reset restores VeAdjustments())
    val adjustments: VeAdjustments = VeAdjustments(),

    // Sound
    val muted: Boolean = false,

    // Focus
    val focusMode: VeFocusMode = VeFocusMode.NONE,
    val focusStrength: Float = 0.6f,         // 0..1 (blur radius / mask feather)
    val focusCenterX: Float = 0.5f,
    val focusCenterY: Float = 0.55f,         // normalized

    // Overlay presets ("light leak" style, index into OverlayBitmapFactory; 0 = none)
    val overlayIndex: Int = 0,

    // Stickers & text
    val items: List<VeOverlayItem> = emptyList(),

    // Brush
    val brushColor: Int = 0xFFE53935.toInt(),
    val brushSizeFraction: Float = 0.012f,
    val strokes: List<VeBrushStroke> = emptyList(),
) {
    fun hasEdits(sourceDurationUs: Long): Boolean =
        trimStartUs > 0L ||
            (trimEndUs in 1 until sourceDurationUs) ||
            rotationDegrees != 0f || flipHorizontal || flipVertical ||
            aspectOption.ratio != null || filterIndex != 0 ||
            !adjustments.isIdentity() ||
            muted || focusMode != VeFocusMode.NONE || overlayIndex != 0 ||
            items.isNotEmpty() || strokes.isNotEmpty()

    companion object {
        val ASPECT_ORIGINAL = VeAspectOption("original", "Original", null)
        val ASPECT_FREE = VeAspectOption("free", "Free", null)

        /**
         * Toggle-able aspect pairs — the exact custom set Simple Gallery's video
         * editor adds (2:1, 19:9, 5:4, 37:18, 16:10) plus the ubiquitous 1:1,
         * 4:3 and 16:9. Tapping a chip swaps landscape <-> portrait.
         */
        val ASPECT_TOGGLES = listOf(
            1f to 1f,
            2f to 1f,
            16f to 9f,
            4f to 3f,
            19f to 9f,
            5f to 4f,
            37f to 18f,
            16f to 10f,
        )

        fun aspectLabel(x: Float, y: Float): String =
            "${trimNum(x)}:${trimNum(y)}"

        private fun trimNum(v: Float): String =
            if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

        /** Landscape-first list of options used by the Transform panel chips. */
        val ASPECT_OPTIONS: List<VeAspectOption> =
            listOf(ASPECT_ORIGINAL) + ASPECT_TOGGLES.map { (x, y) ->
                VeAspectOption("${trimNum(x)}_${trimNum(y)}", aspectLabel(x, y), x / y)
            }
    }
}

/**
 * Minimal capped snapshot stack driving toolbar undo/redo, same visual
 * behavior as the existing image editor (gray when at the respective end).
 */
class EditHistory(private val cap: Int = 50) {
    private val stack = ArrayDeque<VideoEditState>()
    private var pointer = -1

    val canUndo get() = pointer > 0
    val canRedo get() = pointer in 0 until stack.size - 1
    val current: VideoEditState
        get() = if (pointer in stack.indices) stack[pointer] else VideoEditState()

    fun reset(initial: VideoEditState) {
        stack.clear()
        stack.addLast(initial)
        pointer = 0
    }

    /** Commits a new snapshot, dropping any redo tail. No-op if unchanged. */
    fun commit(state: VideoEditState): Boolean {
        if (pointer >= 0 && stack.getOrNull(pointer) == state) return false
        while (stack.size > pointer + 1) stack.removeLast()
        stack.addLast(state)
        if (stack.size > cap) stack.removeFirst()
        pointer = stack.size - 1
        return true
    }

    fun undo(): VideoEditState {
        if (canUndo) pointer--
        return current
    }

    fun redo(): VideoEditState {
        if (canRedo) pointer++
        return current
    }

    /** Replace the snapshot at the pointer WITHOUT pushing (live slider feedback). */
    fun mutateTop(state: VideoEditState) {
        if (pointer in stack.indices) {
            stack.removeAt(pointer)
            stack.add(pointer, state)
        } else {
            commit(state)
        }
    }
}
