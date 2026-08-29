/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Video filter presets: exact knot/param data ported from the app's bundled
 * Zomato photofilters FilterPack (same 16 filters the image editor exposes),
 * re-expressed so one data model feeds BOTH the GPU shader (preview+export)
 * and the CPU evaluator (carousel thumbnails). GPLv3, see LICENSE.
 */
package com.goodwy.gallery.videoeditor.model

/** Flattened x,y pairs, x ascending in 0..255. null entries mean "identity". */
class CurveKnots(val points: FloatArray) {
    val count get() = points.size / 2

    /** Piecewise-linear evaluation, v in 0..1 -> 0..1 (matches the shader). */
    fun eval(v: Float): Float {
        val x = (v * 255f).coerceIn(0f, 255f)
        if (x <= points[0]) return points[1] / 255f
        for (i in 0 until count - 1) {
            val x0 = points[i * 2]
            val y0 = points[i * 2 + 1]
            val x1 = points[i * 2 + 2]
            val y1 = points[i * 2 + 3]
            if (x <= x1) {
                val t = if (x1 > x0) (x - x0) / (x1 - x0) else 0f
                return (y0 + t * (y1 - y0)) / 255f
            }
        }
        return points[points.size - 1] / 255f
    }

    /** Padded into 8 vec2 slots for shader uniforms; unused slots repeat last. */
    fun toUniformArray(): FloatArray {
        val out = FloatArray(16)
        for (i in 0 until 8) {
            val src = (i.coerceAtMost(count - 1)) * 2
            out[i * 2] = points[src] / 255f
            out[i * 2 + 1] = points[src + 1] / 255f
        }
        return out
    }
}

data class VeFilter(
    val name: String,
    val rgb: CurveKnots? = null,
    val red: CurveKnots? = null,
    val green: CurveKnots? = null,
    val blue: CurveKnots? = null,
    /** Added to each channel, -100..100 (zomato BrightnessSubFilter semantics). */
    val brightness: Int = 0,
    /** Multiplier around mid-gray, 1f = no-op (zomato ContrastSubFilter). */
    val contrast: Float = 1f,
    /** -100 = grayscale, 0 = no-op (zomato SaturationSubFilter percent). */
    val saturation: Float = 0f,
    /** Radial darkening strength 0..1 (mapped from zomato VignetteSubFilter radius). */
    val vignette: Float = 0f,
    /** Flat color overlay, mix factor alpha (zomato ColorOverlaySubFilter). */
    val overlayR: Float = 0f,
    val overlayG: Float = 0f,
    val overlayB: Float = 0f,
    val overlayAlpha: Float = 0f,
) {
    val isIdentity: Boolean
        get() = rgb == null && red == null && green == null && blue == null &&
            brightness == 0 && contrast == 1f && saturation == 0f &&
            vignette == 0f && overlayAlpha == 0f

    private fun identity(v: Float) = v

    /** CPU mirror of CurveFilterEffect's fragment shader (single pixel, rgb 0..1). */
    fun apply(r0: Float, g0: Float, b0: Float): FloatArray {
        var r = r0; var g = g0; var b = b0
        // per-channel tone curves
        r = rgb?.eval(r) ?: identity(r); g = rgb?.eval(g) ?: g; b = rgb?.eval(b) ?: b
        red?.let { r = it.eval(r) }; green?.let { g = it.eval(g) }; blue?.let { b = it.eval(b) }
        // brightness (value/255 add)
        val br = brightness / 255f
        r += br; g += br; b += br
        // contrast around 0.5
        r = (r - 0.5f) * contrast + 0.5f; g = (g - 0.5f) * contrast + 0.5f; b = (b - 0.5f) * contrast + 0.5f
        // saturation
        if (saturation != 0f) {
            val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val s = 1f + saturation / 100f
            r = luma + (r - luma) * s; g = luma + (g - luma) * s; b = luma + (b - luma) * s
        }
        r = r.coerceIn(0f, 1f); g = g.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        // flat overlay
        if (overlayAlpha > 0f) {
            r = r + (overlayR - r) * overlayAlpha
            g = g + (overlayG - g) * overlayAlpha
            b = b + (overlayB - b) * overlayAlpha
        }
        return floatArrayOf(r, g, b)
    }
}

private fun knots(vararg xy: Float) = CurveKnots(floatArrayOf(*xy))

/** The exact 16 presets of the app's image-editor FilterPack (+ None first). */
object VideoFilterDefs {
    val FILTERS: List<VeFilter> = listOf(
        VeFilter("None"),
        VeFilter(
            "Struck",
            rgb = knots(0f, 0f, 80f, 43f, 149f, 102f, 201f, 173f, 255f, 255f),
            red = knots(0f, 0f, 125f, 147f, 177f, 199f, 213f, 228f, 255f, 255f),
            green = knots(0f, 0f, 57f, 76f, 103f, 130f, 167f, 192f, 211f, 229f, 255f, 255f),
            blue = knots(0f, 0f, 38f, 62f, 75f, 112f, 116f, 158f, 171f, 204f, 212f, 233f, 255f, 255f),
        ),
        VeFilter(
            "Clarendon", contrast = 1.5f, brightness = -10,
            red = knots(0f, 0f, 56f, 68f, 196f, 206f, 255f, 255f),
            green = knots(0f, 0f, 46f, 77f, 160f, 200f, 255f, 255f),
            blue = knots(0f, 0f, 33f, 86f, 126f, 220f, 255f, 255f),
        ),
        VeFilter(
            "OldMan", brightness = 30, contrast = 1.3f, vignette = 0.15f,
            overlayR = 0.2f, overlayG = 0.2f, overlayB = 0.1f, overlayAlpha = 0.39f,
        ),
        VeFilter("Mars", contrast = 1.5f, brightness = 10),
        VeFilter(
            "Rise", contrast = 1.9f, brightness = 60, vignette = 0.30f,
            red = knots(0f, 0f, 45f, 64f, 170f, 190f, 255f, 255f),
            blue = knots(0f, 0f, 39f, 70f, 150f, 200f, 255f, 255f),
        ),
        VeFilter(
            "April", contrast = 1.5f, brightness = 5, vignette = 0.22f,
            red = knots(0f, 0f, 45f, 64f, 170f, 190f, 255f, 255f),
            blue = knots(0f, 0f, 39f, 70f, 150f, 200f, 255f, 255f),
        ),
        VeFilter(
            "Amazon", contrast = 1.2f,
            blue = knots(0f, 0f, 11f, 40f, 36f, 99f, 86f, 151f, 167f, 209f, 255f, 255f),
        ),
        VeFilter(
            "Starlit",
            rgb = knots(0f, 0f, 34f, 6f, 69f, 23f, 100f, 58f, 150f, 154f, 176f, 196f, 207f, 233f, 255f, 255f),
        ),
        VeFilter(
            "Whisper", contrast = 1.5f,
            rgb = knots(0f, 0f, 174f, 109f, 255f, 255f),
            red = knots(0f, 0f, 70f, 114f, 157f, 145f, 255f, 255f),
            green = knots(0f, 0f, 109f, 138f, 255f, 255f),
            blue = knots(0f, 0f, 113f, 152f, 255f, 255f),
        ),
        VeFilter(
            "Lime",
            blue = knots(0f, 0f, 165f, 114f, 255f, 255f),
        ),
        VeFilter(
            "Haan", contrast = 1.3f, brightness = 60, vignette = 0.30f,
            green = knots(0f, 0f, 113f, 142f, 255f, 255f),
        ),
        VeFilter(
            "BlueMess", brightness = 30,
            red = knots(0f, 0f, 86f, 34f, 117f, 41f, 146f, 80f, 170f, 151f, 200f, 214f, 225f, 242f, 255f, 255f),
        ),
        VeFilter("Adele", saturation = -100f),
        VeFilter("Cruz", saturation = -100f, contrast = 1.3f, brightness = 20),
        VeFilter("Metropolis", saturation = -100f, contrast = 1.7f, brightness = 70),
        VeFilter(
            "Audrey", saturation = -100f, contrast = 1.3f, brightness = 20,
            red = knots(0f, 0f, 124f, 138f, 255f, 255f),
        ),
    )
}
