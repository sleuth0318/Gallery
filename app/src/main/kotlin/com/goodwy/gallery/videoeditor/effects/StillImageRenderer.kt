/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Still-image EXPORT renderer for the unified media editor. Feeds ONE bitmap
 * (the pre-decoded, EXIF-normalized input frame) through the media3 frame
 * processor effect chain — the SAME chain the CompositionPlayer preview runs
 * — and reads the final GL frame back into a Bitmap, so the saved image is
 * pixel-identical to what the editor previewed.
 *
 * Why this exists: media3 1.9.2's inspector FrameExtractor cannot extract
 * frames from IMAGE MediaItems — its internal player is built with a custom
 * renderers factory containing only a video renderer, so image tracks have
 * no renderer and the extraction future never completes (observed on-device
 * as the "Rendering image…" dialog spinning forever). Driving
 * DefaultVideoFrameProcessor directly with queueInputBitmap() has no such
 * limitation.
 *
 * The vertical-flip matrix before the capture pass replicates media3's own
 * frame reader (FrameExtractorInternal.buildVideoEffects): upstream frames
 * are Y-flipped relative to Bitmap memory; mirroring once makes the GL
 * readback produce an upright bitmap.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3. See LICENSE.
 */
package com.goodwy.gallery.videoeditor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.TimestampIterator
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.PassthroughShaderProgram
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(UnstableApi::class)
object StillImageRenderer {

    fun interface Callback {
        /** Exactly one call, from a background thread; error is a short
         *  machine-readable reason (e.g. "gl: …") when bitmap is null. */
        fun onRendered(bitmap: Bitmap?, error: String?)
    }

    /**
     * Renders [inputFile] through [effects] (geometry/crop/filter/adjust/
     * focus/overlays — the same list VideoEffectsAssembler produces for export)
     * and delivers the finished still. [width]/[height] must be the input
     * file's own dimensions.
     */
    private const val TAG = "StillImageRenderer"

    fun render(
        context: Context,
        inputFile: File,
        width: Int,
        height: Int,
        effects: List<Effect>,
        callback: Callback,
    ) {
        val worker = Executors.newSingleThreadExecutor()
        val delivered = AtomicBoolean(false)

        fun deliver(processor: DefaultVideoFrameProcessor?, bitmap: Bitmap?, error: String?) {
            if (!delivered.compareAndSet(false, true)) return
            if (bitmap == null) Log.e(TAG, "render failed: $error")
            try {
                processor?.release()
            } catch (e: Exception) {
                // best effort cleanup
            }
            worker.shutdown()
            callback.onRendered(bitmap, error)
        }

        worker.execute {
            var processor: DefaultVideoFrameProcessor? = null
            var pendingInput: Bitmap? = null
            val listener = object : VideoFrameProcessor.Listener {
                override fun onError(exception: VideoFrameProcessingException) {
                    Log.e(TAG, "pipeline error", exception)
                    deliver(processor, null, "gl: ${exception.message?.take(120)}")
                }

                override fun onInputStreamRegistered(inputType: Int, format: Format, effects: List<Effect>) {
                    // Fired right after the frame processor's internal
                    // configure() re-opens the input condition — the ONLY
                    // point where queueInputBitmap is guaranteed not to be
                    // rejected (verified in the 1.9.2 sources: the public
                    // registerInputStream returns while the condition is still
                    // closed; an immediate queue is a race it usually loses).
                    val proc = processor ?: return
                    val input = pendingInput ?: return
                    try {
                        if (!proc.queueInputBitmap(input, SingleTimestampIterator())) {
                            deliver(proc, null, "queue rejected")
                            return
                        }
                        // release the tail of the pipeline; the capture effect
                        // delivers the bitmap (once) from the GL pass
                        try {
                            proc.signalEndOfInput()
                        } catch (e: Exception) {
                            // non-fatal: the one frame may already be through
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "queue failed", e)
                        deliver(proc, null, "queue: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                    }
                }
            }
            try {
                processor = DefaultVideoFrameProcessor.Factory.Builder().build()
                    .create(
                        /* context = */ context,
                        /* debugViewProvider = */ DebugViewProvider.NONE,
                        /* outputColorInfo = */ ColorInfo.SDR_BT709_LIMITED,
                        /* renderFramesAutomatically = */ true,
                        /* listenerExecutor = */ worker,
                        /* listener = */ listener,
                    )
            } catch (e: Exception) {
                Log.e(TAG, "create() failed", e)
                deliver(null, null, "create: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                return@execute
            }

            val input = try {
                BitmapFactory.decodeFile(inputFile.absolutePath)
            } catch (e: Exception) {
                null
            }
            if (input == null) {
                deliver(processor, null, "input decode")
                return@execute
            }
            pendingInput = input

            val proc = processor
            val mirrorY = MatrixTransformation { Matrix().apply { setScale(1f, -1f) } }
            // onCaptured runs on the frame processor's own GL thread — hop to
            // the worker before delivering: release() must not be called from
            // the GL thread (it blocks on the internal executor, i.e. itself).
            val capture = BitmapCaptureEffect { bitmap, captureError ->
                worker.execute { deliver(proc, bitmap, captureError) }
            }
            val format = Format.Builder()
                .setWidth(width)
                .setHeight(height)
                .setSampleMimeType(MimeTypes.IMAGE_RAW) // media3's own bitmap-input convention
                .setColorInfo(ColorInfo.SRGB_BT709_FULL) // FrameInfo ctor REQUIRES colorInfo set
                .build()
            try {
                proc.registerInputStream(
                    VideoFrameProcessor.INPUT_TYPE_BITMAP,
                    format,
                    effects + mirrorY + capture,
                    /* offsetToAddUs = */ 0L,
                )
                // the bitmap is queued from onInputStreamRegistered (see above)
            } catch (e: Exception) {
                Log.e(TAG, "register failed", e)
                deliver(proc, null, "register: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            }
        }
    }

    /** One-shot iterator: the single input frame sits at t=0. */
    private class SingleTimestampIterator : TimestampIterator {
        private var used = false
        override fun hasNext() = !used
        override fun next(): Long {
            used = true
            return 0L
        }

        override fun copyOf() = SingleTimestampIterator()
    }
}

/** Terminal GlEffect: reads the arriving (final-chain) texture into a Bitmap.
 *  Follows media3's own FrameReader readback (SDR path): focus the input FBO,
 *  glReadPixels as RGBA/UNSIGNED_BYTE (memory order matches ARGB_8888) and
 *  consume the frame without forwarding it. */
@androidx.annotation.OptIn(UnstableApi::class)
private class BitmapCaptureEffect(private val onCaptured: (Bitmap?, String?) -> Unit) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CaptureShaderProgram(onCaptured)
}

@androidx.annotation.OptIn(UnstableApi::class)
private class CaptureShaderProgram(private val onCaptured: (Bitmap?, String?) -> Unit) : PassthroughShaderProgram() {

    private var captured = false

    override fun queueInputFrame(
        glObjectsProvider: GlObjectsProvider,
        inputTexture: GlTextureInfo,
        presentationTimeUs: Long,
    ) {
        if (!captured) {
            captured = true
            val buffer = ByteBuffer.allocateDirect(inputTexture.width * inputTexture.height * 4)
            try {
                GlUtil.focusFramebufferUsingCurrentContext(inputTexture.fboId, inputTexture.width, inputTexture.height)
                GlUtil.checkGlError()
                GLES20.glReadPixels(
                    /* x = */ 0, /* y = */ 0,
                    inputTexture.width, inputTexture.height,
                    /* format = */ GLES20.GL_RGBA,
                    /* type = */ GLES20.GL_UNSIGNED_BYTE,
                    buffer,
                )
                GlUtil.checkGlError()
                val bitmap = Bitmap.createBitmap(inputTexture.width, inputTexture.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                onCaptured(bitmap, null)
            } catch (e: Exception) {
                Log.e("StillImageRenderer", "capture failed (fbo=${inputTexture.fboId}, tex=${inputTexture.texId})", e)
                onCaptured(null, "capture: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }
        // terminal sink: consume the frame (mirrors media3's FrameReader)
        getInputListener().onInputFrameProcessed(inputTexture)
    }
}
