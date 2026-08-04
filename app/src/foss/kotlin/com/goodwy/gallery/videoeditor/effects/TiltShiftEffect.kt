/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Focus tool: single-pass masked blur (full-frame gaussian, radial or linear
 * tilt-shift) as a Media3 GlEffect — identical code path for preview & export.
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
import com.goodwy.gallery.videoeditor.model.VeFocusMode

class TiltShiftEffect(
    private val mode: VeFocusMode,
    private val strength: Float,   // 0..1
    private val centerX: Float,    // normalized 0..1
    private val centerY: Float,    // normalized 0..1 (from the top, converted here)
) : GlEffect {

    init {
        require(mode != VeFocusMode.NONE) { "TiltShiftEffect only makes sense for a non-NONE mode" }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ShaderProgram(mode, strength.coerceIn(0f, 1f), centerX, centerY, useHdr)

    private class ShaderProgram(
        private val mode: VeFocusMode,
        private val strength: Float,
        private val centerX: Float,
        private val centerY: Float,
        useHdr: Boolean,
    ) : BaseGlShaderProgram(useHdr, 1) {

        private val glProgram = createGlProgram()
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
                glProgram.setIntUniform(
                    "uMode",
                    when (mode) {
                        VeFocusMode.GAUSSIAN -> 1
                        VeFocusMode.RADIAL -> 2
                        VeFocusMode.LINEAR -> 3
                        VeFocusMode.NONE -> 0
                    }
                )
                glProgram.setFloatsUniform(
                    "uResolution", floatArrayOf(width.toFloat(), height.toFloat())
                )
                glProgram.setFloatsUniform("uCenter", floatArrayOf(centerX, 1f - centerY))
                glProgram.setFloatUniform("uStrength", strength)
                glProgram.setFloatUniform("uRadius", 0.55f - 0.30f * strength)
                glProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
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
            throw IllegalStateException("Failed to compile tilt-shift shader", e)
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
uniform vec2 uCenter;
uniform int uMode;      // 1 = gaussian, 2 = radial, 3 = linear
uniform float uStrength;
uniform float uRadius;

void main() {
  vec4 sharp = texture2D(uTexSampler, vTexCoord);

  float blurPx = 2.0 + 14.0 * uStrength;
  vec2 texel = blurPx / uResolution;

  vec3 sum = sharp.rgb * 0.204;
  sum += texture2D(uTexSampler, vTexCoord + vec2(texel.x, 0.0)).rgb * 0.119;
  sum += texture2D(uTexSampler, vTexCoord - vec2(texel.x, 0.0)).rgb * 0.119;
  sum += texture2D(uTexSampler, vTexCoord + vec2(0.0, texel.y)).rgb * 0.119;
  sum += texture2D(uTexSampler, vTexCoord - vec2(0.0, texel.y)).rgb * 0.119;
  vec2 diag = texel * 0.7071;
  sum += texture2D(uTexSampler, vTexCoord + diag).rgb * 0.087;
  sum += texture2D(uTexSampler, vTexCoord - diag).rgb * 0.087;
  sum += texture2D(uTexSampler, vTexCoord + vec2(diag.x, -diag.y)).rgb * 0.087;
  sum += texture2D(uTexSampler, vTexCoord - vec2(diag.x, -diag.y)).rgb * 0.087;
  sum += texture2D(uTexSampler, vTexCoord + texel * 2.0).rgb * 0.028;
  sum += texture2D(uTexSampler, vTexCoord - texel * 2.0).rgb * 0.028;
  sum += texture2D(uTexSampler, vTexCoord + vec2(texel.x, -texel.y) * 2.0).rgb * 0.028;
  sum += texture2D(uTexSampler, vTexCoord + vec2(-texel.x, texel.y) * 2.0).rgb * 0.028;

  float aspect = uResolution.x / uResolution.y;
  vec2 delta = vTexCoord - uCenter;
  delta.x *= aspect;

  float mask = 1.0;
  if (uMode == 2) {
    mask = smoothstep(uRadius, uRadius + 0.35, length(delta) * 2.0);
  } else if (uMode == 3) {
    mask = smoothstep(uRadius, uRadius + 0.35, abs(delta.y) * 2.0);
  }

  gl_FragColor = vec4(mix(sharp.rgb, sum, clamp(mask, 0.0, 1.0)), sharp.a);
}
"""
        }
    }
}
