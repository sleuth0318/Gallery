/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Single-pass GPU implementation of the Zomato FilterPack presets
 * (per-channel piecewise-linear tone curves + brightness/contrast/saturation
 * + flat overlay + vignette), as an AndroidX Media3 GlEffect so the very same
 * code path renders the live preview (CompositionPlayer) and the
 * exported file (Transformer). GPLv3, see LICENSE.
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

class CurveFilterEffect(private val filter: VeFilter) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ShaderProgram(filter, useHdr)

    private class ShaderProgram(
        private val filter: VeFilter,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(/* useHighPrecisionColorProcessing = */ useHdr, /* texturePoolCapacity = */ 1) {

        private val glProgram: GlProgram = createGlProgram()
        private var width = 1
        private var height = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                glProgram.use()
                glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                setKnots("uRgb", filter.rgb)
                setKnots("uRed", filter.red)
                setKnots("uGreen", filter.green)
                setKnots("uBlue", filter.blue)
                glProgram.setFloatUniform("uBrightness", filter.brightness / 255f)
                glProgram.setFloatUniform("uContrast", filter.contrast)
                glProgram.setFloatUniform("uSaturation", filter.saturation)
                glProgram.setFloatUniform("uVignette", filter.vignette)
                glProgram.setFloatsUniform(
                    "uOverlay", floatArrayOf(filter.overlayR, filter.overlayG, filter.overlayB, filter.overlayAlpha)
                )
                glProgram.setFloatsUniform("uResolution", floatArrayOf(width.toFloat(), height.toFloat()))
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        private fun setKnots(prefix: String, knots: CurveKnots?) {
            if (knots == null) {
                glProgram.setIntUniform("${prefix}Count", 0)
            } else {
                glProgram.setIntUniform("${prefix}Count", knots.count)
                glProgram.setFloatsUniform("${prefix}Knots", knots.toUniformArray())
            }
        }

        private fun createGlProgram(): GlProgram = try {
            GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
                setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }
        } catch (e: GlUtil.GlException) {
            throw IllegalStateException("Failed to compile curve filter shader", e)
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

            private const val FRAGMENT_SHADER = """
precision mediump float;
varying vec2 vTexCoord;
uniform sampler2D uTexSampler;
uniform vec2 uResolution;
uniform vec2 uRgbKnots[8];
uniform int uRgbCount;
uniform vec2 uRedKnots[8];
uniform int uRedCount;
uniform vec2 uGreenKnots[8];
uniform int uGreenCount;
uniform vec2 uBlueKnots[8];
uniform int uBlueCount;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uVignette;
uniform vec4 uOverlay;

float curveEval(vec2 knots[8], int count, float x) {
  if (x <= knots[0].x) return knots[0].y;
  for (int i = 0; i < 7; i++) {
    if (i >= count - 1) break;
    if (x <= knots[i + 1].x) {
      float t = (x - knots[i].x) / max(knots[i + 1].x - knots[i].x, 1e-6);
      return mix(knots[i].y, knots[i + 1].y, t);
    }
  }
  return knots[7].y;
}

void main() {
  vec4 c = texture2D(uTexSampler, vTexCoord);
  vec3 col = c.rgb;
  if (uRgbCount > 1) {
    col.r = curveEval(uRgbKnots, uRgbCount, col.r);
    col.g = curveEval(uRgbKnots, uRgbCount, col.g);
    col.b = curveEval(uRgbKnots, uRgbCount, col.b);
  }
  if (uRedCount > 1) col.r = curveEval(uRedKnots, uRedCount, col.r);
  if (uGreenCount > 1) col.g = curveEval(uGreenKnots, uGreenCount, col.g);
  if (uBlueCount > 1) col.b = curveEval(uBlueKnots, uBlueCount, col.b);

  col += uBrightness;
  col = (col - 0.5) * uContrast + 0.5;

  float luma = dot(col, vec3(0.2126, 0.7152, 0.0722));
  col = mix(vec3(luma), col, clamp(1.0 + uSaturation / 100.0, 0.0, 3.0));
  col = clamp(col, 0.0, 1.0);

  if (uOverlay.a > 0.0) col = mix(col, uOverlay.rgb, uOverlay.a);

  if (uVignette > 0.001) {
    vec2 uv = vTexCoord - 0.5;
    uv.x *= uResolution.x / uResolution.y;
    float d = length(uv) * 2.0;
    float f = smoothstep(1.05, 0.35, d);
    col *= 1.0 - uVignette * (1.0 - f);
  }

  gl_FragColor = vec4(col, c.a);
}
"""
        }
    }
}
