/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app MEDIA editor — one unified editor for VIDEO and IMAGE content.
 * Functional FOSS re-implementation of the tool set that Simple Mobile Tools'
 * Simple Gallery exposed via the (non-free) img.ly SDK wrapper — rebuilt on
 * AndroidX Media3 preview/transformer export.
 *
 * M19 unification: this activity replaced the legacy image editor
 * (EditActivity and the imageeditor package, removed). Images are pre-decoded with Glide
 * (EXIF-aware, size-capped, bundled-codec friendly), wrapped as a still-frame
 * Composition for preview, and exported back to a still image file via
 * StillImageRenderer (its own media3 frame-processor drive) running the SAME
 * effect chain — so
 * preview == export for images just like for video. Trim, the transport row
 * and double-tap play/pause are video-only and are hidden for images.
 * The camera-action.CROP contract is preserved: launched that way the editor
 * opens directly in the free Transform window and confirm returns the
 * cropped image with result semantics.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See LICENSE for the full text.
 */
package com.goodwy.gallery.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.goodwy.commons.dialogs.ColorPickerDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.ActivityVideoEditBinding
import com.goodwy.gallery.dialogs.SaveAsDialog
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.ensureWritablePath
import com.goodwy.gallery.extensions.fixDateTaken
import com.goodwy.gallery.extensions.openEditor
import com.goodwy.gallery.extensions.proposeNewFilePath
import com.goodwy.gallery.extensions.resolveUriScheme
import com.goodwy.gallery.extensions.shareMediumPath
import com.goodwy.gallery.helpers.getPermissionToRequest
import com.goodwy.gallery.videoeditor.effects.ImageStickerCache
import com.goodwy.gallery.videoeditor.effects.OverlayBitmapFactory
import com.goodwy.gallery.videoeditor.effects.StillImageRenderer
import com.goodwy.gallery.videoeditor.effects.VideoEffectsAssembler
import com.goodwy.gallery.videoeditor.export.VideoExporter
import com.goodwy.gallery.videoeditor.panels.VeGalleryPickerDialog
import com.goodwy.gallery.videoeditor.panels.VeHsvSwatchAdapter
import com.goodwy.gallery.videoeditor.model.EditHistory
import com.goodwy.gallery.videoeditor.model.VeAdjustments
import com.goodwy.gallery.videoeditor.model.VeAspectOption
import com.goodwy.gallery.videoeditor.model.VeCropRect
import com.goodwy.gallery.videoeditor.model.VeFocusMode
import com.goodwy.gallery.videoeditor.model.VeOverlayItem
import com.goodwy.gallery.videoeditor.model.VeBrushStroke
import com.goodwy.gallery.videoeditor.model.VideoEditState
import com.goodwy.gallery.videoeditor.model.VideoFilterDefs
import com.goodwy.gallery.videoeditor.panels.VeAdjustAdapter
import com.goodwy.gallery.videoeditor.panels.VeAdjustItem
import com.goodwy.gallery.videoeditor.panels.VeChipAdapter
import com.goodwy.gallery.videoeditor.panels.VeEmojiAdapter
import com.goodwy.gallery.videoeditor.panels.VeShapeAdapter
import com.goodwy.gallery.videoeditor.panels.VeThumbAdapter
import com.goodwy.gallery.videoeditor.panels.VeThumbEntry
import com.goodwy.gallery.videoeditor.panels.VeTool
import com.goodwy.gallery.videoeditor.panels.VeToolAdapter
import com.goodwy.gallery.videoeditor.views.OverlayStageView
import com.goodwy.gallery.videoeditor.views.TrimSliderView
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("NotifyDataSetChanged")
class VideoEditActivity : SimpleActivity(), OverlayStageView.Listener, TrimSliderView.Listener {

    companion object {
        private const val TAG = "VideoEdit"

        private const val TOOL_TRIM = "trim"
        private const val TOOL_TRANSFORM = "transform"
        private const val TOOL_FILTER = "filter"
        private const val TOOL_ADJUST = "adjust"
        private const val TOOL_FOCUS = "focus"
        private const val TOOL_STICKER = "sticker"
        private const val TOOL_TEXT = "text"
        private const val TOOL_BRUSH = "brush"
        private const val TOOL_OVERLAY = "overlay"

        // sticker tool sub-features (chips inside the sticker panel)
        private const val VE_STICKER_GALLERY = "gallery"
        private const val VE_STICKER_EMOJI = "emoji"
        private const val VE_STICKER_SHAPES = "shapes"

        /** Brush color panel preset strip (color_panel.png); position 0 of the
         *  strip is the pipette cell, these follow it. */
        private val HSV_SWATCHES = listOf(
            0xFFFFFFFF.toInt(), 0xFF767676.toInt(), 0xFF000000.toInt(),
            0xFF6FCFEF.toInt(), 0xFF6A8DFF.toInt(), 0xFF8A68F6.toInt(),
            0xFFBE66F2.toInt(), 0xFFF56FB8.toInt(), 0xFFF44336.toInt(),
        )

        private const val POLL_MS = 200L

        /** Third-party camera crop-contract action (kept from the legacy image editor). */
        private const val CROP_ACTION = "com.android.camera.action.CROP"

        /** Classic crop-contract extra ("crop" = "true"), see the old EditActivity. */
        private const val CROP_EXTRA = "crop"

        /** Still images play as a pseudo-video of this length for preview; the
         *  transport is hidden, so any sane constant works. */
        private const val IMAGE_PSEUDO_DURATION_MS = 5_000L

        /** Images are pre-decoded no larger than this (texture-safe, chosen to
         *  sit within media3's image loader caps so preview == export). */
        private const val MAX_IMAGE_DIM = 4096

        /** Hard cap on a still-image GL render before it is declared failed. */
        private const val STILL_RENDER_TIMEOUT_MS = 45_000L

        // image save-as format choices (crop contract & overwrite keep the
        // source format — bytes must always match the original extension)

        /** Min interval between live (slider-drag) composition pushes. */
        private const val LIVE_PUSH_MS = 120L

        private val TOOLS = listOf(
            VeTool(TOOL_TRIM, R.string.ve_trim, R.drawable.ic_ve_trim),
            VeTool(TOOL_TRANSFORM, R.string.ve_transform, R.drawable.ic_crop_rotate_vector),
            VeTool(TOOL_FILTER, R.string.ve_filter, R.drawable.ic_photo_filter_vector),
            VeTool(TOOL_ADJUST, R.string.ve_adjust, R.drawable.ic_ve_adjust),
            VeTool(TOOL_FOCUS, R.string.ve_focus, R.drawable.ic_ve_focus),
            VeTool(TOOL_STICKER, R.string.ve_sticker, R.drawable.ic_ve_sticker),
            VeTool(TOOL_TEXT, R.string.ve_text, R.drawable.ic_ve_text),
            VeTool(TOOL_BRUSH, R.string.ve_brush, R.drawable.ic_easel),
            VeTool(TOOL_OVERLAY, R.string.ve_overlay, R.drawable.ic_ve_overlay),
        )

        // Text tool panel actions + sub-panels
        private const val TEXT_ACTION_ADD = "add"
        private const val TEXT_ACTION_DELETE = "delete"
        private const val TEXT_ACTION_FONT = "font"
        private const val TEXT_ACTION_COLOR = "color"
        private const val TEXT_ACTION_BACKGROUND = "background"
        private const val TEXT_ACTION_ALIGN = "align"

        private const val TEXT_SUB_FONT = "font"
        private const val TEXT_SUB_COLOR = "color"
        private const val TEXT_SUB_BG = "background"
        private const val TEXT_SUB_ALIGN = "align"

        private val BASIC_COLORS = listOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF9E9E9E.toInt(),
            0xFFF44336.toInt(), 0xFFFF9800.toInt(), 0xFFFFEB3B.toInt(),
            0xFF4CAF50.toInt(), 0xFF009688.toInt(), 0xFF00BCD4.toInt(),
            0xFF2196F3.toInt(), 0xFF9C27B0.toInt(), 0xFFE91E63.toInt(),
            0xFF795548.toInt(),
        )

        private val EMOJIS = listOf(
            "😀", "😄", "😁", "😂", "🤣", "😊", "😍", "😎", "😜", "🤔", "😴", "🤯",
            "😭", "😡", "🥳", "👍", "👎", "❤️", "🔥", "⭐", "🎉", "💯", "⚡", "🌈",
        )

        private val FONTS: List<Pair<String, String?>> = listOf(
            "Default" to null,
            "Condensed" to "sans-serif-condensed",
            "Serif" to "serif",
            "Mono" to "monospace",
            "Serif mono" to "serif-monospace",
            "Casual" to "casual",
            "Cursive" to "cursive",
            "Black" to "sans-serif-black",
            "Light" to "sans-serif-light",
            "Medium" to "sans-serif-medium",
            "Thin" to "sans-serif-thin",
        )

        // Adjust tool slot keys
        private const val ADJ_RESET = "reset"
        private const val ADJ_BRIGHTNESS = "brightness"
        private const val ADJ_CONTRAST = "contrast"
        private const val ADJ_SATURATION = "saturation"
        private const val ADJ_CLARITY = "clarity"
        private const val ADJ_SHADOWS = "shadows"
        private const val ADJ_HIGHLIGHTS = "highlights"
        private const val ADJ_EXPOSURE = "exposure"
        private const val ADJ_GAMMA = "gamma"
        private const val ADJ_BLACKS = "blacks"
        private const val ADJ_WHITES = "whites"
        private const val ADJ_TEMPERATURE = "temperature"
        private const val ADJ_SHARPNESS = "sharpness"

        private val ADJUST_ITEMS = listOf(
            VeAdjustItem(ADJ_RESET, R.string.ve_reset, R.drawable.ic_ve_adj_reset),
            VeAdjustItem(ADJ_BRIGHTNESS, R.string.ve_brightness, R.drawable.ic_ve_adj_brightness),
            VeAdjustItem(ADJ_CONTRAST, R.string.ve_contrast, R.drawable.ic_ve_adj_contrast),
            VeAdjustItem(ADJ_SATURATION, R.string.ve_saturation, R.drawable.ic_ve_adj_saturation),
            VeAdjustItem(ADJ_CLARITY, R.string.ve_clarity, R.drawable.ic_ve_adj_clarity),
            VeAdjustItem(ADJ_SHADOWS, R.string.ve_shadows, R.drawable.ic_ve_adj_shadows),
            VeAdjustItem(ADJ_HIGHLIGHTS, R.string.ve_highlights, R.drawable.ic_ve_adj_highlights),
            VeAdjustItem(ADJ_EXPOSURE, R.string.ve_exposure, R.drawable.ic_ve_adj_exposure),
            VeAdjustItem(ADJ_GAMMA, R.string.ve_gamma, R.drawable.ic_ve_adj_gamma),
            VeAdjustItem(ADJ_BLACKS, R.string.ve_blacks, R.drawable.ic_ve_adj_blacks),
            VeAdjustItem(ADJ_WHITES, R.string.ve_whites, R.drawable.ic_ve_adj_whites),
            VeAdjustItem(ADJ_TEMPERATURE, R.string.ve_temperature, R.drawable.ic_ve_adj_temperature),
            VeAdjustItem(ADJ_SHARPNESS, R.string.ve_sharpness, R.drawable.ic_ve_adj_sharpness),
        )

        private fun formatMs(ms: Long): String {
            if (ms < 0) return "0:00"
            return "${ms / 60000}:${"%02d".format((ms / 1000) % 60)}"
        }
    }

    private val binding by viewBinding(ActivityVideoEditBinding::inflate)

    private var uri: Uri? = null
    private var realPath: String? = null
    private lateinit var saveUri: Uri

    /** True when the incoming media is a still image (image mime); drives the
     *  Trim/transport/double-tap hiding and the still-image export path. */
    private var isImage = false

    /** True for the camera-action.CROP crop-contract launch (any caller asking
     *  us to crop an image and hand the result back). */
    private var isCropIntent = false

    /** What the pipeline actually reads: the original for videos; for images a
     *  pre-rotated, size-capped cache copy (see probeImageThenStart). */
    private var mediaUri: Uri? = null
    private var imageTempInput: File? = null

    private var player: CompositionPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    // live-preview composition push (throttled while sliders drag)
    private var lastCompositionPush = 0L
    private var pendingLiveEffects: List<Effect>? = null
    private var trailingPush: Runnable? = null
    /** Last state key that reached the player; swaps are skipped when unchanged. */
    private var lastPushedVisualKey: VideoEditState? = null

    private val history = EditHistory()
    /** Non-null while a slider/drag gesture is in flight; committed on release. */
    private var liveState: VideoEditState? = null
    private val state: VideoEditState get() = liveState ?: history.current

    private var durationMs = 0L
    private var videoWidth = 0
    private var videoHeight = 0
    private var isSeekTracking = false

    private var currentToolId: String? = null
    private var nextItemId = 1L

    /** State captured when a tool is opened; restored if the tool is
     *  cancelled via the ✕ button (discard-this-tool's-edits semantics). */
    private var toolEntrySnapshot: VideoEditState? = null

    private var retrieverBaseThumb: Bitmap? = null
    private var brushBitmapCache: Pair<Int, Bitmap>? = null

    private lateinit var toolAdapter: VeToolAdapter
    private lateinit var aspectAdapter: VeChipAdapter
    private lateinit var adjustAdapter: VeAdjustAdapter
    private var selectedAdjustKey: String? = null

    // Text tool panel state
    private var veTextSubPanel: String? = null
    private lateinit var veTextActionAdapter: VeToolAdapter
    private lateinit var veTextFontAdapter: VeChipAdapter

    /** Id of the text item currently edited inline (in its own box), -1 = none. */
    private var veInlineEditId = -1L
    private var aspectLabels = VideoEditState.ASPECT_OPTIONS.map { it.label }.toMutableList()

    private val exporter by lazy { VideoExporter(applicationContext) }
    private var tempOutput: File? = null
    private var exportDialog: AlertDialog? = null
    private var exportDialogProgress: android.widget.ProgressBar? = null
    private var exportDialogLabel: TextView? = null

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.veCoordinator.background = Color.BLACK.toDrawable()
        // gallery-image sticker decodes happen through this shared factory
        ImageStickerCache.resolver = applicationContext.contentResolver
        setupEdgeToEdge(padBottomSystem = listOf(binding.vePrimaryActions.root))

        // media kind + crop contract must be known BEFORE the tool row/menu are
        // built (Trim hidden for images; overwrite/edit/share hidden for crop)
        isCropIntent = intent.action == CROP_ACTION || (intent.extras?.get(CROP_EXTRA) as? String) == "true"
        isImage = resolveIsImage()

        binding.veOverlayStage.listener = this
        if (!isImage) {
            binding.veOverlayStage.onCanvasDoubleTap = { togglePlayPause() }
        }
        binding.vePanelTrim.veTrimSlider.listener = this

        setupOptionsMenu()
        setupBottomActions()
        setupPanels()

        if (isCropIntent) {
            binding.veToolbar.menu.apply {
                findItem(R.id.overwrite_original)?.isVisible = false
                findItem(R.id.edit)?.isVisible = false
                findItem(R.id.share)?.isVisible = false
            }
        }

        handlePermission(getPermissionToRequest()) {
            if (!it) {
                toast(com.goodwy.commons.R.string.no_storage_permissions)
                finish()
            } else {
                initEditor()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupToolbar(binding.veToolbar, NavigationIcon.Arrow, Color.BLACK)
        setupTopAppBar(binding.veAppbar, NavigationIcon.Arrow, topBarColor = Color.BLACK)
        if (baseConfig.topAppBarColorIcon) {
            val primary = getProperPrimaryColor()
            binding.vePrimaryActions.bottomPrimaryCancel.setTextColor(primary)
            binding.vePrimaryActions.bottomPrimarySave.setTextColor(primary)
            // M19r: tool strip ✕ / TOOL NAME / ✓ follow the device theme…
            binding.vePrimaryActions.bottomPrimaryToolCancel.setColorFilter(primary)
            binding.vePrimaryActions.bottomPrimaryToolTitle.setTextColor(primary)
            binding.vePrimaryActions.bottomPrimaryToolConfirm.setColorFilter(primary)
            // …and so does the loupe's Cancel / OK pair
            binding.veLoupeCancel.setTextColor(primary)
            binding.veLoupeOk.setTextColor(primary)
        }
        binding.vePanelTrim.veTrimSlider.setColors(getProperPrimaryColor())
        aspectAdapter.setColors(getProperPrimaryColor(), Color.WHITE)
        updateUndoRedoIcons()
        refreshStageContent()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        exporter.cancel()
        tempOutput?.delete()
        imageTempInput?.delete()
    }

    private fun setupOptionsMenu() {
        binding.veToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.undo -> performUndo()
                R.id.redo -> performRedo()
                R.id.overwrite_original -> startSaveFlow(overwrite = true)
                R.id.edit -> editWith()
                R.id.share -> shareMediumPath(realPath ?: return@setOnMenuItemClickListener false)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
    }

    private fun setupBottomActions() {
        // Trim is video-only (a still has no timeline)
        val tools = if (isImage) TOOLS.filterNot { it.id == TOOL_TRIM } else TOOLS
        toolAdapter = VeToolAdapter(
            tools = tools,
            activeColor = { getProperPrimaryColor() },
            idleColor = { Color.WHITE },
        ) { tool -> toolClicked(tool.id) }
        binding.veToolRow.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = toolAdapter
        }
        binding.vePrimaryActions.bottomPrimaryCancel.setOnClickListener { finish() }
        binding.vePrimaryActions.bottomPrimarySave.setOnClickListener { startSaveFlow(overwrite = false) }
        if (isCropIntent) {
            binding.vePrimaryActions.bottomPrimarySave.setText(R.string.ve_crop_confirm)
        }
        // tool-active strip (✕ · tool name · ✓): ✕ discards the tool's edits
        // (for a crop intent it cancels the contract outright); ✓ keeps them
        // and closes back to the tool list — and for crop intents ✓ IS the
        // final confirm, so it also fires the Crop save
        binding.vePrimaryActions.bottomPrimaryToolCancel.setOnClickListener {
            if (interceptBrushColorButtons(confirm = false)) return@setOnClickListener
            if (isCropIntent) finish() else closeActiveTool(keepChanges = false)
        }
        binding.vePrimaryActions.bottomPrimaryToolConfirm.setOnClickListener {
            if (interceptBrushColorButtons(confirm = true)) return@setOnClickListener
            val wasCropIntent = isCropIntent
            closeActiveTool(keepChanges = true)
            if (wasCropIntent) binding.vePrimaryActions.bottomPrimarySave.performClick()
        }
        // system Back walks one level up at a time: sticker feature -> its
        // chips row, active tool -> tool list, else exit
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    currentToolId == TOOL_STICKER && veStickerFeature != null -> showStickerFeature(null)
                    brushPickMode == BrushPickMode.HSV -> closeHsvPanel(revert = true)
                    brushPickMode == BrushPickMode.LOUPE -> {
                        disarmLoupe()
                        brushPickMode = BrushPickMode.IDLE
                        updateVePrimaryStrip()
                    }
                    currentToolId != null -> closeActiveTool(keepChanges = true)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    /** MIME of the SOURCE media (intent type → resolver → path extension). */
    private fun inputMime(): String? {
        intent.type?.let { return it }
        uri?.let { u -> runCatching { contentResolver.getType(u) }.getOrNull()?.let { return it } }
        val from = realPath ?: intent.data?.toString() ?: return null
        val ext = from.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    /** Cheap up-front media-kind check (no file IO on the UI thread beyond a
     *  resolver MIME lookup, which both legacy editors did inline as well). */
    private fun resolveIsImage(): Boolean {
        intent.type?.let { return it.startsWith("image/") }
        val data = intent.data ?: return false
        val mime = runCatching { contentResolver.getType(data) }.getOrNull()
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                MimeTypeMap.getFileExtensionFromUrl(data.toString()).lowercase()
            )
        return mime?.startsWith("image/") == true
    }

    // -------------------------------------------------------------- init path

    private fun initEditor() {
        val data = intent.data
        if (data == null) {
            toast(R.string.invalid_video_path)
            finish()
            return
        }

        var resolved = data
        if (resolved.scheme != "file" && resolved.scheme != "content") {
            toast(R.string.unknown_file_location)
            finish()
            return
        }

        val extras = intent.extras
        realPath = extras?.getString(REAL_FILE_PATH)
        if (realPath != null) {
            val p = realPath!!
            resolved = when {
                isPathOnOTG(p) -> resolved
                p.startsWith("file:/") -> Uri.parse(p)
                else -> Uri.fromFile(File(p))
            }
        } else {
            getRealPathFromURI(resolved)?.let {
                realPath = it
                resolved = Uri.fromFile(File(it))
            }
        }
        uri = resolved

        saveUri = when {
            extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true &&
                extras.get(MediaStore.EXTRA_OUTPUT) is Uri -> extras.get(MediaStore.EXTRA_OUTPUT) as Uri
            else -> resolved
        }

        history.reset(VideoEditState())
        val savedBrushColor = config.editorBrushColor
        if (savedBrushColor != 0) {
            history.mutateTop(state.copy(brushColor = savedBrushColor))
        }
        if (isImage) {
            probeImageThenStart(resolved)
        } else {
            probeDurationThenStart(resolved)
        }
    }

    /**
     * Image init: ONE Glide decode (background) of the still — EXIF-aware,
     * capped to [MAX_IMAGE_DIM], leveraging the app's bundled codec
     * integrations (AVIF/JXL/…). The decoded frame is written to a private
     * cache file which becomes the pipeline input: media3's image loader
     * ignores EXIF orientation (1.9.2 sources), so feeding the original URI
     * would render camera photos sideways; this also pins preview and export
     * to the identical input bitmap. The transport row (play/seek/mute/time)
     * is video-only and hidden here.
     */
    private fun probeImageThenStart(imageUri: Uri) {
        ensureBackgroundThread {
            val decoded = try {
                Glide.with(applicationContext)
                    .asBitmap()
                    .load(imageUri)
                    .apply(
                        RequestOptions()
                            .override(MAX_IMAGE_DIM, MAX_IMAGE_DIM)
                            .downsample(DownsampleStrategy.AT_MOST)
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .skipMemoryCache(true)
                    )
                    .submit()
                    .get()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (decoded == null || decoded.width <= 0 || decoded.height <= 0) {
                    toast(R.string.invalid_image_path)
                    finish()
                    return@runOnUiThread
                }
                val cached = writeInputCache(decoded)
                if (cached == null) {
                    toast(R.string.image_editing_failed)
                    finish()
                    return@runOnUiThread
                }
                videoWidth = decoded.width
                videoHeight = decoded.height
                durationMs = IMAGE_PSEUDO_DURATION_MS
                imageTempInput = cached
                mediaUri = Uri.fromFile(cached)
                binding.veTransportRow.beGone()
                // carousel base thumbs come from the same decode (no retriever)
                retrieverBaseThumb = centerCropScale(decoded, 112)
                setupPlayer(mediaUri!!)
                setupFilterCarousel()
                setupOverlayCarousel()
                if (isCropIntent) enterCropMode()
            }
        }
    }

    /** Writes the pre-decoded input frame to a private cache file; PNG keeps
     *  alpha, JPEG otherwise (an invisible q96 intermediate). */
    private fun writeInputCache(bmp: Bitmap): File? {
        val alphaMime = inputMime().let { it == "image/png" || it == "image/webp" || it == "image/gif" }
        return try {
            val f = File(cacheDir, "ve_input_${System.currentTimeMillis()}.${if (alphaMime) "png" else "jpg"}")
            FileOutputStream(f).use {
                bmp.compress(if (alphaMime) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 96, it)
            }
            f
        } catch (e: Exception) {
            null
        }
    }

    /** Crop-contract launch: straight into the interactive free Transform
     *  window, with the full frame pre-selected (confirm returns the crop). */
    private fun enterCropMode() {
        toolClicked(TOOL_TRANSFORM)
        val freeIndex = VideoEditState.ASPECT_OPTIONS.indexOfFirst { it.id == VideoEditState.ASPECT_FREE.id }
        if (freeIndex >= 0) aspectClicked(freeIndex)
    }

    // ----------------------------------------------------------------- player

    /**
     * CompositionPlayer requires every EditedMediaItem to carry an explicit
     * durationUs, and the effect assembler needs the DECODED input frame size —
     * so both are probed up-front with MediaMetadataRetriever before the player
     * (and its first composition) is created.
     */
    private fun probeDurationThenStart(videoUri: Uri) {
        ensureBackgroundThread {
            var durMs = 0L
            var width = 0
            var height = 0
            var rotation = 0
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, videoUri)
                durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                retriever.release()
            } catch (e: Exception) {
                // handled on the main thread below
            }
            val probedDuration = durMs
            val probedWidth = width
            val probedHeight = height
            val rotated = rotation == 90 || rotation == 270
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (probedDuration <= 0L) {
                    toast(R.string.invalid_video_path)
                    finish()
                    return@runOnUiThread
                }
                durationMs = probedDuration
                if (probedWidth > 0 && probedHeight > 0) {
                    videoWidth = if (rotated) probedHeight else probedWidth
                    videoHeight = if (rotated) probedWidth else probedHeight
                }
                mediaUri = videoUri
                binding.veSeek.max = durationMs.toInt()
                binding.vePanelTrim.veTrimSlider.setDuration(durationMs * 1000)
                updateTrimLabels(0L, durationMs * 1000)
                setupPlayer(videoUri)
                loadThumbnails(videoUri)
            }
        }
    }

    /**
     * The preview is a [CompositionPlayer] — media3-transformer's own player for
     * compositions, i.e. the exact pipeline Transformer exports with (which we
     * know renders correctly on this device). Every edit swaps in a fresh
     * Composition carrying the rebuilt effect chain; the player's documented
     * behavior then re-renders the held frame through the new chain even while
     * paused, and the requested start position preserves the playhead.
     */
    private fun setupPlayer(videoUri: Uri) {
        try {
            setupPlayerInternal(videoUri)
        } catch (e: Exception) {
            // never crash the editor on a device-specific graph failure — surface it instead
            showErrorToast("${getString(R.string.video_editing_failed)}: ${e.javaClass.simpleName}: ${e.message}")
            finish()
        }
    }

    private fun setupPlayerInternal(videoUri: Uri) {
        // NOTE: no replayable cache — it recycles recently-decoded textures
        // across composition swaps and showed up on-device as torn/sheared
        // frames (stale bands + smeared regions) right after an effect change.
        val p = CompositionPlayer.Builder(this).build()
        player = p
        binding.vePlayer.player = p

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updatePlayPauseIcon()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlayPauseIcon()
            }

            override fun onEvents(player: Player, events: Player.Events) {
                // CompositionPlayer only supports a subset of listener events
                // (no IS_PLAYING_CHANGED / VIDEO_SIZE_CHANGED); onEvents fires
                // for every state batch and is the reliable refresh hook.
                if (events.size() > 0) updatePlayPauseIcon()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val root = generateSequence<Throwable>(error) { it.cause }.last()
                android.util.Log.e(TAG, "player error: ${error.errorCodeName}", error)
                val detail = root.message?.takeIf { it.isNotBlank() }?.take(180) ?: root.javaClass.simpleName
                showErrorToast("${getString(R.string.video_editing_failed)}: ${error.errorCodeName}\n$detail")
            }
        })

        p.repeatMode = Player.REPEAT_MODE_ALL
        p.setComposition(buildComposition(emptyList()), /* startPositionMs = */ 0L)
        p.prepare()
        p.play()

        setupTransport()
        if (!isImage) startPolling()   // nothing to poll with a hidden transport
    }

    /** Shared play/pause toggle: transport button + double-tap on the stage. */
    private fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun setupTransport() {
        binding.veBtnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        binding.veBtnMute.setOnClickListener {
            commitState(state.copy(muted = !state.muted))
        }
        binding.veSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress.toLong())
                    binding.veTime.text = "${formatMs(progress.toLong())} / ${formatMs(durationMs)}"
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekTracking = true
                setScrubbingMode(true)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekTracking = false
                setScrubbingMode(false)
            }
        })
    }

    private fun startPolling() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (!isSeekTracking && durationMs > 0) {
                        val pos = p.currentPosition
                        binding.veSeek.progress = pos.toInt()
                        binding.veTime.text = "${formatMs(pos)} / ${formatMs(durationMs)}"
                        // loop within the trim selection
                        val endMs = state.trimEndUs.takeIf { it > 0 }?.div(1000) ?: durationMs
                        if (pos >= endMs - 60) {
                            p.seekTo(state.trimStartUs / 1000)
                        }
                    }
                }
                handler.postDelayed(this, POLL_MS)
            }
        }, POLL_MS)
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        binding.veBtnPlayPause.setImageResource(if (playing) R.drawable.ic_pause_vector else R.drawable.ic_play_vector)
    }

    /**
     * Preview composition for [CompositionPlayer]. Deliberately WITHOUT a
     * ClippingConfiguration: the preview timeline stays the full file so the
     * transport seek/labels keep absolute positions, while the poller enforces
     * the trim window; the clip is applied once, at export, by [buildMediaItem].
     */
    /**
     * Pipeline input for preview and export alike. Images MUST carry an
     * explicit imageDurationMs — media3's asset loader only routes a MediaItem
     * to the image path (CompositionPlayer and FrameExtractor both check it)
     * when it is set.
     */
    private fun contentMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(mediaUri!!)
        .apply { if (isImage) setImageDurationMs(durationMs) }
        .build()

    private fun buildComposition(videoEffects: List<Effect>): Composition {
        val edited = EditedMediaItem.Builder(contentMediaItem())
            .setDurationUs(durationMs * 1000) // mandatory for CompositionPlayer
            .setEffects(
                Effects(
                    /* audioProcessors = */ ImmutableList.of(),
                    /* videoEffects = */ ImmutableList.copyOf(videoEffects),
                )
            )
            .build()
        return Composition.Builder(EditedMediaItemSequence.Builder(edited).build()).build()
    }

    /** MediaItem with the trim clip — EXPORT ONLY (see [buildComposition]). */
    private fun buildMediaItem(s: VideoEditState): MediaItem {
        val builder = MediaItem.Builder().setUri(mediaUri)
            .apply { if (isImage) setImageDurationMs(durationMs) }
        val startMs = s.trimStartUs / 1000
        val endMs = if (s.trimEndUs > 0) s.trimEndUs / 1000 else durationMs
        if (startMs > 0 || (durationMs > 0 && endMs < durationMs)) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
            )
        }
        return builder.build()
    }

    // ------------------------------------------------------------------ state

    private fun commitState(newState: VideoEditState) {
        if (history.commit(newState)) {
            liveState = null
            applyState(newState)
        }
    }

    /** Applies a state without touching history (live slider/drag movement). */
    private fun applyStateLive(newState: VideoEditState) {
        setScrubbingMode(true)
        liveState = newState
        applyState(newState, isLiveDrag = true)
    }

    /**
     * Scrubbing mode optimizes the player for frequent seeks (audio playback is
     * suppressed while active) — enabled for the duration of any drag gesture.
     */
    private fun setScrubbingMode(enabled: Boolean) {
        try {
            player?.setScrubbingModeEnabled(enabled)
        } catch (e: Exception) {
            // best effort only
        }
    }

    /**
     * Fields that change the video effect chain. Audio (muted), the trim window
     * (not part of the preview chain), pending brush settings, and the overlay
     * items/strokes (drawn by OverlayStageView in the preview; baked in only at
     * export) do NOT, so a composition swap for those alone is pure churn —
     * skipped via this key.
     */
    private fun VideoEditState.visualKey(): VideoEditState =
        copy(
            muted = false,
            trimStartUs = 0L,
            trimEndUs = -1L,
            brushColor = 0,
            brushSizeFraction = 0f,
            items = emptyList(),
            strokes = emptyList(),
        )

    @Synchronized
    private fun brushLayer(): Bitmap {
        val (rotW, rotH) = VideoEffectsAssembler.rotatedSize(state, videoWidth, videoHeight)
        val w = rotW.coerceAtLeast(2)
        val h = rotH.coerceAtLeast(2)
        val key = state.strokes.size * 31 + w * 31_331 + h
        brushBitmapCache?.let { (k, bmp) -> if (k == key && !bmp.isRecycled && bmp.width == w && bmp.height == h) return bmp }
        val bmp = VideoEffectsAssembler.renderBrushLayer(state, w, h)
        brushBitmapCache = key to bmp
        return bmp
    }

    private fun applyState(s: VideoEditState, isLiveDrag: Boolean = false) {
        val p = player ?: return
        // While the interactive free-crop window is on screen, keep the preview
        // at the FULL frame: baking the dragged rect into the composition would
        // reshape the letterbox mid-gesture, and the frame (normalized to the
        // full frame) would chase itself across the video — the "weird crop" the
        // user saw. The crop enters the preview as soon as the Transform tool
        // closes (and is always baked at export).
        val editingFreeCrop = currentToolId == TOOL_TRANSFORM && s.aspectOption.id == VideoEditState.ASPECT_FREE.id
        val previewState = if (editingFreeCrop) s.copy(freeCrop = null) else s
        val visualKey = previewState.visualKey()
        if (videoWidth > 0 && videoHeight > 0 && durationMs > 0 && visualKey != lastPushedVisualKey) {
            lastPushedVisualKey = visualKey
            // preview chain excludes stickers/text/brush — OverlayStageView
            // renders those in the View layer in real time (export bakes them in)
            val effects = VideoEffectsAssembler.assemble(previewState, videoWidth, videoHeight, includeOverlays = false) { brushLayer() }
            // live slider drags are throttled; commits always apply immediately
            pushComposition(p, effects, throttle = isLiveDrag)
            val (outW, outH) = VideoEffectsAssembler.outputSize(previewState, videoWidth, videoHeight)
            if (outH > 0) binding.veOverlayStage.setContentAspect(outW.toFloat() / outH)
        } else if (!isLiveDrag) {
            setScrubbingMode(false)
        }

        // sound
        p.volume = if (s.muted) 0f else 1f
        binding.veBtnMute.setImageResource(if (s.muted) R.drawable.ic_ve_volume_off else R.drawable.ic_ve_volume_on)

        // stage + panels
        binding.veOverlayStage.brushColor = s.brushColor
        binding.veOverlayStage.brushSizeFraction = s.brushSizeFraction
        binding.veOverlayStage.focusX = s.focusCenterX
        binding.veOverlayStage.focusY = s.focusCenterY
        // display-only seed: with Custom (free) as the default aspect option
        // the crop window must already show (full frame) when Transform opens,
        // before anything is committed to the state (freeCrop == null keeps
        // the "not edited" flags false — nothing is seeded INTO the state)
        val cropSeed = s.freeCrop ?: VeCropRect()
        binding.veOverlayStage.setCropRect(cropSeed.left, cropSeed.top, cropSeed.right, cropSeed.bottom)
        refreshStageContent()
        syncPanels(s)
        updateStageMode()
        updateUndoRedoIcons()
    }

    private fun pushComposition(p: CompositionPlayer, effects: List<Effect>, throttle: Boolean) {
        if (throttle) {
            pendingLiveEffects = effects
            val now = SystemClock.uptimeMillis()
            if (now - lastCompositionPush >= LIVE_PUSH_MS) {
                applyCompositionNow(p)
            } else if (trailingPush == null) {
                val r = Runnable {
                    trailingPush = null
                    player?.let { applyCompositionNow(it) }
                }
                trailingPush = r
                handler.postDelayed(r, LIVE_PUSH_MS - (now - lastCompositionPush))
            }
        } else {
            trailingPush?.let { handler.removeCallbacks(it) }
            trailingPush = null
            pendingLiveEffects = effects
            applyCompositionNow(p)
        }
    }

    private fun applyCompositionNow(p: CompositionPlayer) {
        val effects = pendingLiveEffects ?: return
        pendingLiveEffects = null
        if (durationMs <= 0) return
        lastCompositionPush = SystemClock.uptimeMillis()
        try {
            // The documented preview mechanism: setComposition keeps the playhead
            // (startPositionMs) and re-renders the held frame through the NEW
            // effect chain even when paused — no renderer-message hacks needed.
            val position = p.currentPosition.coerceIn(0L, durationMs - 1)
            p.setComposition(buildComposition(effects), position)
        } catch (e: Exception) {
            // a device GL quirk must not kill the editor; let the next attempt retry
            android.util.Log.e(TAG, "composition push failed", e)
            lastPushedVisualKey = null
            showErrorToast(e.localizedMessage ?: getString(R.string.video_editing_failed))
        }
    }

    private fun refreshStageContent() {
        binding.veOverlayStage.setContent(state.items, state.strokes)
    }

    private fun performUndo() {
        if (history.canUndo) applyState(history.undo())
    }

    private fun performRedo() {
        if (history.canRedo) applyState(history.redo())
    }

    private fun updateUndoRedoIcons() {
        val iconColor = if (baseConfig.topAppBarColorIcon) getProperPrimaryColor() else Color.WHITE
        binding.veToolbar.menu.apply {
            findItem(R.id.undo)?.let {
                it.isEnabled = history.canUndo
                it.icon = resources.getColoredDrawableWithColor(
                    this@VideoEditActivity,
                    if (history.canUndo) R.drawable.ic_undo_vector else R.drawable.ic_undo_gray,
                    iconColor,
                )
            }
            findItem(R.id.redo)?.let {
                it.isEnabled = history.canRedo
                it.icon = resources.getColoredDrawableWithColor(
                    this@VideoEditActivity,
                    if (history.canRedo) R.drawable.ic_redo_vector else R.drawable.ic_redo_gray,
                    iconColor,
                )
            }
        }
    }

    // ------------------------------------------------------------------ tools

    private fun toolClicked(toolId: String) {
        if (currentToolId == null) toolEntrySnapshot = state
        currentToolId = if (currentToolId == toolId) null else toolId
        if (currentToolId == null) toolEntrySnapshot = null
        toolAdapter.activeToolId = currentToolId
        updatePanelsVisibility()
        updateStageMode()
        syncPanels(state)
        // Reapply on BOTH enter and exit: re-entering Transform+Free must swap
        // the preview back to the FULL frame (applyState holds freeCrop out of
        // the composition while the interactive window is up — see applyState),
        // otherwise the player keeps the cropped composition pushed when the
        // tool was last closed and the window floats over the cropped preview.
        // Leaving Transform pushes the crop into the preview; other switches
        // are no-ops via the visualKey check.
        applyState(state)
    }

    private fun updatePanelsVisibility() {
        fun visible(id: String) = currentToolId == id
        binding.vePanelTrim.root.beVisibleIf(visible(TOOL_TRIM))
        binding.vePanelTransform.root.beVisibleIf(visible(TOOL_TRANSFORM))
        binding.vePanelFilter.root.beVisibleIf(visible(TOOL_FILTER))
        binding.vePanelAdjust.root.beVisibleIf(visible(TOOL_ADJUST))
        binding.vePanelFocus.root.beVisibleIf(visible(TOOL_FOCUS))
        binding.vePanelSticker.root.beVisibleIf(visible(TOOL_STICKER))
        binding.vePanelBrush.root.beVisibleIf(visible(TOOL_BRUSH))
        binding.vePanelOverlay.root.beVisibleIf(visible(TOOL_OVERLAY))
        binding.vePanelText.root.beVisibleIf(visible(TOOL_TEXT))
        if (currentToolId != TOOL_TEXT) {
            veTextSubPanel = null
            endVeInlineEdit()
        }
        if (currentToolId != TOOL_STICKER) showStickerFeature(null)
        if (currentToolId != TOOL_BRUSH && brushPickMode != BrushPickMode.IDLE) {
            val revert = brushPickMode == BrushPickMode.HSV
            disarmLoupe()
            closeHsvPanel(revert = revert)
        }
        updateVeTextSubPanels()
        // selection REPLACES the tool row: the active tool's panel occupies
        // the row's band and the row itself hides. INVISIBLE (not GONE) keeps
        // each slot measured, so the preview's bottom anchor never moves and
        // the framing/crop geometry stays constant idle<->tool. The transport
        // row swaps out the same way on video (tall panels would overlap it);
        // on images it stays GONE as always.
        val toolOpen = currentToolId != null
        binding.veToolRow.visibility = if (toolOpen) View.INVISIBLE else View.VISIBLE
        binding.veTransportRow.visibility = when {
            isImage -> View.GONE
            toolOpen -> View.INVISIBLE
            else -> View.VISIBLE
        }
        updateVePrimaryStrip()
    }

    /** Swaps the bottom strip (reference: tool_bar.png / tool_selected.png):
     *  idle = Cancel / Save-as; tool active = ✕ · TOOL NAME · ✓. */
    private fun updateVePrimaryStrip() {
        val active = currentToolId
        val pa = binding.vePrimaryActions
        pa.bottomPrimaryCancel.beVisibleIf(active == null)
        pa.bottomPrimarySave.beVisibleIf(active == null)
        pa.bottomPrimaryToolCancel.beVisibleIf(active != null)
        pa.bottomPrimaryToolConfirm.beVisibleIf(active != null)
        pa.bottomPrimaryToolTitle.beVisibleIf(active != null)
        if (active != null) {
            // an open eyedropper/HSV session titles the strip "BRUSH COLOR"
            val titleRes = if (active == TOOL_BRUSH && brushPickMode != BrushPickMode.IDLE) {
                R.string.ve_brush_color
            } else {
                TOOLS.firstOrNull { it.id == active }?.labelRes
            }
            titleRes?.let { pa.bottomPrimaryToolTitle.setText(it) }
        }
    }

    /** Closes the active tool back to the tool list. keepChanges=false
     *  restores the state captured at tool entry (the ✕ discard path); the
     *  restore goes through commitState so undo/redo still covers it. */
    private fun closeActiveTool(keepChanges: Boolean) {
        val active = currentToolId ?: return
        val snapshot = toolEntrySnapshot
        toolEntrySnapshot = null
        if (!keepChanges && snapshot != null && snapshot != state) {
            commitState(snapshot)
        }
        toolClicked(active)  // toggles to null: panels close, stage mode + preview re-push
    }

    private fun updateStageMode() {
        val stage = binding.veOverlayStage
        stage.showFocusMarker = currentToolId == TOOL_FOCUS &&
            (state.focusMode == VeFocusMode.RADIAL || state.focusMode == VeFocusMode.LINEAR)
        stage.mode = when {
            currentToolId == TOOL_TRANSFORM && state.aspectOption.id == VideoEditState.ASPECT_FREE.id ->
                OverlayStageView.Mode.CROP
            currentToolId == TOOL_BRUSH -> OverlayStageView.Mode.BRUSH
            currentToolId == TOOL_STICKER || currentToolId == TOOL_TEXT -> OverlayStageView.Mode.MOVE_ITEMS
            stage.showFocusMarker -> OverlayStageView.Mode.FOCUS_CENTER
            else -> OverlayStageView.Mode.IDLE
        }
        stage.invalidate()
    }

    private fun syncPanels(s: VideoEditState) {
        // filter selection
        if (::filterAdapter.isInitialized) filterAdapter.selectedIndex = s.filterIndex
        // overlay selection
        if (::overlayAdapter.isInitialized) overlayAdapter.selectedIndex = s.overlayIndex
        // adjust chips + slider
        if (::adjustAdapter.isInitialized) {
            adjustAdapter.selectedKey = selectedAdjustKey
            adjustAdapter.modifiedKeys = modifiedAdjustKeys(s)
        }
        syncAdjustSlider(s)
        // transform (straighten slider follows undo/redo & tool re-entry)
        binding.vePanelTransform.veAngleSlider.setAngle(s.straightenAngle)
        // focus
        binding.vePanelFocus.veSeekFocus.progress = (s.focusStrength * 100).toInt()
        tintFocusChips(s.focusMode)
        // brush
        binding.vePanelBrush.veBrushSize.progress = (((s.brushSizeFraction - 0.004f) / 0.156f) * 100).toInt().coerceIn(0, 100)
        updateBrushColorPreview()
        updateStickerColorPreview()
        updateStageMode()
    }

    // --------------------------------------------------------------- trim UI

    override fun onTrimChanging(startUs: Long, endUs: Long) {
        setScrubbingMode(true) // optimize the stream of drags/seeks; audio suppressed
        updateTrimLabels(startUs, endUs)
        // live path: only move the loop window + playhead; the effect chain is
        // untouched by trim (visualKey covers this), the clip is export-only
        liveState = state.copy(trimStartUs = startUs, trimEndUs = endUs)
        player?.seekTo(startUs / 1000)
    }

    override fun onTrimChangeFinished(startUs: Long, endUs: Long) {
        setScrubbingMode(false)
        commitState(state.copy(trimStartUs = startUs, trimEndUs = endUs))
    }

    private fun updateTrimLabels(startUs: Long, endUs: Long) {
        binding.vePanelTrim.veTrimStart.text = getString(R.string.ve_trim_start, formatMs(startUs / 1000))
        binding.vePanelTrim.veTrimEnd.text = getString(R.string.ve_trim_end, formatMs(endUs / 1000))
        binding.vePanelTrim.veTrimDuration.text = getString(R.string.ve_trim_duration, formatMs((endUs - startUs) / 1000))
    }

    // ------------------------------------------------------------- panels I/O

    private fun setupPanels() {
        setupTransformPanel()
        setupAdjustPanel()
        setupFocusPanel()
        setupStickerPanel()
        setupBrushPanel()
        setupTextPanel()
    }

    /** Pulls every overlay item fully inside the frame implied by [s]:
     *  geometry commits (crop/aspect/rotate) reinterpret the normalized item
     *  coordinates against the new canvas, and items stranded outside used to
     *  fail the whole export (media3 anchor outside [-1, 1]). */
    private fun clampedGeometryState(s: VideoEditState): VideoEditState {
        if (s.items.isEmpty() || videoWidth <= 0 || videoHeight <= 0) return s
        val (outW, outH) = VideoEffectsAssembler.outputSize(s, videoWidth, videoHeight)
        if (outW <= 0 || outH <= 0) return s
        val rw = outW.toFloat()
        val rh = outH.toFloat()
        val clamped = s.items.map { item ->
            val isText = item.kind == VeOverlayItem.Kind.TEXT
            val w = rw * (if (isText) 0.55f else 0.24f) * item.scale * item.sizeX
            val h = rh * (if (isText) 0.20f else 0.24f) * item.scale * item.sizeY
            val rad = Math.toRadians(item.rotationDegrees.toDouble())
            val ca = abs(cos(rad)).toFloat()
            val sa = abs(sin(rad)).toFloat()
            val aabbHwN = (w / 2f * ca + h / 2f * sa) / rw
            val aabbHhN = (w / 2f * sa + h / 2f * ca) / rh
            val cx = if (aabbHwN >= 0.5f) 0.5f else item.centerX.coerceIn(aabbHwN, 1f - aabbHwN)
            val cy = if (aabbHhN >= 0.5f) 0.5f else item.centerY.coerceIn(aabbHhN, 1f - aabbHhN)
            if (cx != item.centerX || cy != item.centerY) item.copy(centerX = cx, centerY = cy) else item
        }
        return s.copy(items = clamped)
    }

    private fun setupTransformPanel() {
        // straighten at an arbitrary angle: live drags go through the
        // throttled composition path (same effect chain as export), the
        // gesture end is ONE undo step — no geometry clamp needed, the angle
        // never moves the frame-anchored items
        binding.vePanelTransform.veAngleSlider.onAngleChanged = { angle, finished ->
            if (finished) {
                commitState(state.copy(straightenAngle = angle))
            } else {
                applyStateLive(state.copy(straightenAngle = angle))
            }
        }
        // reset icon at the tape's left: back to 0° as ONE undo step
        binding.vePanelTransform.veAngleReset.setOnClickListener {
            if (state.straightenAngle != 0f) {
                commitState(state.copy(straightenAngle = 0f))
            }
        }
        binding.vePanelTransform.veBtnRotate.setOnClickListener {
            commitState(clampedGeometryState(state.copy(rotationDegrees = (state.rotationDegrees + 90f) % 360f)))
        }
        binding.vePanelTransform.veBtnFlipH.setOnClickListener {
            commitState(clampedGeometryState(state.copy(flipHorizontal = !state.flipHorizontal)))
        }
        binding.vePanelTransform.veBtnFlipV.setOnClickListener {
            commitState(clampedGeometryState(state.copy(flipVertical = !state.flipVertical)))
        }

        aspectAdapter = VeChipAdapter(aspectLabels) { index ->
            aspectClicked(index)
        }
        binding.vePanelTransform.veAspectRecycler.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = aspectAdapter
        }
        syncAspectSelection()
    }

    private fun aspectClicked(index: Int) {
        val option = VideoEditState.ASPECT_OPTIONS.getOrNull(index) ?: return
        if (option.id == VideoEditState.ASPECT_FREE.id) {
            // free transform: show the interactive crop window on the stage,
            // starting at the FULL frame (nothing pre-cropped)
            commitState(clampedGeometryState(state.copy(
                aspectOption = option,
                freeCrop = state.freeCrop ?: VeCropRect(),
            )))
        } else {
            val current = state.aspectOption
            val updated = if (option.id == current.id && option.ratio != null) {
                // tapping the same chip flips landscape <-> portrait (parity w/ the reference editor)
                val flippedRatio = 1f / option.ratio
                val (x, y) = flipLabel(option.label)
                VeAspectOption(option.id, "$y:$x", flippedRatio)
            } else {
                option
            }
            commitState(clampedGeometryState(state.copy(aspectOption = updated, freeCrop = null)))
        }
        syncAspectSelection()
        updateStageMode()
    }

    private fun flipLabel(label: String): Pair<String, String> {
        val parts = label.split(":")
        return if (parts.size == 2) parts[1] to parts[0] else label to label
    }

    private fun syncAspectSelection() {
        val idx = VideoEditState.ASPECT_OPTIONS.indexOfFirst { it.id == state.aspectOption.id }
        aspectAdapter.selectedIndex = idx
        aspectLabels = VideoEditState.ASPECT_OPTIONS.map {
            if (it.id == state.aspectOption.id) state.aspectOption.label else it.label
        }.toMutableList()
        aspectAdapter.updateLabels(aspectLabels)
    }

    // ---------------------------------------------------------------- adjust

    private fun setupAdjustPanel() {
        adjustAdapter = VeAdjustAdapter(
            items = ADJUST_ITEMS,
            activeColor = { getProperPrimaryColor() },
            idleColor = { Color.WHITE },
        ) { item -> adjustChipClicked(item.key) }
        binding.vePanelAdjust.veAdjustRow.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = adjustAdapter
        }
        binding.vePanelAdjust.veAdjustSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val key = selectedAdjustKey ?: return
                if (fromUser) {
                    applyStateLive(state.withAdjustment(key, progress - 100))
                    updateAdjustValueText(progress - 100)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                commitState(state) // snapshot the live-mutated state
            }
        })
    }

    private fun adjustChipClicked(key: String) {
        if (key == ADJ_RESET) {
            if (!state.adjustments.isIdentity()) {
                commitState(state.copy(adjustments = VeAdjustments()))
            }
            selectedAdjustKey = null
            adjustAdapter.selectedKey = null
            binding.vePanelAdjust.veAdjustSliderRow.beGone()
            return
        }
        selectedAdjustKey = key
        adjustAdapter.selectedKey = key
        syncAdjustSlider(state)
    }

    private fun syncAdjustSlider(s: VideoEditState) {
        val key = selectedAdjustKey
        if (key == null || currentToolId != TOOL_ADJUST) {
            binding.vePanelAdjust.veAdjustSliderRow.beGone()
            return
        }
        binding.vePanelAdjust.veAdjustSliderRow.beVisible()
        binding.vePanelAdjust.veAdjustName.setText(adjustmentLabelRes(key))
        val value = adjustmentValue(s, key)
        binding.vePanelAdjust.veAdjustSeek.progress = value + 100
        updateAdjustValueText(value)
    }

    private fun updateAdjustValueText(value: Int) {
        binding.vePanelAdjust.veAdjustValue.text = "%+d".format(value)
    }

    private fun VideoEditState.withAdjustment(key: String, value: Int): VideoEditState {
        val v = value.coerceIn(-100, 100)
        val a = adjustments
        return copy(
            adjustments = when (key) {
                ADJ_BRIGHTNESS -> a.copy(brightness = v)
                ADJ_CONTRAST -> a.copy(contrast = v)
                ADJ_SATURATION -> a.copy(saturation = v)
                ADJ_CLARITY -> a.copy(clarity = v)
                ADJ_SHADOWS -> a.copy(shadows = v)
                ADJ_HIGHLIGHTS -> a.copy(highlights = v)
                ADJ_EXPOSURE -> a.copy(exposure = v)
                ADJ_GAMMA -> a.copy(gamma = v)
                ADJ_BLACKS -> a.copy(blacks = v)
                ADJ_WHITES -> a.copy(whites = v)
                ADJ_TEMPERATURE -> a.copy(temperature = v)
                ADJ_SHARPNESS -> a.copy(sharpness = v)
                else -> a
            }
        )
    }

    private fun adjustmentValue(s: VideoEditState, key: String): Int = with(s.adjustments) {
        when (key) {
            ADJ_BRIGHTNESS -> brightness
            ADJ_CONTRAST -> contrast
            ADJ_SATURATION -> saturation
            ADJ_CLARITY -> clarity
            ADJ_SHADOWS -> shadows
            ADJ_HIGHLIGHTS -> highlights
            ADJ_EXPOSURE -> exposure
            ADJ_GAMMA -> gamma
            ADJ_BLACKS -> blacks
            ADJ_WHITES -> whites
            ADJ_TEMPERATURE -> temperature
            ADJ_SHARPNESS -> sharpness
            else -> 0
        }
    }

    private fun adjustmentLabelRes(key: String): Int = when (key) {
        ADJ_BRIGHTNESS -> R.string.ve_brightness
        ADJ_CONTRAST -> R.string.ve_contrast
        ADJ_SATURATION -> R.string.ve_saturation
        ADJ_CLARITY -> R.string.ve_clarity
        ADJ_SHADOWS -> R.string.ve_shadows
        ADJ_HIGHLIGHTS -> R.string.ve_highlights
        ADJ_EXPOSURE -> R.string.ve_exposure
        ADJ_GAMMA -> R.string.ve_gamma
        ADJ_BLACKS -> R.string.ve_blacks
        ADJ_WHITES -> R.string.ve_whites
        ADJ_TEMPERATURE -> R.string.ve_temperature
        ADJ_SHARPNESS -> R.string.ve_sharpness
        else -> R.string.ve_reset
    }

    private fun modifiedAdjustKeys(s: VideoEditState): Set<String> = with(s.adjustments) {
        buildSet {
            if (brightness != 0) add(ADJ_BRIGHTNESS)
            if (contrast != 0) add(ADJ_CONTRAST)
            if (saturation != 0) add(ADJ_SATURATION)
            if (clarity != 0) add(ADJ_CLARITY)
            if (shadows != 0) add(ADJ_SHADOWS)
            if (highlights != 0) add(ADJ_HIGHLIGHTS)
            if (exposure != 0) add(ADJ_EXPOSURE)
            if (gamma != 0) add(ADJ_GAMMA)
            if (blacks != 0) add(ADJ_BLACKS)
            if (whites != 0) add(ADJ_WHITES)
            if (temperature != 0) add(ADJ_TEMPERATURE)
            if (sharpness != 0) add(ADJ_SHARPNESS)
        }
    }

    // -----------------------------------------------------------------

    private fun setupFocusPanel() {
        fun setMode(mode: VeFocusMode) {
            commitState(state.copy(focusMode = mode))
            updateStageMode()
        }
        binding.vePanelFocus.veFocusNone.setOnClickListener { setMode(VeFocusMode.NONE) }
        binding.vePanelFocus.veFocusGaussian.setOnClickListener { setMode(VeFocusMode.GAUSSIAN) }
        binding.vePanelFocus.veFocusRadial.setOnClickListener { setMode(VeFocusMode.RADIAL) }
        binding.vePanelFocus.veFocusLinear.setOnClickListener { setMode(VeFocusMode.LINEAR) }
        binding.vePanelFocus.veSeekFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyStateLive(state.copy(focusStrength = progress / 100f))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                commitState(state)
            }
        })
    }

    private fun tintFocusChips(mode: VeFocusMode) {
        val accent = getProperPrimaryColor()
        fun tint(tv: TextView, active: Boolean) = tv.setTextColor(if (active) accent else Color.WHITE)
        tint(binding.vePanelFocus.veFocusNone, mode == VeFocusMode.NONE)
        tint(binding.vePanelFocus.veFocusGaussian, mode == VeFocusMode.GAUSSIAN)
        tint(binding.vePanelFocus.veFocusRadial, mode == VeFocusMode.RADIAL)
        tint(binding.vePanelFocus.veFocusLinear, mode == VeFocusMode.LINEAR)
    }

    // -------------------------------------------------------------- stickers

    private var stickerColor = Color.WHITE
    private var veStickerFeature: String? = null

    private fun setupStickerPanel() {
        // shapes must stay visible on the dark panel: default fill is white
        stickerColor = Color.WHITE
        // 3 primary features as icon+label chips (same look as the main tool row)
        binding.vePanelSticker.veStickerFeatures.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = VeToolAdapter(
                listOf(
                    VeTool(VE_STICKER_GALLERY, R.string.ve_sticker_gallery, R.drawable.ic_ve_gallery),
                    VeTool(VE_STICKER_EMOJI, R.string.ve_sticker_emoticons, R.drawable.ic_ve_emoticons),
                    VeTool(VE_STICKER_SHAPES, R.string.ve_sticker_shapes, R.drawable.ic_ve_shapes),
                ),
                activeColor = { getProperPrimaryColor() },
                idleColor = { Color.WHITE },
            ) { feature -> stickerFeatureClicked(feature.id) }
        }
        binding.vePanelSticker.veStickerColor.setOnClickListener { pickStickerColor() }
        showStickerFeature(null)
        updateStickerColorPreview()
    }

    /** Gallery opens the image picker instantly; Emoticons/Shapes swap the
     *  chip row for their grid (the same replace-pattern as the tool row). */
    private fun stickerFeatureClicked(id: String) {
        when (id) {
            VE_STICKER_GALLERY -> VeGalleryPickerDialog(this, getProperPrimaryColor()) { uri -> addImageSticker(uri) }.show()
            else -> showStickerFeature(id)
        }
    }

    private fun showStickerFeature(feature: String?) {
        veStickerFeature = feature
        binding.vePanelSticker.veStickerFeatures.beVisibleIf(feature == null)
        binding.vePanelSticker.veStickerColor.beVisibleIf(feature == VE_STICKER_SHAPES)
        binding.vePanelSticker.veStickerRecycler.beVisibleIf(feature == VE_STICKER_EMOJI || feature == VE_STICKER_SHAPES)
        if (feature != VE_STICKER_EMOJI && feature != VE_STICKER_SHAPES) return
        binding.vePanelSticker.veStickerRecycler.apply {
            layoutManager = GridLayoutManager(this@VideoEditActivity, 2, GridLayoutManager.HORIZONTAL, false)
            adapter = if (feature == VE_STICKER_SHAPES) {
                VeShapeAdapter(OverlayBitmapFactory.SHAPES, { OverlayBitmapFactory.shapeBitmap(it, stickerColor, 96) }) { shapeId ->
                    addItem(VeOverlayItem.Kind.SHAPE, shapeId, stickerColor)
                }
            } else {
                VeEmojiAdapter(EMOJIS) { emoji -> addItem(VeOverlayItem.Kind.EMOJI, emoji, Color.WHITE) }
            }
        }
        centerStickerContent(binding.vePanelSticker.veStickerRecycler)
    }

    /** Gallery feature result: decode (EXIF-corrected, cached) off the UI
     *  thread, then add the image as an IMAGE overlay item. */
    private fun addImageSticker(uri: String) {
        ensureBackgroundThread {
            val bmp = ImageStickerCache.get(uri)
            if (bmp == null) {
                runOnUiThread { toast(R.string.ve_sticker_image_failed) }
                return@ensureBackgroundThread
            }
            val aspect = bmp.width.toFloat() / bmp.height.toFloat()
            runOnUiThread {
                val item = addItem(VeOverlayItem.Kind.IMAGE, uri, Color.WHITE, itemAspect = aspect)
                binding.veOverlayStage.selectItem(item.id)
            }
        }
    }

    /** Centers the sticker/shape grid horizontally when it is narrower than the panel. */
    private fun centerStickerContent(rv: androidx.recyclerview.widget.RecyclerView) {
        rv.post {
            val count = rv.adapter?.itemCount ?: return@post
            val cols = kotlin.math.ceil(count / 2.0).toInt()
            val itemW = (44 * resources.displayMetrics.density + 0.5f).toInt()
            val pad = ((rv.width - cols * itemW) / 2).coerceAtLeast(0)
            rv.setPadding(pad, 0, pad, 0)
            rv.clipToPadding = false
        }
    }

    private fun colorCircle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(2, Color.WHITE)
    }

    private fun updateStickerColorPreview() {
        binding.vePanelSticker.veStickerColor.setImageDrawable(colorCircle(stickerColor))
    }

    private fun pickStickerColor() {
        ColorPickerDialog(this, stickerColor) { wasPositivePressed, color, _ ->
            if (wasPositivePressed) {
                stickerColor = color
                updateStickerColorPreview()
                if (veStickerFeature == VE_STICKER_SHAPES) showStickerFeature(VE_STICKER_SHAPES)
            }
        }
    }

    private fun updateBrushColorPreview() {
        binding.vePanelBrush.veBrushColor.setImageDrawable(colorCircle(state.brushColor))
    }

    // ----------------------------------------------------------------- brush

    private fun setupBrushPanel() {
        binding.vePanelBrush.veBrushColor.setOnClickListener { pickBrushColor() }
        binding.vePanelBrush.veBrushPick.setOnClickListener { armBrushColorPick() }
        binding.veLoupeCancel.setOnClickListener {
            if (brushPickMode == BrushPickMode.LOUPE) {
                disarmLoupe()
                brushPickMode = BrushPickMode.IDLE
                updateVePrimaryStrip()
            }
        }
        binding.veLoupeOk.setOnClickListener { applyLoupePick() }
        // HDR panel: every picker change goes LIVE as the brush color
        binding.vePanelBrushColor.veHsvPicker.onColorChanged = { color ->
            applyLiveBrushColor(color)
        }
        // HSV panel swatch strip: pipette first, then the fixed presets
        hsvSwatchAdapter = VeHsvSwatchAdapter(
            colors = HSV_SWATCHES,
            accent = { getProperPrimaryColor() },
            current = { state.brushColor },
            onColor = { color ->
                binding.vePanelBrushColor.veHsvPicker.setColor(color)
                applyLiveBrushColor(color)
            },
            onPipette = { armBrushColorPick() },
        )
        binding.vePanelBrushColor.veColorSwatches.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = hsvSwatchAdapter
        }
        binding.vePanelBrush.veBrushSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // 0.004 .. 0.16 of the frame width (M19r: max 2x, min unchanged)
                    val fraction = 0.004f + (progress / 100f) * 0.156f
                    applyStateLive(state.copy(brushSizeFraction = fraction))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // center true-size preview circle while the size is adjusted
                binding.veOverlayStage.showBrushSizeIndicator = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                binding.veOverlayStage.showBrushSizeIndicator = false
                commitState(state)
            }
        })
        binding.vePanelBrush.veBrushUndo.setOnClickListener {
            if (state.strokes.isNotEmpty()) {
                commitState(state.copy(strokes = state.strokes.dropLast(1)))
            }
        }
        updateBrushColorPreview()
    }

    private fun pickBrushColor() {
        ColorPickerDialog(this, state.brushColor) { wasPositivePressed, color, _ ->
            if (wasPositivePressed) {
                config.editorBrushColor = color
                commitState(state.copy(brushColor = color))
            }
        }
    }

    /** Eyedropper mode of the Brush tool (color_picker_target.png &
     *  color_panel.png). IDLE = plain panel. */
    private enum class BrushPickMode { IDLE, LOUPE, HSV }
    private var brushPickMode = BrushPickMode.IDLE
    private var loupeSnapshot: Bitmap? = null
    private lateinit var hsvSwatchAdapter: VeHsvSwatchAdapter

    /** Brush color the current pipette session was opened with — the revert
     *  target when the session is cancelled (✕ / Back / tool switch). */
    private var brushColorSessionStart = 0

    /** "HDR" panel live feedback: EVERY hue/SV/opacity/swatch change inside the
     *  panel becomes the brush color immediately (applyStateLive = no undo
     *  spam). ✓ commits ONE undo step (applyHsvColor), cancel reverts to
     *  [brushColorSessionStart]. */
    private fun applyLiveBrushColor(color: Int) {
        applyStateLive(state.copy(brushColor = color))
        updateBrushColorPreview()
        hsvSwatchAdapter.refresh()
    }

    /** Pipette tap cycle: 1st tap = magnifying target over a frozen frame,
     *  2nd tap = "HDR" HSV color panel, further taps toggle between them. */
    private fun armBrushColorPick() {
        when (brushPickMode) {
            BrushPickMode.IDLE -> {
                brushColorSessionStart = state.brushColor
                brushPickMode = BrushPickMode.LOUPE
                armBrushLoupe()
            }
            BrushPickMode.LOUPE -> {
                disarmLoupe()
                brushPickMode = BrushPickMode.HSV
                openHsvPanel()
            }
            BrushPickMode.HSV -> {
                binding.vePanelBrushColor.root.beGone()
                brushPickMode = BrushPickMode.LOUPE
                armBrushLoupe()
            }
        }
        updateVePrimaryStrip()
    }

    private fun armBrushLoupe() {
        val stage = binding.veOverlayStage
        val rect = stage.displayedRect()
        val surfaceView = binding.vePlayer.videoSurfaceView as? SurfaceView
        if (surfaceView == null || rect.width() <= 0f) {
            toast(R.string.ve_sticker_image_failed)
            brushPickMode = BrushPickMode.IDLE
            return
        }
        val bmp = Bitmap.createBitmap(surfaceView.width.coerceAtLeast(1), surfaceView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            // one frozen frame: dragging the loupe samples exactly THIS image
            PixelCopy.request(surfaceView, bmp, { result ->
                if (result == PixelCopy.SUCCESS && brushPickMode == BrushPickMode.LOUPE) {
                    loupeSnapshot = bmp
                    binding.veLoupe.setSnapshot(bmp)
                    binding.veLoupe.setFrameRect(rect)
                    binding.veLoupe.beVisible()
                    binding.veLoupeActions.beVisible()
                    stage.touchInterceptor = { ev ->
                        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) {
                            binding.veLoupe.setTouch(ev.x, ev.y)
                        }
                        Unit
                    }
                    toast(R.string.ve_brush_pick_hint)
                } else {
                    bmp.recycle()
                    if (brushPickMode == BrushPickMode.LOUPE) {
                        toast(R.string.ve_sticker_image_failed)
                        brushPickMode = BrushPickMode.IDLE
                        updateVePrimaryStrip()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            bmp.recycle()
            toast(R.string.ve_sticker_image_failed)
            brushPickMode = BrushPickMode.IDLE
        }
        updateVePrimaryStrip()
    }

    private fun disarmLoupe() {
        binding.veOverlayStage.touchInterceptor = null
        binding.veLoupe.clear()
        binding.veLoupe.beGone()
        binding.veLoupeActions.beGone()
        loupeSnapshot?.recycle()
        loupeSnapshot = null
    }

    private fun applyLoupePick() {
        val bmp = loupeSnapshot
        val px = binding.veLoupe.centerPixel()
        if (bmp == null || px == null) {
            disarmLoupe()
            brushPickMode = BrushPickMode.IDLE
            updateVePrimaryStrip()
            return
        }
        val color = bmp.getPixel(px.first, px.second) or 0xFF000000.toInt()
        config.editorBrushColor = color
        commitState(state.copy(brushColor = color))
        updateBrushColorPreview()
        disarmLoupe()
        brushPickMode = BrushPickMode.IDLE
        updateVePrimaryStrip()
    }

    private fun openHsvPanel() {
        binding.vePanelBrushColor.root.beVisible()
        binding.vePanelBrushColor.veHsvPicker.setColor(state.brushColor)
        hsvSwatchAdapter.refresh()
    }

    private fun applyHsvColor() {
        val color = binding.vePanelBrushColor.veHsvPicker.getColor()
        config.editorBrushColor = color
        commitState(state.copy(brushColor = color))
        updateBrushColorPreview()
        closeHsvPanel()
    }

    private fun closeHsvPanel(revert: Boolean = false) {
        if (revert) {
            // cancelled session: restore the color it was opened with — as a
            // live (uncommitted) change, so no ghost undo step is left behind
            applyStateLive(state.copy(brushColor = brushColorSessionStart))
            updateBrushColorPreview()
            hsvSwatchAdapter.refresh()
        }
        binding.vePanelBrushColor.root.beGone()
        brushPickMode = BrushPickMode.IDLE
        updateVePrimaryStrip()
    }

    /** While a brush color mode is open, the strip's ✕/✓ act on IT (apply or
     *  discard the color session) instead of closing the whole Brush tool. */
    private fun interceptBrushColorButtons(confirm: Boolean): Boolean {
        if (currentToolId != TOOL_BRUSH || brushPickMode == BrushPickMode.IDLE) return false
        if (confirm) {
            when (brushPickMode) {
                BrushPickMode.LOUPE -> applyLoupePick()
                BrushPickMode.HSV -> applyHsvColor()
                BrushPickMode.IDLE -> Unit
            }
        } else {
            val revert = brushPickMode == BrushPickMode.HSV
            disarmLoupe()
            closeHsvPanel(revert = revert)
        }
        return true
    }

    // ----------------------------------------------------------- stage events

    private fun addItem(kind: VeOverlayItem.Kind, content: String, color: Int, fontFamily: String? = null, itemAspect: Float = 1f): VeOverlayItem {
        val item = VeOverlayItem(
            id = nextItemId++,
            kind = kind,
            content = content,
            color = color,
            fontFamily = fontFamily,
            itemAspect = itemAspect,
            centerX = 0.5f,
            centerY = 0.45f,
        )
        commitState(state.copy(items = state.items + item))
        updateStageMode()
        return item
    }

    // ------------------------------------------------------------------ Text

    private fun setupTextPanel() {
        // keep the open inline editor glued to its text box across ANY stage
        // relayout (keyboard show/hide resizes the whole viewer backbone)
        binding.veOverlayStage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val id = veInlineEditId
            if (id != -1L) {
                state.items.firstOrNull { it.id == id }?.let { positionVeInlineEditor(it) }
            }
        }
        val actions = listOf(
            VeTool(TEXT_ACTION_ADD, R.string.ve_text_action_add, R.drawable.ic_ve_text_add),
            VeTool(TEXT_ACTION_DELETE, R.string.ve_text_action_delete, R.drawable.ic_ve_text_delete),
            VeTool(TEXT_ACTION_FONT, R.string.ve_text_action_font, R.drawable.ic_ve_text_font),
            VeTool(TEXT_ACTION_COLOR, R.string.ve_text_action_color, R.drawable.ic_ve_text_color),
            VeTool(TEXT_ACTION_BACKGROUND, R.string.ve_text_action_background, R.drawable.ic_ve_text_background),
            VeTool(TEXT_ACTION_ALIGN, R.string.ve_text_action_align, R.drawable.ic_ve_text_align),
        )
        veTextActionAdapter = VeToolAdapter(
            tools = actions,
            activeColor = { getProperPrimaryColor() },
            idleColor = { Color.WHITE },
        ) { action -> textActionClicked(action.id) }
        binding.vePanelText.veTextActionsRow.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = veTextActionAdapter
        }

        // keep the typed text inside the box: shrink the font as it grows
        binding.veInlineEditor.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.veInlineEditor.post { refitVeInlineEditor() }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // font sub-panel
        var fontRef: VeChipAdapter? = null
        veTextFontAdapter = VeChipAdapter(
            FONTS.map { it.first },
            typefaceFor = { idx -> FONTS[idx].second?.let { Typeface.create(it, Typeface.NORMAL) } },
        ) { idx ->
            fontRef?.selectedIndex = idx
            updateSelectedVeText { it.copy(fontFamily = FONTS[idx].second) }
        }
        fontRef = veTextFontAdapter
        veTextFontAdapter.setColors(getProperPrimaryColor(), getProperTextColor())
        binding.vePanelTextFont.veTextFontRow.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = veTextFontAdapter
        }

        // text color sub-panel (swatches are plain views so they can center)
        binding.vePanelTextColor.veTextColorCustom.setOnClickListener {
            withSelectedVeText { item ->
                ColorPickerDialog(this, item.color) { ok, color, _ ->
                    if (ok) {
                        updateSelectedVeText { it.copy(color = color) }
                        fillVeTextColorRow()
                    }
                }
            }
        }
        fillVeTextColorRow()

        // background sub-panel
        binding.vePanelTextBg.veTextBgCustom.setOnClickListener {
            withSelectedVeText { item ->
                val initial = if (item.backgroundColor != Color.TRANSPARENT) item.backgroundColor else Color.WHITE
                ColorPickerDialog(this, initial) { ok, color, _ ->
                    if (ok) updateSelectedVeText { it.copy(backgroundColor = color) }
                }
            }
        }
        binding.vePanelTextBg.veTextBgNone.setOnClickListener {
            updateSelectedVeText { it.copy(backgroundColor = Color.TRANSPARENT) }
        }
        binding.vePanelTextBg.veTextBgSemi.setOnClickListener {
            updateSelectedVeText { it.copy(backgroundColor = 0x80000000.toInt()) }
        }
        binding.vePanelTextBg.veTextBgWhite.setOnClickListener {
            updateSelectedVeText { it.copy(backgroundColor = Color.WHITE) }
        }
        binding.vePanelTextBg.veTextBgBlack.setOnClickListener {
            updateSelectedVeText { it.copy(backgroundColor = Color.BLACK) }
        }

        // alignment sub-panel
        binding.vePanelTextAlign.veTextAlignLeft.setOnClickListener {
            updateSelectedVeText { it.copy(textAlign = 0) }
        }
        binding.vePanelTextAlign.veTextAlignCenter.setOnClickListener {
            updateSelectedVeText { it.copy(textAlign = 1) }
        }
        binding.vePanelTextAlign.veTextAlignRight.setOnClickListener {
            updateSelectedVeText { it.copy(textAlign = 2) }
        }
    }

    private fun textActionClicked(id: String) {
        when (id) {
            TEXT_ACTION_ADD -> {
                // requested: NO preset "Text" word. The new box is EMPTY and
                // its inline editor opens immediately — a blinking cursor at
                // the box's center with the keyboard up (requested default:
                // new text boxes start WHITE, M19p). Aborting without typing
                // drops the box again (see endVeInlineEdit).
                val item = addItem(VeOverlayItem.Kind.TEXT, "", Color.WHITE)
                binding.veOverlayStage.selectItem(item.id)
                beginVeInlineEdit(item)
            }
            TEXT_ACTION_DELETE -> withSelectedVeText { item ->
                commitState(state.copy(items = state.items.filterNot { it.id == item.id }))
            }
            TEXT_ACTION_FONT -> toggleVeTextSubPanel(TEXT_SUB_FONT)
            TEXT_ACTION_COLOR -> toggleVeTextSubPanel(TEXT_SUB_COLOR)
            TEXT_ACTION_BACKGROUND -> toggleVeTextSubPanel(TEXT_SUB_BG)
            TEXT_ACTION_ALIGN -> toggleVeTextSubPanel(TEXT_SUB_ALIGN)
        }
    }

    private fun toggleVeTextSubPanel(sub: String) {
        veTextSubPanel = if (veTextSubPanel == sub) null else sub
        updateVeTextSubPanels()
    }

    private fun updateVeTextSubPanels() {
        val sub = if (currentToolId == TOOL_TEXT) veTextSubPanel else null
        binding.vePanelTextFont.root.beVisibleIf(sub == TEXT_SUB_FONT)
        binding.vePanelTextColor.root.beVisibleIf(sub == TEXT_SUB_COLOR)
        binding.vePanelTextBg.root.beVisibleIf(sub == TEXT_SUB_BG)
        binding.vePanelTextAlign.root.beVisibleIf(sub == TEXT_SUB_ALIGN)
        if (::veTextActionAdapter.isInitialized) {
            veTextActionAdapter.activeToolId = when (sub) {
                TEXT_SUB_FONT -> TEXT_ACTION_FONT
                TEXT_SUB_COLOR -> TEXT_ACTION_COLOR
                TEXT_SUB_BG -> TEXT_ACTION_BACKGROUND
                TEXT_SUB_ALIGN -> TEXT_ACTION_ALIGN
                else -> null
            }
        }
        if (sub != null) syncVeTextSubPanelState()
    }

    /** Reflects the selected text box's current style into the open sub-panel. */
    private fun syncVeTextSubPanelState() {
        val item = selectedVeTextItem() ?: return
        if (::veTextFontAdapter.isInitialized) {
            veTextFontAdapter.selectedIndex = FONTS.indexOfFirst { it.second == item.fontFamily }.coerceAtLeast(0)
        }
        fillVeTextColorRow()
    }

    /** Basic-color swatch circles; plain views inside a HorizontalScrollView so
     * the row centers when it fits and scrolls when it doesn't. */
    private fun fillVeTextColorRow() {
        val row = binding.vePanelTextColor.veTextColorSwatches
        row.removeAllViews()
        val dm = resources.displayMetrics.density
        val size = (34 * dm).toInt()
        val pad = (4 * dm).toInt()
        val margin = (2 * dm).toInt()
        val current = selectedVeTextItem()?.color
        BASIC_COLORS.forEach { color ->
            val selected = current == color
            val swatch = ImageView(this).apply {
                setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        ((if (selected) 3f else 1f) * dm).toInt().coerceAtLeast(1),
                        if (selected) Color.WHITE else 0x55FFFFFF,
                    )
                })
                setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    updateSelectedVeText { it.copy(color = color) }
                    fillVeTextColorRow()
                }
            }
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = margin
            row.addView(swatch, lp)
        }
    }

    private fun selectedVeTextItem(): VeOverlayItem? {
        val id = binding.veOverlayStage.selectedItemId()
        return state.items.firstOrNull { it.id == id && it.kind == VeOverlayItem.Kind.TEXT }
    }

    /** Runs [block] with the selected TEXT item, or shows a hint when none is selected. */
    private fun withSelectedVeText(block: (VeOverlayItem) -> Unit) {
        val item = selectedVeTextItem()
        if (item == null) {
            toast(R.string.ve_text_select_first)
        } else {
            block(item)
        }
    }

    /** Applies [transform] to the selected TEXT item (one undo step) and redraws the stage. */
    private fun updateSelectedVeText(transform: (VeOverlayItem) -> VeOverlayItem) {
        withSelectedVeText { item ->
            val updated = transform(item)
            commitState(state.copy(items = state.items.map { if (it.id == item.id) updated else it }))
            binding.veOverlayStage.selectItem(updated.id)
            // restyle the open inline editor too (align/color/font/background
            // changed while the keyboard is up and the stage copy is hidden)
            if (veInlineEditId == updated.id) {
                val r = binding.veOverlayStage.itemRect(updated)
                applyVeInlineEditorProps(updated, r.width().toInt().coerceAtLeast(24), r.height().toInt().coerceAtLeast(16))
            }
        }
    }

    override fun onItemMoved(item: VeOverlayItem, finished: Boolean) {
        val updated = state.items.map { if (it.id == item.id) item else it }
        if (finished) {
            commitState(state.copy(items = updated))
        } else {
            applyStateLive(state.copy(items = updated))
        }
    }

    override fun onItemDeleted(item: VeOverlayItem) {
        commitState(state.copy(items = state.items.filterNot { it.id == item.id }))
    }

    override fun onItemTapped(item: VeOverlayItem) {
        if (item.kind == VeOverlayItem.Kind.TEXT && currentToolId == TOOL_TEXT) {
            beginVeInlineEdit(item)
        }
    }

    override fun onStrokeFinished(stroke: VeBrushStroke) {
        commitState(state.copy(strokes = state.strokes + stroke))
    }

    override fun onFocusCenterChanged(x: Float, y: Float) {
        applyStateLive(state.copy(focusCenterX = x, focusCenterY = y))
        focusCommitRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { commitState(state) }
        focusCommitRunnable = r
        handler.postDelayed(r, 600)
    }

    override fun onCropChanged(left: Float, top: Float, right: Float, bottom: Float, finished: Boolean) {
        val rect = VeCropRect(left, top, right, bottom)
        if (finished) {
            // one undo step per gesture; items outside the shrunk window are
            // pulled inside (stranded items anchored out-of-range fail exports)
            commitState(clampedGeometryState(state.copy(freeCrop = rect)))
        } else {
            applyStateLive(state.copy(freeCrop = rect))
        }
    }

    private var focusCommitRunnable: Runnable? = null

    override fun onStageTappedEmpty() {
        endVeInlineEdit()
        if (veTextSubPanel != null) {
            veTextSubPanel = null
            updateVeTextSubPanels()
        }
    }

    // ------------------------------------------------------------ text dialog

    // ------------------------------------------------------- inline text edit

    /**
     * In-place text editing: an EditText is laid out EXACTLY over the text box
     * (same rect, font, fitted size, color, alignment, background, rotation)
     * and the stage hides the item's own rendering while the keyboard is up.
     * No popup dialog is involved; the result is committed as ONE undo step
     * when editing ends.
     */
    private fun beginVeInlineEdit(item: VeOverlayItem) {
        if (currentToolId != TOOL_TEXT) return
        if (veInlineEditId == item.id) return
        if (veInlineEditId != -1L) endVeInlineEdit() // switching targets: commit the previous box first
        veInlineEditId = item.id
        binding.veOverlayStage.setEditingItemId(item.id)
        binding.veOverlayStage.selectItem(item.id)

        val et = binding.veInlineEditor
        et.setText(item.content)
        positionVeInlineEditor(item)
        applyVeInlineEditorProps(item, et.layoutParams.width, et.layoutParams.height)
        et.rotation = item.rotationDegrees
        et.visibility = View.VISIBLE
        et.requestLayout()
        et.setSelection(et.text?.length ?: 0)
        et.requestFocus()
        showKeyboard(et)
        // first-open race (M19p, 1st_text_box.png): the VERY first begin ran
        // while the stage/viewer backbone hadn't finished laying out, so the
        // editor consumed a stale measure pass and parked LEFT of the box
        // (subsequent boxes were fine). Re-derive everything from the settled
        // layout on the next frame; the stage OnLayoutChange hook then keeps
        // it glued (keyboard resizes included).
        binding.veOverlayStage.post {
            if (veInlineEditId == item.id && et.visibility == View.VISIBLE) {
                positionVeInlineEditor(item)
                applyVeInlineEditorProps(item, et.layoutParams.width, et.layoutParams.height)
                if (et.isFocused) showKeyboard(et)
            }
        }
    }

    /**
     * Pins the open inline editor EXACTLY over the item's stage box.
     * POSITION IS MARGIN-FREE ON PURPOSE: the layout params only carry
     * width/height, placement goes through translationX/Y (applied instantly,
     * never racing a measure pass). Called at begin, one frame after begin,
     * and on every stage layout change while a session is open.
     */
    private fun positionVeInlineEditor(item: VeOverlayItem) {
        val et = binding.veInlineEditor
        val rect = binding.veOverlayStage.itemRect(item)
        val w = rect.width().toInt().coerceAtLeast(24)
        val h = rect.height().toInt().coerceAtLeast(16)
        et.layoutParams?.let { lp ->
            lp.width = w
            lp.height = h   // fixed: typed text must never leave the box outline
            if (lp is ConstraintLayout.LayoutParams) {
                lp.marginStart = 0
                lp.topMargin = 0
            }
            et.layoutParams = lp
        }
        et.minHeight = h
        // itemRect() is stage-local; the EditText's parent is ve_holder and
        // the stage has an outer margin, so convert coordinates
        et.translationX = rect.left + binding.veOverlayStage.left
        et.translationY = rect.top + binding.veOverlayStage.top
    }

    /**
     * Applies the item's text style (color, font, fitted size, alignment,
     * background, renderer-matching padding) to the open inline editor.
     * Called at begin AND again whenever the selected item is restyled
     * (align/color/font/bg panels) while the keyboard is up: the stage hides
     * the item content during editing, so without this re-sync the typed text
     * kept whatever style was active when editing began (reported as the text
     * box "not remaining centered / not following alignment").
     */
    private fun applyVeInlineEditorProps(item: VeOverlayItem, w: Int, h: Int) {
        val et = binding.veInlineEditor
        et.setTextColor(item.color)
        et.typeface = item.fontFamily?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.DEFAULT
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = et.typeface }
        et.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            OverlayBitmapFactory.fitTextSize(paint, et.text?.toString()?.split('\n') ?: item.content.split('\n'), w, h),
        )
        et.gravity = when (item.textAlign) {
            0 -> Gravity.START or Gravity.CENTER_VERTICAL
            2 -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.CENTER
        }
        et.background = if (item.backgroundColor != Color.TRANSPARENT) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = h * 0.10f
                setColor(item.backgroundColor)
            }
        } else {
            null
        }
        // same inset as OverlayBitmapFactory's padX (6% of the box width), so
        // typed text sits exactly where the render/export will place it
        val padX = (w * 0.06f).toInt()
        et.setPadding(padX, 0, padX, 0)
    }

    /**
     * Re-fits the inline editor's font size to the CURRENT text and the box
     * geometry on every keystroke, so typed text never leaves the box outline
     * (long lines shrink; Enter adds lines and the whole stack shrinks).
     */
    private fun refitVeInlineEditor() {
        if (veInlineEditId == -1L) return
        val et = binding.veInlineEditor
        val lp = et.layoutParams ?: return
        val w = lp.width
        val h = if (lp.height > 0) lp.height else et.minHeight
        if (w <= 0 || h <= 0) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = et.typeface }
        et.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            OverlayBitmapFactory.fitTextSize(paint, et.text?.toString()?.split('\n') ?: listOf(""), w, h),
        )
    }

    private fun endVeInlineEdit() {
        val id = veInlineEditId
        if (id == -1L) return
        veInlineEditId = -1L
        val et = binding.veInlineEditor
        hideKeyboard()
        val text = et.text?.toString()?.trim().orEmpty()
        et.text?.clear()
        et.visibility = View.GONE
        et.translationX = 0f
        et.translationY = 0f
        binding.veOverlayStage.setEditingItemId(-1L)

        val item = state.items.firstOrNull { it.id == id } ?: return
        // an emptied text box is dropped
        val items = if (text.isEmpty()) {
            state.items.filterNot { it.id == id }
        } else {
            state.items.map { if (it.id == id) it.copy(content = text) else it }
        }
        if (text.isEmpty() && item.content.isEmpty()) {
            // a freshly ADDED box aborted without typing: fold the drop into
            // the add-step itself instead of leaving an invisible empty box in
            // the undo stack (undo would reveal a ghost box otherwise)
            val folded = state.copy(items = items)
            liveState = null
            history.mutateTop(folded)
            applyState(folded)
        } else {
            commitState(state.copy(items = items))
        }
        if (item.id != -1L && items.any { it.id == id }) {
            binding.veOverlayStage.selectItem(id)
        }
    }

    // ------------------------------------------------------------- thumbnails

    private fun loadThumbnails(videoUri: Uri) {
        ensureBackgroundThread {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, videoUri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durMs = durationStr?.toLongOrNull() ?: 0L
                if (durMs > 0 && durationMs <= 0) {
                    runOnUiThread {
                        if (durationMs <= 0) {
                            durationMs = durMs
                            binding.veSeek.max = durationMs.toInt()
                            binding.vePanelTrim.veTrimSlider.setDuration(durationMs * 1000)
                            updateTrimLabels(0L, durationMs * 1000)
                        }
                    }
                }

                // filmstrip for the trim slider
                if (durMs > 0) {
                    val frames = 8
                    val strip = (0 until frames).mapNotNull { i ->
                        try {
                            retriever.getFrameAtTime(
                                (durMs * 1000 * i / (frames - 1).coerceAtLeast(1)),
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )?.let { Bitmap.createScaledBitmap(it, 96, 96, true) }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    runOnUiThread { binding.vePanelTrim.veTrimSlider.setThumbnails(strip) }
                }

                // one mid-frame as the filter/overlay carousel base
                val frame = retriever.getFrameAtTime(
                    maxOf(1_000_000L, durMs * 500),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                val base = frame?.let { centerCropScale(it, 112) }
                retriever.release()
                retrieverBaseThumb = base

                runOnUiThread {
                    setupFilterCarousel()
                    setupOverlayCarousel()
                    updateTrimLabels(state.trimStartUs, state.trimEndUs.takeIf { it > 0 } ?: (durationMs * 1000))
                }
            } catch (e: Exception) {
                // thumbnails are decorative; the editor works without them
            }
        }
    }

    private fun centerCropScale(src: Bitmap, size: Int): Bitmap {
        val w = src.width
        val h = src.height
        val scale = size.toFloat() / minOf(w, h)
        val sw = (w * scale).toInt().coerceAtLeast(1)
        val sh = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x = ((sw - size) / 2).coerceAtLeast(0)
        val y = ((sh - size) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(scaled, x, y, minOf(size, sw), minOf(size, sh))
    }

    private lateinit var filterAdapter: VeThumbAdapter
    private lateinit var overlayAdapter: VeThumbAdapter

    private fun setupFilterCarousel() {
        val base = retrieverBaseThumb
        val entries = VideoFilterDefs.FILTERS.map { filter ->
            VeThumbEntry(filter.name) {
                base?.let { b ->
                    if (filter.isIdentity) {
                        b
                    } else {
                        val out = b.copy(Bitmap.Config.ARGB_8888, true)
                        applyFilterCpu(filter, out)
                        out
                    }
                }
            }
        }
        filterAdapter = VeThumbAdapter(entries) { index ->
            filterAdapter.selectedIndex = index
            commitState(state.copy(filterIndex = index))
        }
        filterAdapter.selectedIndex = state.filterIndex
        binding.vePanelFilter.veFilterRecycler.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = filterAdapter
        }
    }

    /** CPU version of the filter, for tiny carousel thumbnails only. */
    private fun applyFilterCpu(filter: com.goodwy.gallery.videoeditor.model.VeFilter, bmp: Bitmap) {
        val vignette = filter.vignette
        val w = bmp.width
        val h = bmp.height
        val cx = w / 2f
        val cy = h / 2f
        val maxD = kotlin.math.hypot(cx, cy)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                var r = Color.red(c) / 255f
                var g = Color.green(c) / 255f
                var b = Color.blue(c) / 255f
                val out = filter.apply(r, g, b)
                r = out[0]; g = out[1]; b = out[2]
                if (vignette > 0f) {
                    val d = kotlin.math.hypot(x - cx, y - cy) / maxD
                    val f = 1f - vignette * d * d
                    r *= f; g *= f; b *= f
                }
                px[i] = Color.argb(
                    Color.alpha(c),
                    (r.coerceIn(0f, 1f) * 255f).toInt(),
                    (g.coerceIn(0f, 1f) * 255f).toInt(),
                    (b.coerceIn(0f, 1f) * 255f).toInt(),
                )
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun setupOverlayCarousel() {
        val base = retrieverBaseThumb
        val entries = OverlayBitmapFactory.PRESETS.mapIndexed { index, preset ->
            VeThumbEntry(preset.name) {
                if (index == 0) base else base?.let { OverlayBitmapFactory.tintedThumb(it, index) }
            }
        }
        overlayAdapter = VeThumbAdapter(entries) { index ->
            overlayAdapter.selectedIndex = index
            commitState(state.copy(overlayIndex = index))
        }
        overlayAdapter.selectedIndex = state.overlayIndex
        binding.vePanelOverlay.veOverlayRecycler.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = overlayAdapter
        }
    }

    // ---------------------------------------------------------------- sharing

    private fun editWith() {
        val path = realPath ?: return
        openEditor(path, forceChooser = true)
    }

    // ------------------------------------------------------------- save flow

    private fun startSaveFlow(overwrite: Boolean) {
        endVeInlineEdit() // commit any in-progress inline text edit first
        val p = player ?: return
        if (videoWidth <= 0 || videoHeight <= 0) {
            toast(R.string.video_editing_failed)
            return
        }
        p.pause()

        if (isImage) {
            // no format prompt: every path keeps the SOURCE format and its
            // extension (PNG/WEBP keep alpha; everything else maps to JPEG)
            startImageSaveFlow(overwrite)
            return
        }

        val temp = File(cacheDir, "ve_export_${System.currentTimeMillis()}.mp4")
        tempOutput = temp
        val effects = VideoEffectsAssembler.assemble(state, videoWidth, videoHeight, includeOverlays = true) { brushLayer() }

        showExportDialog()
        exporter.export(
            mediaItem = buildMediaItem(state),
            videoEffects = effects,
            removeAudio = state.muted,
            outputFile = temp,
            callback = object : VideoExporter.Callback {
                override fun onProgress(percent: Int) = updateExportProgress(percent)

                override fun onCompleted(output: File) {
                    if (overwrite) {
                        writeExportToSaveUri(output)
                    } else {
                        resolveSaveAs(output)
                    }
                }

                override fun onError(message: String) {
                    dismissExportDialog()
                    showErrorToast(getString(R.string.ve_export_failed, message))
                    temp.delete()
                }

                override fun onCancelled() {
                    dismissExportDialog()
                    temp.delete()
                }
            },
        )
    }

    // ------------------------------------------------------ still image save

    /**
     * Image export through the SAME effect chain as the preview (crop, rotate,
     * flip, filter, adjust, focus, overlays, text/stickers, brush — all baked):
     * [StillImageRenderer] renders the (pre-rotated) input bitmap through the
     * media3 frame processor chain and reads the final frame back, so what
     * was previewed is exactly what gets written. The result is encoded in
     * the SOURCE's format (PNG/WEBP keep their format + alpha, else JPEG).
     */
    private fun startImageSaveFlow(overwrite: Boolean) {
        stillCancelled = false
        showExportDialog(indeterminate = true, labelRes = R.string.ve_rendering_image)
        val fmt = sourceImageFormat()
        renderEditedStill { bitmap, renderError ->
            if (stillCancelled) return@renderEditedStill
            val temp = File(cacheDir, "ve_export_${System.currentTimeMillis()}.${fmt.ext}")
            tempOutput = temp
            // multi-megapixel encode stays off the UI thread
            ensureBackgroundThread {
                val ok = bitmap != null && encodeStill(bitmap, temp, fmt.compress)
                runOnUiThread {
                    if (stillCancelled) return@runOnUiThread
                    if (!ok) {
                        dismissExportDialog()
                        temp.delete()
                        showErrorToast(getString(
                            R.string.ve_export_failed,
                            renderError ?: getString(R.string.ve_image_render_failed),
                        ))
                        return@runOnUiThread
                    }
                    when {
                        isCropIntent -> commitCropResult(temp)
                        overwrite -> writeExportToSaveUri(temp)
                        else -> resolveSaveAs(temp, fmt.ext)
                    }
                }
            }
        }
    }

    private var stillCancelled = false

    /**
     * Renders the finished still with [StillImageRenderer] — the SAME effect
     * chain as the preview and the video export (media3 1.9.2's inspector
     * FrameExtractor can't extract IMAGE items: its internal player registers
     * only a video renderer, so the future never completed on-device).
     * Result arrives on the main thread; null = render or pipeline failure
     * (surfaced by the caller). A watchdog caps GL/driver stalls so the
     * "Rendering image" dialog can never spin forever.
     */
    private fun renderEditedStill(callback: (Bitmap?, String?) -> Unit) {
        val effects = try {
            VideoEffectsAssembler.assemble(state, videoWidth, videoHeight, includeOverlays = true) { brushLayer() }
        } catch (e: Exception) {
            callback(null, "assemble: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            return
        }
        val input = imageTempInput
        if (input == null || !input.exists()) {
            callback(null, "input cache missing")
            return
        }

        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        var watchdog: Runnable? = null
        val deliver: (Bitmap?, String?) -> Unit = { bitmap, error ->
            if (delivered.compareAndSet(false, true)) {
                watchdog?.let { handler.removeCallbacks(it) }
                callback(bitmap, error)
            }
        }
        val wd = Runnable { deliver(null, "timeout ${STILL_RENDER_TIMEOUT_MS / 1000}s") }
        watchdog = wd
        handler.postDelayed(wd, STILL_RENDER_TIMEOUT_MS)

        StillImageRenderer.render(
            context = applicationContext,
            inputFile = input,
            width = videoWidth,
            height = videoHeight,
            effects = effects,
        ) { bitmap, error -> handler.post { deliver(bitmap, error) } }
    }

    /** Encode format + file extension for an exported still. */
    private data class ImageOutFormat(val compress: Bitmap.CompressFormat, val ext: String)

    /** The SOURCE image's own format (PNG/WEBP keep alpha and format,
     *  everything else maps to high-quality JPEG). */
    private fun sourceImageFormat(): ImageOutFormat = when (inputMime()) {
        "image/png" -> ImageOutFormat(Bitmap.CompressFormat.PNG, "png")
        "image/webp" -> ImageOutFormat(Bitmap.CompressFormat.WEBP, "webp")
        else -> ImageOutFormat(Bitmap.CompressFormat.JPEG, "jpg")
    }

    private fun encodeStill(bitmap: Bitmap, out: File, format: Bitmap.CompressFormat): Boolean = try {
        FileOutputStream(out).use { bitmap.compress(format, if (format == Bitmap.CompressFormat.JPEG) 95 else 100, it) }
        out.length() > 0
    } catch (e: Exception) {
        false
    }

    /** Crop-contract commit: the cropped still goes to the caller's requested
     *  destination (EXTRA_OUTPUT content uri, or the source file when no output
     *  was supplied) and the result carries the uri with read permission —
     *  the semantics the legacy EditActivity exposed. */
    private fun commitCropResult(temp: File) {
        resolveUriScheme(
            uri = saveUri,
            onPath = { path ->
                ensureWritablePath(targetPath = path, confirmOverwrite = false) { writable ->
                    copyExported(temp, writable) // scans, toasts, setResult, finish
                }
            },
            onContentUri = { contentUri ->
                dismissExportDialog()
                ensureBackgroundThread {
                    val ok = copyToContentUri(temp, contentUri)
                    temp.delete()
                    runOnUiThread {
                        if (ok) {
                            setResult(RESULT_OK, Intent().setData(contentUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            finish()
                        } else {
                            toast(R.string.image_editing_failed)
                        }
                    }
                }
            },
        )
    }

    private fun showExportDialog(indeterminate: Boolean = false, labelRes: Int = R.string.ve_exporting) {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = indeterminate
        }
        val label = TextView(this).apply {
            setText(labelRes)
            setPadding(0, 16, 0, 0)
        }
        container.addView(bar)
        container.addView(label)
        exportDialogProgress = bar
        exportDialogLabel = label
        // app-standard dialog wrapper: themed rounded background + button colors
        getAlertDialogBuilder()
            .setCancelable(false)
            .setNegativeButton(com.goodwy.commons.R.string.cancel) { _, _ ->
                if (exporter.isRunning) {
                    exporter.cancel()
                } else {
                    stillCancelled = true   // still render has no cancel; just abort the flow
                    dismissExportDialog()
                    tempOutput?.delete()
                }
            }
            .apply {
                setupDialogStuff(
                    view = container,
                    dialog = this,
                    titleId = labelRes,
                ) { alertDialog ->
                    exportDialog = alertDialog
                }
            }
    }

    private fun updateExportProgress(percent: Int) {
        exportDialogProgress?.progress = percent
        exportDialogLabel?.text = "${getString(R.string.ve_exporting)} $percent%"
    }

    private fun dismissExportDialog() {
        exportDialog?.dismiss()
        exportDialog = null
    }

    private fun resolveSaveAs(source: File, extension: String? = null) {
        // when the user picked a container format, the proposed destination
        // must carry the matching extension (bytes are copied verbatim)
        fun adjustExt(path: String): String {
            if (extension == null) return path
            val name = path.substringAfterLast('/')
            return if ('.' in name) path.substringBeforeLast('.') + ".$extension" else "$path.$extension"
        }
        resolveUriScheme(
            uri = saveUri,
            onPath = { path ->
                SaveAsDialog(this, adjustExt(path), true, cancelCallback = { abandonSave(source) }) { destination ->
                    writeExportToPath(source, destination)
                }
            },
            onContentUri = { contentUri ->
                val (path, append) = proposeNewFilePath(contentUri)
                SaveAsDialog(this, adjustExt(path), append, cancelCallback = { abandonSave(source) }) { destination ->
                    writeExportToPath(source, destination)
                }
            },
        )
    }

    private fun abandonSave(source: File) {
        dismissExportDialog()
        toast(R.string.video_editing_cancelled)
        source.delete()
    }

    private fun writeExportToSaveUri(source: File) {
        resolveUriScheme(
            uri = saveUri,
            onPath = { path ->
                ensureWritablePath(targetPath = path, confirmOverwrite = false) { writable ->
                    copyExported(source, writable)
                }
            },
            onContentUri = { contentUri ->
                dismissExportDialog()
                ensureBackgroundThread {
                    val ok = copyToContentUri(source, contentUri)
                    if (ok) finishSave(source, destinationSnapshot = null) else failSave(source)
                }
            },
        )
    }

    private fun writeExportToPath(source: File, destinationPath: String) {
        ensureWritablePath(targetPath = destinationPath, confirmOverwrite = false) { writable ->
            copyExported(source, writable)
        }
    }

    private fun copyExported(source: File, destinationPath: String) {
        ensureBackgroundThread {
            try {
                File(destinationPath).let { dest -> if (dest.exists()) dest.delete() }
                source.inputStream().use { input ->
                    FileOutputStream(destinationPath).use { output -> input.copyTo(output) }
                }
                finishSave(source, destinationSnapshot = destinationPath)
            } catch (e: Exception) {
                failSave(source, e)
            }
        }
    }

    private fun copyToContentUri(source: File, contentUri: Uri): Boolean = try {
        contentResolver.openOutputStream(contentUri, "w")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } != null
    } catch (e: Exception) {
        false
    }

    private fun finishSave(source: File, destinationSnapshot: String?) {
        val destination = destinationSnapshot ?: realPath
        dismissExportDialog()
        source.delete()
        if (destination != null) {
            val paths = arrayListOf(destination)
            rescanPaths(paths) {
                fixDateTaken(paths, false)
                runOnUiThread {
                    toast(R.string.file_edited_successfully)
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        } else {
            runOnUiThread {
                toast(R.string.file_edited_successfully)
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private fun failSave(source: File, e: Exception? = null) {
        dismissExportDialog()
        source.delete()
        runOnUiThread {
            if (e != null) showErrorToast(e) else toast(R.string.video_editing_failed)
        }
    }
}
