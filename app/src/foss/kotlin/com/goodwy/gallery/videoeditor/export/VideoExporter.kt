/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Thin wrapper around AndroidX Media3 Transformer: renders the composed
 * EditedMediaItem (trim + full effect chain + optional audio removal) to a
 * temporary MP4 and reports determinate progress. Apache-2.0 dependency only,
 * safe for the foss flavor. GPLv3, see LICENSE.
 */
package com.goodwy.gallery.videoeditor.export

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    interface Callback {
        fun onProgress(percent: Int)
        fun onCompleted(output: File)
        fun onError(message: String)
        fun onCancelled()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var running = false
    private var cancelled = false

    val isRunning get() = running

    fun export(
        mediaItem: MediaItem,
        videoEffects: List<Effect>,
        removeAudio: Boolean,
        outputFile: File,
        callback: Callback,
    ) {
        cancelInternal()
        cancelled = false

        if (outputFile.exists()) outputFile.delete()

        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(removeAudio)
            .setEffects(
                Effects(
                    /* audioProcessors = */ ImmutableList.of(),
                    /* videoEffects = */ ImmutableList.copyOf(videoEffects),
                )
            )
            .build()

        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = false
                    if (!cancelled) callback.onCompleted(outputFile)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    running = false
                    if (cancelled) {
                        callback.onCancelled()
                    } else {
                        callback.onError(exportException.message ?: exportException.toString())
                    }
                }
            })
            .build()

        transformer = t
        running = true
        t.start(edited, outputFile.absolutePath)
        pollProgress(callback)
    }

    private fun pollProgress(callback: Callback) {
        if (!running) return
        val t = transformer ?: return
        val state = t.getProgress(progressHolder)
        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
            callback.onProgress(progressHolder.progress.coerceIn(0, 100))
        }
        handler.postDelayed({ pollProgress(callback) }, PROGRESS_POLL_MS)
    }

    fun cancel() {
        cancelled = true
        cancelInternal()
    }

    private fun cancelInternal() {
        running = false
        try {
            transformer?.cancel()
        } catch (ignored: Exception) {
        }
        transformer = null
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        private const val PROGRESS_POLL_MS = 250L
    }
}
