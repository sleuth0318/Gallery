/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app video editor (foss flavor). Functional FOSS re-implementation of the
 * tool set that Simple Mobile Tools' Simple Gallery exposed via the (non-free)
 * img.ly SDK wrapper — rebuilt on AndroidX Media3 preview/transformer export
 * and themed with this app's own image-editor design language.
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.goodwy.commons.dialogs.ColorPickerDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.ActivityVideoEditBinding
import com.goodwy.gallery.databinding.DialogVeTextBinding
import com.goodwy.gallery.dialogs.SaveAsDialog
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.ensureWritablePath
import com.goodwy.gallery.extensions.fixDateTaken
import com.goodwy.gallery.extensions.openEditor
import com.goodwy.gallery.extensions.proposeNewFilePath
import com.goodwy.gallery.extensions.resolveUriScheme
import com.goodwy.gallery.extensions.shareMediumPath
import com.goodwy.gallery.helpers.getPermissionToRequest
import com.goodwy.gallery.videoeditor.effects.OverlayBitmapFactory
import com.goodwy.gallery.videoeditor.effects.VideoEffectsAssembler
import com.goodwy.gallery.videoeditor.export.VideoExporter
import com.goodwy.gallery.videoeditor.model.EditHistory
import com.goodwy.gallery.videoeditor.model.VeAdjustments
import com.goodwy.gallery.videoeditor.model.VeAspectOption
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

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("NotifyDataSetChanged")
class VideoEditActivity : SimpleActivity(), OverlayStageView.Listener, TrimSliderView.Listener {

    companion object {
        private const val TOOL_TRIM = "trim"
        private const val TOOL_TRANSFORM = "transform"
        private const val TOOL_FILTER = "filter"
        private const val TOOL_ADJUST = "adjust"
        private const val TOOL_FOCUS = "focus"
        private const val TOOL_STICKER = "sticker"
        private const val TOOL_TEXT = "text"
        private const val TOOL_BRUSH = "brush"
        private const val TOOL_OVERLAY = "overlay"

        private const val POLL_MS = 200L

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

    private var retrieverBaseThumb: Bitmap? = null
    private var brushBitmapCache: Pair<Int, Bitmap>? = null

    private lateinit var toolAdapter: VeToolAdapter
    private lateinit var aspectAdapter: VeChipAdapter
    private lateinit var adjustAdapter: VeAdjustAdapter
    private var selectedAdjustKey: String? = null
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
        setupEdgeToEdge(padBottomSystem = listOf(binding.vePrimaryActions.root))

        binding.veOverlayStage.listener = this
        binding.vePanelTrim.veTrimSlider.listener = this

        setupOptionsMenu()
        setupBottomActions()
        setupPanels()

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
            binding.vePrimaryActions.bottomPrimaryCancel.setTextColor(getProperPrimaryColor())
            binding.vePrimaryActions.bottomPrimarySave.setTextColor(getProperPrimaryColor())
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
        toolAdapter = VeToolAdapter(
            tools = TOOLS,
            activeColor = { getProperPrimaryColor() },
            idleColor = { Color.WHITE },
        ) { tool -> toolClicked(tool.id) }
        binding.veToolRow.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = toolAdapter
        }
        binding.vePrimaryActions.bottomPrimaryCancel.setOnClickListener { finish() }
        binding.vePrimaryActions.bottomPrimarySave.setOnClickListener { startSaveFlow(overwrite = false) }
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
            stickerColor = savedBrushColor
        }
        probeDurationThenStart(resolved)
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
                showErrorToast("${getString(R.string.video_editing_failed)}: ${error.errorCodeName}")
            }
        })

        p.repeatMode = Player.REPEAT_MODE_ALL
        p.setComposition(buildComposition(emptyList()), /* startPositionMs = */ 0L)
        p.prepare()
        p.play()

        setupTransport()
        startPolling()
    }

    private fun setupTransport() {
        binding.veBtnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
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
    private fun buildComposition(videoEffects: List<Effect>): Composition {
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri!!))
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
        val builder = MediaItem.Builder().setUri(uri)
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
     * (not part of the preview chain) and pending brush settings do NOT, so a
     * composition swap for those alone is pure churn — skipped via this key.
     */
    private fun VideoEditState.visualKey(): VideoEditState =
        copy(muted = false, trimStartUs = 0L, trimEndUs = -1L, brushColor = 0, brushSizeFraction = 0f)

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
        val visualKey = s.visualKey()
        if (videoWidth > 0 && videoHeight > 0 && durationMs > 0 && visualKey != lastPushedVisualKey) {
            lastPushedVisualKey = visualKey
            val effects = VideoEffectsAssembler.assemble(s, videoWidth, videoHeight) { brushLayer() }
            // live slider drags are throttled; commits always apply immediately
            pushComposition(p, effects, throttle = isLiveDrag)
            val (outW, outH) = VideoEffectsAssembler.outputSize(s, videoWidth, videoHeight)
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
        refreshStageContent()
        syncPanels(s)
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
        currentToolId = if (currentToolId == toolId) null else toolId
        toolAdapter.activeToolId = currentToolId
        updatePanelsVisibility()
        updateStageMode()
        syncPanels(state)
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
        if (currentToolId == TOOL_TEXT) {
            // text uses a dialog to enter content, then items are dragged on stage
            showTextDialog(null)
        }
    }

    private fun updateStageMode() {
        val stage = binding.veOverlayStage
        stage.showFocusMarker = currentToolId == TOOL_FOCUS &&
            (state.focusMode == VeFocusMode.RADIAL || state.focusMode == VeFocusMode.LINEAR)
        stage.mode = when {
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
        // focus
        binding.vePanelFocus.veSeekFocus.progress = (s.focusStrength * 100).toInt()
        tintFocusChips(s.focusMode)
        binding.vePanelFocus.veFocusHint.beVisibleIf(s.focusMode == VeFocusMode.RADIAL || s.focusMode == VeFocusMode.LINEAR)
        // brush
        binding.vePanelBrush.veBrushSize.progress = (((s.brushSizeFraction - 0.004f) / 0.076f) * 100).toInt().coerceIn(0, 100)
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
    }

    private fun setupTransformPanel() {
        binding.vePanelTransform.veBtnRotate.setOnClickListener {
            commitState(state.copy(rotationDegrees = (state.rotationDegrees + 90f) % 360f))
        }
        binding.vePanelTransform.veBtnFlipH.setOnClickListener {
            commitState(state.copy(flipHorizontal = !state.flipHorizontal))
        }
        binding.vePanelTransform.veBtnFlipV.setOnClickListener {
            commitState(state.copy(flipVertical = !state.flipVertical))
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
        val current = state.aspectOption
        val updated = if (option.id == current.id && option.ratio != null) {
            // tapping the same chip flips landscape <-> portrait (parity w/ the reference editor)
            val flippedRatio = 1f / option.ratio
            val (x, y) = flipLabel(option.label)
            VeAspectOption(option.id, "$y:$x", flippedRatio)
        } else {
            option
        }
        commitState(state.copy(aspectOption = updated))
        syncAspectSelection()
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

    private var stickerColor = Color.parseColor("#FFEE58")
    private var stickerShapesShown = false

    private fun setupStickerPanel() {
        stickerColor = config.editorBrushColor.takeIf { it != 0 } ?: stickerColor
        binding.vePanelSticker.veStickerTabEmojis.setOnClickListener { showStickerTab(shapes = false) }
        binding.vePanelSticker.veStickerTabShapes.setOnClickListener { showStickerTab(shapes = true) }
        binding.vePanelSticker.veStickerColor.setOnClickListener { pickStickerColor() }
        showStickerTab(shapes = false)
        updateStickerColorPreview()
    }

    private fun showStickerTab(shapes: Boolean) {
        stickerShapesShown = shapes
        val accent = getProperPrimaryColor()
        binding.vePanelSticker.veStickerTabEmojis.setTextColor(if (!shapes) accent else Color.WHITE)
        binding.vePanelSticker.veStickerTabShapes.setTextColor(if (shapes) accent else Color.WHITE)
        binding.vePanelSticker.veStickerRecycler.apply {
            layoutManager = GridLayoutManager(this@VideoEditActivity, 2, GridLayoutManager.HORIZONTAL, false)
            adapter = if (shapes) {
                VeShapeAdapter(OverlayBitmapFactory.SHAPES, { OverlayBitmapFactory.shapeBitmap(it, stickerColor, 96) }) { shapeId ->
                    addItem(VeOverlayItem.Kind.SHAPE, shapeId, stickerColor)
                }
            } else {
                VeEmojiAdapter(EMOJIS) { emoji -> addItem(VeOverlayItem.Kind.EMOJI, emoji, Color.WHITE) }
            }
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
                if (stickerShapesShown) showStickerTab(shapes = true)
            }
        }
    }

    private fun updateBrushColorPreview() {
        binding.vePanelBrush.veBrushColor.setImageDrawable(colorCircle(state.brushColor))
    }

    // ----------------------------------------------------------------- brush

    private fun setupBrushPanel() {
        binding.vePanelBrush.veBrushColor.setOnClickListener { pickBrushColor() }
        binding.vePanelBrush.veBrushSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val fraction = 0.004f + (progress / 100f) * 0.076f
                    applyStateLive(state.copy(brushSizeFraction = fraction))
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
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

    // ----------------------------------------------------------- stage events

    private fun addItem(kind: VeOverlayItem.Kind, content: String, color: Int, fontFamily: String? = null) {
        val item = VeOverlayItem(
            id = nextItemId++,
            kind = kind,
            content = content,
            color = color,
            fontFamily = fontFamily,
            centerX = 0.5f,
            centerY = 0.45f,
        )
        commitState(state.copy(items = state.items + item))
        if (kind == VeOverlayItem.Kind.EMOJI || kind == VeOverlayItem.Kind.SHAPE) {
            toast(R.string.ve_sticker_added)
        }
        updateStageMode()
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
            showTextDialog(item)
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

    private var focusCommitRunnable: Runnable? = null

    override fun onStageTappedEmpty() {}

    // ------------------------------------------------------------ text dialog

    private fun showTextDialog(existing: VeOverlayItem?) {
        val dialogBinding = DialogVeTextBinding.inflate(LayoutInflater.from(this))
        var color = existing?.color ?: Color.WHITE
        var font: String? = existing?.fontFamily
        dialogBinding.veTextInput.setText(existing?.content ?: "")
        dialogBinding.veTextColor.setImageDrawable(colorCircle(color))

        var fontAdapterRef: VeChipAdapter? = null
        val fontAdapter = VeChipAdapter(
            FONTS.map { it.first },
            typefaceFor = { idx -> FONTS[idx].second?.let { Typeface.create(it, Typeface.NORMAL) } },
        ) { idx ->
            font = FONTS[idx].second
            fontAdapterRef?.selectedIndex = idx
        }
        fontAdapterRef = fontAdapter
        fontAdapter.setColors(getProperPrimaryColor(), getProperTextColor())
        fontAdapter.selectedIndex = FONTS.indexOfFirst { it.second == font }.coerceAtLeast(0)
        dialogBinding.veFontRecycler.apply {
            layoutManager = LinearLayoutManager(this@VideoEditActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = fontAdapter
        }
        dialogBinding.veTextColor.setOnClickListener {
            ColorPickerDialog(this, color) { ok, picked, _ ->
                if (ok) {
                    color = picked
                    dialogBinding.veTextColor.setImageDrawable(colorCircle(color))
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(getString(if (existing == null) R.string.ve_text_add else R.string.ve_text_edit))
            .setView(dialogBinding.root)
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ ->
                val text = dialogBinding.veTextInput.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    if (existing != null) {
                        val updated = state.items.map {
                            if (it.id == existing.id) it.copy(content = text, color = color, fontFamily = font) else it
                        }
                        commitState(state.copy(items = updated))
                    } else {
                        addItem(VeOverlayItem.Kind.TEXT, text, color, font)
                    }
                }
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .show()
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
        val p = player ?: return
        if (videoWidth <= 0 || videoHeight <= 0) {
            toast(R.string.video_editing_failed)
            return
        }
        p.pause()

        val temp = File(cacheDir, "ve_export_${System.currentTimeMillis()}.mp4")
        tempOutput = temp
        val effects = VideoEffectsAssembler.assemble(state, videoWidth, videoHeight) { brushLayer() }

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

    private fun showExportDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
        }
        val label = TextView(this).apply {
            setText(R.string.ve_exporting)
            setPadding(0, 16, 0, 0)
        }
        container.addView(bar)
        container.addView(label)
        exportDialogProgress = bar
        exportDialogLabel = label
        exportDialog = AlertDialog.Builder(this)
            .setTitle(R.string.ve_exporting)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(com.goodwy.commons.R.string.cancel) { _, _ -> exporter.cancel() }
            .show()
    }

    private fun updateExportProgress(percent: Int) {
        exportDialogProgress?.progress = percent
        exportDialogLabel?.text = "${getString(R.string.ve_exporting)} $percent%"
    }

    private fun dismissExportDialog() {
        exportDialog?.dismiss()
        exportDialog = null
    }

    private fun resolveSaveAs(source: File) {
        resolveUriScheme(
            uri = saveUri,
            onPath = { path ->
                SaveAsDialog(this, path, true, cancelCallback = { abandonSave(source) }) { destination ->
                    writeExportToPath(source, destination)
                }
            },
            onContentUri = { contentUri ->
                val (path, append) = proposeNewFilePath(contentUri)
                SaveAsDialog(this, path, append, cancelCallback = { abandonSave(source) }) { destination ->
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
