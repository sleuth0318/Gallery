/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Single-pass GPU implementation of the "advanced" Adjust-tool slots (the ones
 * Media3 has no built-in Effect for): exposure, gamma, temperature, shadows,
 * highlights, whites, blacks, clarity and sharpness. Written as an AndroidX
 * Media3 GlEffect so the same code path renders the live preview
 * (CompositionPlayer) and the exported file (Transformer).
 *
 * Tonal masks follow the industry-standard approach (luminance-weighted
 * zones); clarity/sharpness are unsharp-mask variants over neighboring texels.
 * GPLv3, see LICENSE.
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
import com.goodwy.gallery.videoeditor.model.VeAdjustments

class AdjustEffect(private val adj: VeAdjustments) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ShaderProgram(adj, useHdr)

    private class ShaderProgram(
        private val adj: VeAdjustments,
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
                glProgram.setFloatsUniform(
                    "uTexelSize",
                    floatArrayOf(1f / width.coerceAtLeast(1), 1f / height.coerceAtLeast(1)),
                )
                val s = 1f / 100f
                glProgram.setFloatUniform("uExposure", adj.exposure * s)
                glProgram.setFloatUniform("uGamma", adj.gamma * s)
                glProgram.setFloatUniform("uTemperature", adj.temperature * s)
                glProgram.setFloatUniform("uShadows", adj.shadows * s)
                glProgram.setFloatUniform("uHighlights", adj.highlights * s)
                glProgram.setFloatUniform("uWhites", adj.whites * s)
                glProgram.setFloatUniform("uBlacks", adj.blacks * s)
                glProgram.setFloatUniform("uClarity", adj.clarity * s)
                glProgram.setFloatUniform("uSharpness", adj.sharpness * s)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
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
            throw IllegalStateException("Failed to compile adjust shader", e)
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
uniform vec2 uTexelSize;
uniform float uExposure;
uniform float uGamma;
uniform float uTemperature;
uniform float uShadows;
uniform float uHighlights;
uniform float uWhites;
uniform float uBlacks;
uniform float uClarity;
uniform float uSharpness;

float lumaOf(vec3 c) {
  return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
  vec4 src = texture2D(uTexSampler, vTexCoord);
  vec3 col = src.rgb;
  float luma = lumaOf(col);

  // --- detail: sharpness (unsharp mask over immediate neighbors) ---
  if (uSharpness > 0.001 || uSharpness < -0.001) {
    vec3 l = texture2D(uTexSampler, vTexCoord - vec2(uTexelSize.x, 0.0)).rgb;
    vec3 r = texture2D(uTexSampler, vTexCoord + vec2(uTexelSize.x, 0.0)).rgb;
    vec3 u = texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexelSize.y)).rgb;
    vec3 d = texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexelSize.y)).rgb;
    vec3 blur = (l + r + u + d) * 0.25;
    col = col + (col - blur) * uSharpness * 2.0;
    luma = lumaOf(col);
  }

  // --- detail: clarity (midtone local contrast over a wider footprint) ---
  if (uClarity > 0.001 || uClarity < -0.001) {
    vec2 t = uTexelSize * 2.5;
    float avg = 0.0;
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2(-t.x, -t.y)).rgb);
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2( t.x, -t.y)).rgb);
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2(-t.x,  t.y)).rgb);
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2( t.x,  t.y)).rgb);
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2(-t.x,  0.0)).rgb);
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2( t.x,  0.0)).rgb);
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2( 0.0, -t.y)).rgb);
    avg += lumaOf(texture2D(uTexSampler, vTexCoord + vec2( 0.0,  t.y)).rgb);
    avg *= 0.125;
    float midMask = clamp(1.0 - abs(2.0 * luma - 1.0), 0.0, 1.0);
    float delta = (luma - avg) * uClarity * midMask * 1.5;
    col += vec3(delta);
    luma = lumaOf(clamp(col, 0.0, 1.0));
  }

  // --- exposure (photographic, +/-2 EV over the slider range) ---
  if (uExposure > 0.001 || uExposure < -0.001) {
    col *= pow(2.0, uExposure * 2.0);
  }

  // --- temperature (warm/cool axis) ---
  if (uTemperature > 0.001 || uTemperature < -0.001) {
    col.r += uTemperature * 0.12;
    col.b -= uTemperature * 0.12;
  }

  col = clamp(col, 0.0, 1.0);
  luma = lumaOf(col);

  // --- gamma (midtone power curve; positive brightens mids) ---
  if (uGamma > 0.001 || uGamma < -0.001) {
    float inv = 1.0 / clamp(1.0 + uGamma * 0.8, 0.28, 3.6);
    col = pow(col, vec3(inv));
    luma = lumaOf(col);
  }

  // --- tonal zones: shadows / highlights / whites / blacks (luma-masked) ---
  if (uShadows > 0.001 || uShadows < -0.001) {
    col *= 1.0 + uShadows * (1.0 - smoothstep(0.0, 0.45, luma)) * 0.9;
  }
  if (uHighlights > 0.001 || uHighlights < -0.001) {
    col *= 1.0 + uHighlights * smoothstep(0.55, 1.0, luma) * 0.9;
  }
  if (uWhites > 0.001 || uWhites < -0.001) {
    col *= 1.0 + uWhites * smoothstep(0.75, 1.0, luma) * 0.6;
  }
  if (uBlacks > 0.001 || uBlacks < -0.001) {
    col *= 1.0 + uBlacks * (1.0 - smoothstep(0.0, 0.25, luma)) * 0.6;
  }

  gl_FragColor = vec4(clamp(col, 0.0, 1.0), src.a);
}
"""
        }
    }
}
