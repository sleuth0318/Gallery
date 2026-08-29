/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Single-pass GPU implementation of the Zomato FilterPack presets
 * (per-channel piecewise-linear tone curves + brightness/contrast/saturation
 * + flat overlay + vignette), as an AndroidX Media3 GlEffect so the very same
 * code path renders the live preview (CompositionPlayer) and the
 * exported file (Transformer). GPLv3, see LICENSE.
 *
 * NOTE: the whole preset is BAKED INTO THE GENERATED FRAGMENT SHADER as
 * literals — one static, straight-line program per preset, zero data
 * uniforms except the sampler (and uResolution when a vignette is present).
 * The original implementation passed curves through "vec2 knots[8]" uniform
 * arrays + a dynamic loop, and on some drivers that path died with
 * ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED (array-uniform "[0]"-reporting
 * quirks / dynamic indexing). Static GLSL is what every driver compiles.
 */
package com.goodwy.gallery.videoeditor.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.goodwy.gallery.videoeditor.model.CurveKnots
import com.goodwy.gallery.videoeditor.model.VeFilter
import java.util.Locale

class CurveFilterEffect(private val filter: VeFilter) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ShaderProgram(filter, useHdr)

    private class ShaderProgram(
        private val filter: VeFilter,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(/* useHighPrecisionColorProcessing = */ useHdr, /* texturePoolCapacity = */ 1) {

        private val glProgram: GlProgram = createGlProgram()
        private val resolvedUniformNames = HashMap<String, String>()
        private var width = 1
        private var height = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        /**
         * Drivers report some uniform names differently; GlProgram keys its
         * uniform map by the reported name verbatim and NPEs on a miss
         * (checkNotNull). Resolve once per program; a total miss fails LOUDLY
         * with the uniform name instead of a bare NPE on the GL thread.
         */
        private fun uniformName(name: String): String {
            resolvedUniformNames[name]?.let { return it }
            val resolved = when {
                glProgram.getUniformLocation(name) != -1 -> name
                glProgram.getUniformLocation("$name[0]") != -1 -> "$name[0]"
                else -> throw VideoFrameProcessingException("CurveFilterEffect: no uniform '$name'")
            }
            resolvedUniformNames[name] = resolved
            return resolved
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform(uniformName("uTexSampler"), inputTexId, 0)
                if (filter.vignette > 0.001f) {
                    glProgram.setFloatsUniform(
                        uniformName("uResolution"),
                        floatArrayOf(width.toFloat(), height.toFloat()),
                    )
                }
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
            } catch (e: VideoFrameProcessingException) {
                throw e
            } catch (t: Throwable) {
                throw VideoFrameProcessingException(
                    "CurveFilterEffect: ${t.javaClass.simpleName}: ${t.message}", presentationTimeUs
                )
            }
        }

        private fun createGlProgram(): GlProgram = try {
            GlProgram(VERTEX_SHADER, buildFragmentShader(filter)).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }
        } catch (e: GlUtil.GlException) {
            throw IllegalStateException("Failed to compile curve filter shader", e)
        }

        // -------------------------------------------- static GLSL generation

        private fun fl(v: Float): String = String.format(Locale.US, "%.6f", v)

        /**
         * Emits a clamped piecewise-linear curve evaluation for [selector]
         * (e.g. "col.r"), equivalent to the original curveEval() with the
         * knots padded by repeating the last one.
         */
        private fun appendCurve(sb: StringBuilder, knots: CurveKnots, selector: String) {
            val n = knots.count
            if (n < 2) return
            val xs = FloatArray(n) { knots.points[it * 2] / 255f }
            val ys = FloatArray(n) { knots.points[it * 2 + 1] / 255f }
            sb.append("  {\n")
            sb.append("    float x = ").append(selector).append(";\n")
            sb.append("    float y = ").append(fl(ys[n - 1])).append(";\n")
            sb.append("    if (x <= ").append(fl(xs[0])).append(") {\n")
            sb.append("      y = ").append(fl(ys[0])).append(";\n")
            sb.append("    }\n")
            for (i in 0 until n - 1) {
                sb.append("    else if (x <= ").append(fl(xs[i + 1])).append(") {\n")
                val dx = xs[i + 1] - xs[i]
                if (dx > 1e-6f) {
                    sb.append("      y = mix(").append(fl(ys[i])).append(", ").append(fl(ys[i + 1]))
                        .append(", (x - ").append(fl(xs[i])).append(") / ").append(fl(dx)).append(");\n")
                } else {
                    sb.append("      y = ").append(fl(ys[i + 1])).append(";\n")
                }
                sb.append("    }\n")
            }
            sb.append("    ").append(selector).append(" = clamp(y, 0.0, 1.0);\n")
            sb.append("  }\n")
        }

        /** Same math as the original dynamic-uniform shader, but fully literal. */
        private fun buildFragmentShader(f: VeFilter): String {
            val sb = StringBuilder()
            sb.append("precision mediump float;\n")
            sb.append("varying vec2 vTexCoord;\n")
            sb.append("uniform sampler2D uTexSampler;\n")
            sb.append("uniform vec2 uResolution;\n")
            sb.append("void main() {\n")
            sb.append("  vec4 c = texture2D(uTexSampler, vTexCoord);\n")
            sb.append("  vec3 col = c.rgb;\n")
            f.rgb?.let {
                appendCurve(sb, it, "col.r")
                appendCurve(sb, it, "col.g")
                appendCurve(sb, it, "col.b")
            }
            f.red?.let { appendCurve(sb, it, "col.r") }
            f.green?.let { appendCurve(sb, it, "col.g") }
            f.blue?.let { appendCurve(sb, it, "col.b") }
            if (f.brightness != 0) {
                sb.append("  col += ").append(fl(f.brightness / 255f)).append(";\n")
            }
            if (f.contrast != 1f) {
                sb.append("  col = (col - 0.5) * ").append(fl(f.contrast)).append(" + 0.5;\n")
            }
            if (f.saturation != 0f) {
                sb.append("  {\n")
                sb.append("    float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));\n")
                sb.append("    col = mix(vec3(luma), col, ")
                    .append(fl((1f + f.saturation / 100f).coerceIn(0f, 3f))).append(");\n")
                sb.append("  }\n")
            }
            sb.append("  col = clamp(col, 0.0, 1.0);\n")
            if (f.overlayAlpha > 0f) {
                sb.append("  col = mix(col, vec3(").append(fl(f.overlayR)).append(", ")
                    .append(fl(f.overlayG)).append(", ").append(fl(f.overlayB)).append("), ")
                    .append(fl(f.overlayAlpha)).append(");\n")
            }
            if (f.vignette > 0.001f) {
                sb.append("  {\n")
                sb.append("    vec2 uv = vTexCoord - 0.5;\n")
                sb.append("    uv.x *= uResolution.x / uResolution.y;\n")
                sb.append("    float d = length(uv) * 2.0;\n")
                sb.append("    float fn = smoothstep(1.05, 0.35, d);\n")
                sb.append("    col *= 1.0 - ").append(fl(f.vignette)).append(" * (1.0 - fn);\n")
                sb.append("  }\n")
            }
            sb.append("  gl_FragColor = vec4(col, c.a);\n")
            sb.append("}\n")
            return sb.toString()
        }

        companion object {
            // Media3 convention (see vertex_shader_transformation_es2.glsl): the only
            // vertex attribute is the NDC quad position; texture UVs are derived
            // from it by mapping [-1,1] -> [0,1].
            private const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexCoord;
void main() {
  gl_Position = aFramePosition;
  vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""
        }
    }
}
