/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app editor — gallery-image sticker decoder (foss flavor).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.effects

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.FileInputStream
import java.io.InputStream

/**
 * Synchronous decoder + small cache for gallery-image stickers
 * (VeOverlayItem.Kind.IMAGE). The primary warm happens on a background thread
 * right when the image is picked; renderItem() then only reads the cache. The
 * export assembler runs on its own executor, so a cache MISS there may block
 * harmlessly for one decode.
 *
 * Decoding is EXIF-corrected and downsampled to ~[MAX_DIM] px so a 50MP
 * camera shot never lands in an overlay bitmap.
 */
object ImageStickerCache {
    private const val MAX_DIM = 1280

    /** Set once from the editor activity (applicationContext resolver); the
     *  bitmap factory itself stays UI-free so any thread may call it. */
    @Volatile
    var resolver: ContentResolver? = null

    private val cache = LruCache<String, Bitmap>(4)

    /** Cached decode; null when the source cannot be decoded. */
    @Synchronized
    fun get(content: String): Bitmap? {
        cache.get(content)?.let { return it }
        val r = resolver ?: return null
        val bmp = decode(content, r) ?: return null
        cache.put(content, bmp)
        return bmp
    }

    private fun decode(content: String, resolver: ContentResolver): Bitmap? {
        try {
            val open: () -> InputStream? = {
                if (content.startsWith("content://") || content.startsWith("file://")) {
                    resolver.openInputStream(content.toUri())
                } else {
                    FileInputStream(content)
                }
            }

            // bounds pass
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // orientation (inputstream-friendly androidx exif)
            var rotation = 0
            var mirrored = false
            try {
                open()?.use {
                    val exif = ExifInterface(it)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> rotation = 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> rotation = 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> rotation = 270
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> mirrored = true
                    }
                }
            } catch (ignored: Exception) {
                // EXIF is best-effort: a broken tag must not sink the sticker
            }

            var sample = 1
            val bigger = maxOf(bounds.outWidth, bounds.outHeight)
            while (bigger / (sample * 2) >= MAX_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            if (rotation == 0 && !mirrored) return raw

            val m = Matrix().apply {
                if (rotation != 0) postRotate(rotation.toFloat())
                if (mirrored) postScale(-1f, 1f)
            }
            return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                if (it != raw) raw.recycle()
            }
        } catch (e: Exception) {
            return null
        }
    }
}
