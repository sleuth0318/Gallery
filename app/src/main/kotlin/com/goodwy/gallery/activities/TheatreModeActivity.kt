@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])

package com.goodwy.gallery.activities

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.google.android.material.appbar.AppBarLayout
import com.goodwy.commons.extensions.*
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.ActivityTheatreModeBinding
import com.goodwy.gallery.extensions.*
import com.goodwy.gallery.fragments.TheatreTrackOption
import com.goodwy.gallery.fragments.TheatreTrackSelectionListener
import com.goodwy.gallery.fragments.TheatreTrackSelectorFragment
import com.goodwy.gallery.helpers.*

class TheatreModeActivity : BaseViewerActivity(), SeekBar.OnSeekBarChangeListener,
    TextureView.SurfaceTextureListener, TheatreTrackSelectionListener {

    companion object {
        private const val TAG = "TheatreMode"
        private const val UPDATE_INTERVAL_MS = 250L
        private const val CHROME_HIDE_DELAY = 3000L
        private const val SEEK_FEEDBACK_DURATION = 1200L
        private const val CHROME_FADE_DURATION = 200L
        private const val PLAY_WHEN_READY_DRAG_DELAY = 100L
        private const val REQUEST_EXTERNAL_AUDIO = 5101
        private const val REQUEST_EXTERNAL_SUBTITLE = 5102

        private const val PIP_ACTION_PLAY = "com.goodwy.gallery.PIP_ACTION_PLAY"
        private const val PIP_ACTION_SEEK = "com.goodwy.gallery.PIP_ACTION_SEEK"
        private const val PIP_EXTRA_SEEK_FORWARD = "PIP_EXTRA_SEEK_FORWARD"
        private const val PIP_REQUEST_PLAY_PAUSE = 6101
        private const val PIP_REQUEST_SEEK = 6102
    }

    private val mPipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PIP_ACTION_PLAY -> {
                    togglePipPlayPause()
                    updatePipActions()
                }
                PIP_ACTION_SEEK -> pipSeek(intent.getBooleanExtra(PIP_EXTRA_SEEK_FORWARD, false))
            }
        }
    }

    private var mIsPlaying = false
    private var mWasVideoStarted = false
    private var mIsDragged = false
    private var mHasAudio = true
    private var mCurrTime = 0L
    private var mDuration = 0L
    private var mChromeVisible = true
    private var mPendingSelectExternalAudio = false
    private var mUserDisabledTextTrack = false
    private var mUserTriggeredExternalNav = false
    private var mWasInPip = false
    private var mPipResumePositionMs = -1L
    private var mPipRestoring = false
    private var mPipTrackRestored = false
    private var mPipResumeAudioGroup = -1
    private var mPipResumeAudioTrack = -1
    private var mPipResumeTextGroup = -1
    private var mPipResumeTextTrack = -1

    private var mUri: Uri? = null
    private var mExoPlayer: ExoPlayer? = null
    private var mVideoSize = Point(0, 0)
    private var mTimerHandler = Handler(Looper.getMainLooper())
    private var mPlayWhenReadyHandler = Handler(Looper.getMainLooper())
    private var mChromeHandler = Handler(Looper.getMainLooper())
    private var mSeekFeedbackHandler = Handler(Looper.getMainLooper())
    private var mOriginalBrightness: Float? = null

    private var mExternalAudioUri: Uri? = null
    private var mExternalAudioMime: String? = null
    private var mExternalSubtitleUri: Uri? = null
    private var mExternalSubtitleMime: String? = null

    private val binding by viewBinding(ActivityTheatreModeBinding::inflate)

    override val contentHolder: View
        get() = binding.theatrePlayerHolder

    override val appBarLayout: AppBarLayout
        get() = binding.theatreAppbar

    private val mHideChromeRunnable = Runnable { hideChrome() }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.theatreBottomBar.root))
        setupOrientation()
        registerPipActions()
        initPlayer()
    }

    override fun onResume() {
        super.onResume()
        mUserTriggeredExternalNav = false
        mWasInPip = false
        config.clearPendingPipResume()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (config.blackBackground) {
            binding.theatrePlayerHolder.background = Color.BLACK.toDrawable()
        }
        mOriginalBrightness = window.updateBrightness(config.maxBrightness, mOriginalBrightness)
        updateTextColors(binding.theatrePlayerHolder)
    }

    override fun onPause() {
        super.onPause()
        // While entering or inside picture-in-picture mode the video must keep playing,
        // so the pop-up window keeps rendering. The system resumes the activity (and
        // onResume) once the pop-up is expanded back to fullscreen.
        if (!isInPictureInPictureMode && !isChangingConfigurations) {
            pauseVideo()
        }
        if (config.rememberLastVideoPosition && mWasVideoStarted) {
            saveVideoProgress()
        }
    }

    override fun onStop() {
        super.onStop()
        // Dismissing the picture-in-picture window (the ✕ button) removes this activity
        // from the task. Pause playback so it doesn't keep running in the background and
        // persist an exact resume point so the video can be reopened at the same timestamp.
        if (!isChangingConfigurations && mWasVideoStarted && mExoPlayer != null) {
            pauseVideo()
            if (mWasInPip) {
                savePipResumePoint()
            } else if (config.rememberLastVideoPosition) {
                saveVideoProgress()
            }
        }
    }

    private fun savePipResumePoint() {
        if (config.rememberLastVideoPosition) {
            saveVideoProgress()
        }
        val path = mUri?.toString() ?: return
        config.pendingPipResumePath = path
        config.pendingPipResumePositionMs = mExoPlayer?.currentPosition ?: 0L

        // Persist the audio/subtitle track selection so the reopened player restores it.
        config.pendingPipExternalAudioUri = mExternalAudioUri?.toString() ?: ""
        config.pendingPipExternalSubtitleUri = mExternalSubtitleUri?.toString() ?: ""

        val tracks = mExoPlayer?.currentTracks ?: return
        config.pendingPipAudioGroup = -1
        config.pendingPipAudioTrack = -1
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_AUDIO && group.length > 0 && group.isAnyTrackSelected()) {
                config.pendingPipAudioGroup = groupIndex
                for (trackIndex in 0 until group.length) {
                    if (group.isTrackSelected(trackIndex)) {
                        config.pendingPipAudioTrack = trackIndex
                        break
                    }
                }
            }
        }

        config.pendingPipTextGroup = -1
        config.pendingPipTextTrack = -1
        if (!tracks.containsType(C.TRACK_TYPE_TEXT)) {
            config.pendingPipTextDisabled = false
        } else if (!tracks.isTypeSelected(C.TRACK_TYPE_TEXT)) {
            config.pendingPipTextDisabled = true
        } else {
            config.pendingPipTextDisabled = false
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type == C.TRACK_TYPE_TEXT && group.length > 0 && group.isAnyTrackSelected()) {
                    config.pendingPipTextGroup = groupIndex
                    for (trackIndex in 0 until group.length) {
                        if (group.isTrackSelected(trackIndex)) {
                            config.pendingPipTextTrack = trackIndex
                            break
                        }
                    }
                }
            }
        }
    }

    private fun Tracks.Group.isAnyTrackSelected(): Boolean = (0 until length).any { isTrackSelected(it) }


    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // User pressed Home while a video is open: pin the video as a pop-up window
        // with the same aspect ratio as the video and a play/pause action.
        // Skip when the user is navigating back/menu-exiting (only Home should trigger PIP),
        // or when we knowingly left for another in-app activity (external track pickers).
        if (mUserTriggeredExternalNav || isFinishing) {
            mUserTriggeredExternalNav = false
            return
        }
        if (mExoPlayer != null && !isInPictureInPictureMode) {
            enterPictureInPictureMode(buildPipParams())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            mWasInPip = true
            // Hide the fullscreen chrome; keep the subtitle view visible so subtitles
            // keep rendering inside the pop-up window.
            mChromeHandler.removeCallbacks(mHideChromeRunnable)
            binding.theatreAppbar.beGone()
            binding.theatreBottomBar.root.beGone()
            binding.theatreBrightnessController.beGone()
            binding.theatreVolumeController.beGone()
            binding.theatrePreciseSeek.beGone()
            binding.theatreVolumeIndicator.beGone()
            binding.theatreBrightnessIndicator.beGone()
            binding.theatreSeekFeedbackBackward.beGone()
            binding.theatreSeekFeedbackForward.beGone()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Re-fit the surface so the whole video is visible inside the pop-up window,
            // letterboxed if the window ratio differs from the video's ratio.
            fitSurfaceToPipWindow()
            // Keep fitting on every subsequent window resize (pinch/double-tap) so the
            // video never gets cropped once the user shrinks the pop-up below the size
            // it had at entry.
            mLastPipFrameWidth = 0
            mLastPipFrameHeight = 0
            binding.theatreSurfaceFrame.viewTreeObserver.addOnGlobalLayoutListener(mPipResizeListener)
        } else {
            // Back to fullscreen: restore the chrome, then re-fit the surface to the screen.
            binding.theatreSurfaceFrame.viewTreeObserver.removeOnGlobalLayoutListener(mPipResizeListener)
            binding.theatreBrightnessController.beVisible()
            binding.theatreVolumeController.beVisible()
            binding.theatreVolumeIndicator.beVisible()
            binding.theatreBrightnessIndicator.beVisible()
            setVideoSize()
            binding.theatreSurfaceFrame.onGlobalLayout {
                binding.theatreSurfaceFrame.controller.resetState()
            }
            if (mIsPlaying) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            showChrome(scheduleHide = true)
            updatePipActions()
        }
    }

    private fun registerPipActions() {
        ContextCompat.registerReceiver(
            this,
            mPipActionReceiver,
            IntentFilter().apply {
                addAction(PIP_ACTION_PLAY)
                addAction(PIP_ACTION_SEEK)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun updatePipActions() {
        if (isInPictureInPictureMode) {
            setPictureInPictureParams(buildPipParams())
        }
    }

    private fun buildPipParams(): PictureInPictureParams {
        return PictureInPictureParams.Builder()
            .setAspectRatio(buildPipAspectRatio())
            .setActions(buildPipActions().take(maxNumPictureInPictureActions))
            .apply {
                // Smoothly scale video content while the user pinches the pop-up; the final
                // window size is then reflected by a layout pass handled by mPipResizeListener.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    }

    private fun buildPipActions(): List<RemoteAction> {
        val playing = mExoPlayer?.isPlaying == true
        val toggleIntent = PendingIntent.getBroadcast(
            this,
            PIP_REQUEST_PLAY_PAUSE,
            Intent(PIP_ACTION_PLAY).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val seekBackwardIntent = PendingIntent.getBroadcast(
            this,
            PIP_REQUEST_SEEK,
            Intent(PIP_ACTION_SEEK).setPackage(packageName).putExtra(PIP_EXTRA_SEEK_FORWARD, false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val seekForwardIntent = PendingIntent.getBroadcast(
            this,
            PIP_REQUEST_SEEK,
            Intent(PIP_ACTION_SEEK).setPackage(packageName).putExtra(PIP_EXTRA_SEEK_FORWARD, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return listOf(
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_backward_10_vector),
                getString(R.string.ve_picture_in_picture_seek_backward),
                getString(R.string.ve_picture_in_picture_seek_backward),
                seekBackwardIntent
            ),
            RemoteAction(
                Icon.createWithResource(this, if (playing) R.drawable.ic_pause_vector else R.drawable.ic_play_vector),
                getString(if (playing) R.string.ve_pause else R.string.ve_play),
                getString(R.string.ve_picture_in_picture_playback),
                toggleIntent
            ),
            RemoteAction(
                Icon.createWithResource(this, R.drawable.ic_pip_forward_10_vector),
                getString(R.string.ve_picture_in_picture_seek_forward),
                getString(R.string.ve_picture_in_picture_seek_forward),
                seekForwardIntent
            )
        )
    }

    private fun buildPipAspectRatio(): Rational {
        val width = mVideoSize.x.coerceAtLeast(1)
        val height = mVideoSize.y.coerceAtLeast(1)
        return Rational(width, height)
    }

    private fun fitSurfaceToPipWindow() {
        // The system fires this callback once the PiP enter animation has completed and the
        // pop-up has been laid out, so the surface frame reflects the real pop-up size.
        binding.theatreSurfaceFrame.rootView.onGlobalLayout {
            val frameWidth = binding.theatreSurfaceFrame.width
            val frameHeight = binding.theatreSurfaceFrame.height
            if (mVideoSize.x > 0 && mVideoSize.y > 0 && frameWidth > 0 && frameHeight > 0) {
                applySurfaceBounds(mVideoSize.x, mVideoSize.y, frameWidth, frameHeight)
            }
        }
    }

    /**
     * Keeps the surface letterbox-fitted to the pop-up while it is resized (pinch or
     * double-tap). fitSurfaceToPipWindow() only runs once at enter; without this listener
     * the surface keeps its enter-time size and the video gets cropped once the window
     * shrinks below it. The listener is only attached while in picture-in-picture mode.
     */
    private var mLastPipFrameWidth = 0
    private var mLastPipFrameHeight = 0

    private val mPipResizeListener = object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            if (!isInPictureInPictureMode) {
                return
            }
            val frame = binding.theatreSurfaceFrame
            // Skip when nothing changed to avoid re-layout loops from re-applying bounds.
            if (frame.width == mLastPipFrameWidth && frame.height == mLastPipFrameHeight) {
                return
            }
            if (mVideoSize.x > 0 && mVideoSize.y > 0 && frame.width > 0 && frame.height > 0) {
                applySurfaceBounds(mVideoSize.x, mVideoSize.y, frame.width, frame.height)
                mLastPipFrameWidth = frame.width
                mLastPipFrameHeight = frame.height
            }
        }
    }

    private fun applySurfaceBounds(videoWidth: Int, videoHeight: Int, boxWidth: Int, boxHeight: Int) {
        // Fit the whole video inside the given box, preserving the video's aspect ratio.
        // The surface is centered in the window and letterboxed whenever the box ratio
        // differs from the video ratio.
        val videoProportion = videoWidth.toFloat() / videoHeight.toFloat()
        val boxProportion = boxWidth.toFloat() / boxHeight.toFloat()
        binding.theatreSurface.layoutParams.apply {
            if (videoProportion > boxProportion) {
                width = boxWidth
                height = (boxWidth.toFloat() / videoProportion).toInt()
            } else {
                width = (videoProportion * boxHeight.toFloat()).toInt()
                height = boxHeight
            }
            binding.theatreSurface.layoutParams = this
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mPipActionReceiver)
        binding.theatreSurfaceFrame.viewTreeObserver.removeOnGlobalLayoutListener(mPipResizeListener)
        if (!isChangingConfigurations) {
            pauseVideo()
            binding.theatreBottomBar.theatreCurrTime.text = 0.getFormattedDuration()
            releaseExoPlayer()
            binding.theatreBottomBar.theatreSeekbar.progress = 0
            mTimerHandler.removeCallbacksAndMessages(null)
            mPlayWhenReadyHandler.removeCallbacksAndMessages(null)
            mChromeHandler.removeCallbacksAndMessages(null)
            mSeekFeedbackHandler.removeCallbacksAndMessages(null)
            binding.theatreBrightnessController.cleanup()
            binding.theatreVolumeController.cleanup()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isInPictureInPictureMode) {
            return
        }
        setVideoSize()
        initTimeHolder()
        binding.theatreSurfaceFrame.onGlobalLayout {
            binding.theatreSurfaceFrame.controller.resetState()
        }
    }

    private fun setupOrientation() {
        if (config.screenRotation == ROTATE_BY_DEVICE_ROTATION) {
            requestedOrientation = SCREEN_ORIENTATION_SENSOR
        } else if (config.screenRotation == ROTATE_BY_SYSTEM_SETTING) {
            requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initPlayer() {
        mUri = intent.data ?: return
        binding.theatreTitle.text = getFilenameFromUri(mUri!!)
        mPipResumePositionMs = intent.getLongExtra(PIP_RESUME_POSITION, -1L)
        mPipRestoring = mPipResumePositionMs >= 0
        if (mPipRestoring) {
            mExternalAudioUri = intent.getStringExtra(PIP_RESUME_EXTERNAL_AUDIO)
                ?.takeIf { it.isNotEmpty() }
                ?.let(Uri::parse)
            mExternalSubtitleUri = intent.getStringExtra(PIP_RESUME_EXTERNAL_SUBTITLE)
                ?.takeIf { it.isNotEmpty() }
                ?.let(Uri::parse)
            mExternalAudioUri?.let { mExternalAudioMime = contentResolver.getType(it) ?: "audio/mpeg" }
            mExternalSubtitleUri?.let {
                mExternalSubtitleMime = contentResolver.getType(it) ?: guessSubtitleMime(it)
            }
            mPipResumeAudioGroup = intent.getIntExtra(PIP_RESUME_AUDIO_GROUP, -1)
            mPipResumeAudioTrack = intent.getIntExtra(PIP_RESUME_AUDIO_TRACK, -1)
            mPipResumeTextGroup = intent.getIntExtra(PIP_RESUME_TEXT_GROUP, -1)
            mPipResumeTextTrack = intent.getIntExtra(PIP_RESUME_TEXT_TRACK, -1)
            mUserDisabledTextTrack = intent.getBooleanExtra(PIP_RESUME_TEXT_DISABLED, false)
        }
        initTimeHolder()

        showSystemUI()
        binding.theatreBottomBar.theatrePlayPause.setOnClickListener { togglePlayPause() }
        binding.theatreBottomBar.theatreTracksButton.setOnClickListener { showTrackSelector() }
        binding.theatreBottomBar.theatreRotateButton.setOnClickListener { toggleOrientation() }
        binding.theatreBottomBar.theatreSeekbar.setOnSeekBarChangeListener(this)

        binding.theatreSurfaceFrame.controller.settings.swallowDoubleTaps = true
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleChrome()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                handleDoubleTap(e.rawX)
                return true
            }
        })
        binding.theatreSurfaceFrame.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        // Render cues with an explicit, clean style: white text, transparent background, no
        // edge, and the native text size. We deliberately do NOT inherit the device's
        // captioning style or the subtitle file's embedded styling (colors, opaque box,
        // font size), which would otherwise produce a centered, boxed, mis-sized render.
        binding.theatreSubtitles.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                Color.WHITE,
                null
            )
        )
        binding.theatreSubtitles.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
        binding.theatreSubtitles.setApplyEmbeddedStyles(false)
        binding.theatreSubtitles.beVisible()
        binding.theatreSubtitles.post {
            Log.d(
                TAG,
                "SubtitleView chip top=${binding.theatreSubtitles.top} " +
                    "bottom=${binding.theatreSubtitles.bottom} " +
                    "height=${binding.theatreSubtitles.height} " +
                    "padBottom=${binding.theatreSubtitles.paddingBottom} " +
                    "rootHeight=${binding.theatrePlayerHolder.height}"
            )
        }

        initExoPlayer()
        binding.theatreSurface.surfaceTextureListener = this

        binding.theatreBrightnessController.initialize(
            this,
            binding.theatreBrightnessIndicator,
            true,
            binding.theatrePlayerHolder,
            singleTap = { _, _ -> toggleChrome() },
            doubleTap = { _, _ -> doSkip(false) }
        )
        binding.theatreVolumeController.initialize(
            this,
            binding.theatreVolumeIndicator,
            false,
            binding.theatrePlayerHolder,
            singleTap = { _, _ -> toggleChrome() },
            doubleTap = { _, _ -> doSkip(true) }
        )

        showChrome(scheduleHide = true)
    }

    private fun initExoPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                EXOPLAYER_MIN_BUFFER_MS,
                EXOPLAYER_MAX_BUFFER_MS,
                EXOPLAYER_MIN_BUFFER_MS,
                EXOPLAYER_MIN_BUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        mExoPlayer = ExoPlayer.Builder(this)
            .setSeekParameters(SeekParameters.EXACT)
            .setLoadControl(loadControl)
            .setTrackSelector(
                DefaultTrackSelector(this).apply {
                    parameters = buildUponParameters()
                        .setSelectTextByDefault(true)
                        .build()
                }
            )
            .build()
            .apply {
                setPlaybackSpeed(config.playbackSpeed)
                setMediaSource(buildMediaSource())
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false
                )
                if (config.loopVideos) {
                    repeatMode = Player.REPEAT_MODE_ONE
                }
                prepare()
                initListeners()
            }

        updatePlayerMuteState()
    }

    private fun buildMediaSource(): MediaSource {
        val dataSourceFactory = DataSource.Factory { ContentDataSource(applicationContext) }
        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(mUri!!))

        val extras = mutableListOf<MediaSource>()
        mExternalAudioUri?.let { uri ->
            extras.add(
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            )
        }
        mExternalSubtitleUri?.let { uri -> extras.add(buildExternalSubtitleSource(dataSourceFactory, uri)) }

        return if (extras.isEmpty()) {
            videoSource
        } else {
            MergingMediaSource(true, videoSource, *extras.toTypedArray())
        }
    }

    /**
     * Builds the media source for an external subtitle file.
     *
     * media3 1.9 parses subtitles during extraction (the default and only non-deprecated path), so
     * the file is wrapped in a [SubtitleExtractor] which transcodes it to timed cues
     * (application/x-media3-cues) as it is read. This mirrors what DefaultMediaSourceFactory does
     * for MediaItem subtitle configurations. Constructing the deprecated SingleSampleMediaSource
     * here would instead require legacy subtitle decoding, which is disabled by default and fails
     * playback with an "Unexpected runtime error".
     */
    private fun buildExternalSubtitleSource(dataSourceFactory: DataSource.Factory, uri: Uri): MediaSource {
        val subtitleParserFactory = DefaultSubtitleParserFactory()
        val mimeType = mExternalSubtitleMime
            ?.takeIf { mime -> subtitleParserFactory.supportsFormat(Format.Builder().setSampleMimeType(mime).build()) }
            ?: guessSubtitleMime(uri)
        val format = Format.Builder()
            .setSampleMimeType(mimeType)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_AUTOSELECT)
            .setLabel(getFilenameFromUri(uri))
            .build()
        val extractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(SubtitleExtractor(subtitleParserFactory.create(format), format))
        }
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(MediaItem.fromUri(uri))
    }

    private fun ExoPlayer.initListeners() {
        addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                @Player.DiscontinuityReason reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    binding.theatreBottomBar.theatreSeekbar.progress = 0
                    binding.theatreBottomBar.theatreCurrTime.text = 0.getFormattedDuration()
                }
            }

            override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> videoPrepared()
                    Player.STATE_ENDED -> videoCompleted()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                mVideoSize.x = videoSize.width
                mVideoSize.y = videoSize.height
                if (isInPictureInPictureMode) {
                    updatePipActions()
                } else {
                    setVideoSize()
                }
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                binding.errorMessageHolder.errorMessage.apply {
                    if (error != null) {
                        text = error.getFriendlyMessage(context)
                        setTextColor(
                            if (context.config.blackBackground) {
                                Color.WHITE
                            } else {
                                context.getProperTextColor()
                            }
                        )
                        fadeIn()
                    } else {
                        beGone()
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                super.onTracksChanged(tracks)
                mHasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO)
                updatePlayerMuteState()
                val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                Log.d(
                    TAG,
                    "onTracksChanged groups=${tracks.groups.size} " +
                        "hasText=${tracks.containsType(C.TRACK_TYPE_TEXT)} " +
                        "textSelected=${tracks.isTypeSelected(C.TRACK_TYPE_TEXT)}"
                )
                textGroups.forEachIndexed { gi, g ->
                    for (ti in 0 until g.length) {
                        val f = g.getTrackFormat(ti)
                        Log.d(
                            TAG,
                            "  textGroup[$gi] track[$ti] mime=${f.sampleMimeType} " +
                                "codecs=${f.codecs} lang=${f.language} " +
                                "flags=${f.selectionFlags} sel=${g.isTrackSelected(ti)}"
                        )
                    }
                }
                if (mPendingSelectExternalAudio && mExternalAudioUri != null) {
                    mPendingSelectExternalAudio = false
                    selectOnlyExternalAudio(tracks)
                }
                restorePipTracks(tracks)
                ensureTextTrackSelected(tracks)
            }

            override fun onCues(cueGroup: CueGroup) {
                val cues = cueGroup.cues
                Log.d(
                    TAG,
                    "onCues count=${cues.size} firstLine=${cues.firstOrNull()?.line} " +
                        "firstLineType=${cues.firstOrNull()?.lineType} " +
                        "firstPos=${cues.firstOrNull()?.position}"
                )
                val normalized = normalizeCuePositions(cues)
                normalized.firstOrNull()?.let { c ->
                    Log.d(
                        TAG,
                        "normalized line=${c.line} lineType=${c.lineType} lineAnchor=${c.lineAnchor} " +
                            "pos=${c.position} posAnchor=${c.positionAnchor}"
                    )
                }
                binding.theatreSubtitles.setCues(normalized)
            }
        })
    }

    /**
     * Rewrites text cues so they render at the viewer's native bottom placement.
     *
     * Subtitle files (especially SSA/ASS) can carry an explicit mid-screen or top alignment that
     * media3 honors faithfully, which places cues at the vertical center of the screen instead of
     * along the bottom edge like other players. We clear any explicit [Cue.line]/[Cue.position]
     * anchoring from text cues so [androidx.media3.ui.SubtitleView] falls back to its default
     * bottom placement. Bitmap cues (e.g. PGS) and vertical-progression information are preserved.
     */
    /**
     * Rewrites cues so they render at the app's native bottom placement (matching right.png).
     *
     * The SubtitleView is a full-screen layer with no bar padding, so the painter's default for an
     * unset line is exactly the native bottom position (~92% down: bottom minus the 8% bottom
     * padding fraction). Text cues therefore just get any authoring-defined line/position cleared
     * (e.g. SSA/ASS {n8} top alignment or {n5} mid alignment that would otherwise draw at the
     * top or center). Bitmap cues (PGS/VobSub) need explicit anchors because an unset line would be
     * interpreted as a negative coordinate, so those are pinned bottom-center.
     */
    private fun normalizeCuePositions(cues: List<Cue>): List<Cue> {
        if (cues.isEmpty()) {
            return cues
        }
        return cues.map { cue ->
            val builder = cue.buildUpon()
            if (cue.text != null) {
                builder
                    .setPosition(Cue.DIMEN_UNSET)
                    .setPositionAnchor(Cue.TYPE_UNSET)
                    .setLine(Cue.DIMEN_UNSET, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.TYPE_UNSET)
            } else {
                builder
                    .setPosition(0.5f)
                    .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE)
                    .setLine(1.0f, Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_END)
            }
            builder.build()
        }
    }

    private fun selectOnlyExternalAudio(tracks: Tracks) {
        val player = mExoPlayer ?: return
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        if (audioGroups.size <= 1) {
            return
        }
        val externalGroup = audioGroups.last()
        val params = player.trackSelectionParameters.buildUpon()
        params.setOverrideForType(TrackSelectionOverride(externalGroup.mediaTrackGroup, 0))
        player.trackSelectionParameters = params.build()
    }

    private fun ensureTextTrackSelected(tracks: Tracks) {
        if (mPipRestoring && (mPipResumeTextGroup >= 0 || mExternalSubtitleUri != null)) {
            // The persisted text selection is applied by restorePipTracks(); do not
            // auto-pick a default subtitle over it. When no text selection was saved
            // (e.g. reopened from the gesture player), fall through to the default pick.
            return
        }
        if (mUserDisabledTextTrack) {
            return
        }
        if (!tracks.containsType(C.TRACK_TYPE_TEXT)) {
            return
        }
        if (tracks.isTypeSelected(C.TRACK_TYPE_TEXT)) {
            return
        }

        // Prefer a default-flagged subtitle; otherwise the first text track, so cues
        // render without requiring the user to open the track selector.
        val player = mExoPlayer ?: return
        tracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT && group.length > 0) {
                var trackIndex = 0
                for (index in 0 until group.length) {
                    val flags = group.getTrackFormat(index).selectionFlags
                    if (flags and C.SELECTION_FLAG_DEFAULT != 0) {
                        trackIndex = index
                        break
                    }
                }
                val params = player.trackSelectionParameters.buildUpon()
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                player.trackSelectionParameters = params.build()
                return
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun videoPrepared() {
        if (!mWasVideoStarted) {
            mDuration = mExoPlayer!!.duration
            binding.theatreBottomBar.theatreSeekbar.max = mDuration.toInt()
            binding.theatreBottomBar.theatreDuration.text = mDuration.getFormattedDuration()
            setPosition(mCurrTime)

            val restoredFromPip = mPipResumePositionMs >= 0
            if (restoredFromPip) {
                // Reopened right after dismissing the PiP pop-up: land paused at the exact
                // position where it was closed. Mark playback as started so the buffering
                // this seek triggers (STATE_BUFFERING -> STATE_READY) does not re-enter this
                // block and clobber the restored position back to 0.
                setPosition(mPipResumePositionMs.coerceIn(0L, mDuration))
                mWasVideoStarted = true
                mIsPlaying = false
                mExoPlayer?.playWhenReady = false
                binding.theatreBottomBar.theatrePlayPause.setImageResource(R.drawable.ic_play_outline_vector)
                mPipResumePositionMs = -1L
                config.clearPendingPipResume()
            } else {
                if (config.rememberLastVideoPosition) {
                    setLastVideoSavedPosition()
                }
                if (config.autoplayVideos) {
                    resumeVideo()
                } else {
                    binding.theatreBottomBar.theatrePlayPause.setImageResource(R.drawable.ic_play_outline_vector)
                }
            }
        }

        // Re-assert the text track once the player is ready and push any cues that are
        // already active, in case the override selected while preparing did not stick.
        mExoPlayer?.let { player ->
            ensureTextTrackSelected(player.currentTracks)
            binding.theatreSubtitles.setCues(normalizeCuePositions(player.currentCues.cues))
        }
    }

    /**
     * Re-applies the audio/subtitle track selection stored when the pop-up was dismissed.
     * Runs once the first time tracks are reported for the restored player, before the
     * default ensureTextTrackSelected() logic would re-select the default text track.
     */
    private fun restorePipTracks(tracks: Tracks) {
        if (!mPipRestoring || mPipTrackRestored) {
            return
        }
        val player = mExoPlayer ?: return
        val groups = tracks.groups
        if (groups.isEmpty()) {
            // media3 may report an empty track list during prepare(); wait for the real set.
            return
        }
        val params = player.trackSelectionParameters.buildUpon()

        // The saved group/track indices were captured from the previous instance whose source
        // was rebuilt identically (buildMediaSource() merges external audio/subtitles in the
        // same order), so re-selecting them restores external tracks exactly like internal ones.
        if (mPipResumeAudioGroup in groups.indices) {
            val group = groups[mPipResumeAudioGroup]
            if (group.type == C.TRACK_TYPE_AUDIO && mPipResumeAudioTrack in 0 until group.length) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, mPipResumeAudioTrack))
            }
        }

        if (mUserDisabledTextTrack) {
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else if (mPipResumeTextGroup in groups.indices) {
            val group = groups[mPipResumeTextGroup]
            if (group.type == C.TRACK_TYPE_TEXT && mPipResumeTextTrack in 0 until group.length) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, mPipResumeTextTrack))
            } else if (groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                // Persisted selection no longer maps; fall back to the default subtitle.
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            }
        }

        player.trackSelectionParameters = params.build()
        mPipTrackRestored = true
    }

    private fun handleDoubleTap(x: Float) {
        val realMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        val screenWidth = realMetrics.widthPixels
        val third = screenWidth / 3f
        when {
            x <= third -> doSkip(false)
            x >= screenWidth - third -> doSkip(true)
            else -> togglePlayPause()
        }
    }

    private fun resumeVideo() {
        binding.theatreBottomBar.theatrePlayPause.setImageResource(R.drawable.ic_pause_vector)
        val player = mExoPlayer ?: return

        if (didVideoEnd()) {
            setPosition(0)
        }

        mWasVideoStarted = true
        mIsPlaying = true
        player.playWhenReady = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun pauseVideo() {
        binding.theatreBottomBar.theatrePlayPause.setImageResource(R.drawable.ic_play_outline_vector)
        val player = mExoPlayer ?: return

        mIsPlaying = false
        if (!didVideoEnd()) {
            player.playWhenReady = false
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun togglePlayPause() {
        mIsPlaying = !mIsPlaying
        if (mIsPlaying) {
            resumeVideo()
        } else {
            pauseVideo()
        }
        showChrome(scheduleHide = true)
    }

    private fun togglePipPlayPause() {
        // Same as togglePlayPause() but without showChrome(), which would re-show the
        // fullscreen bars inside the small picture-in-picture window.
        mIsPlaying = !mIsPlaying
        if (mIsPlaying) {
            resumeVideo()
        } else {
            pauseVideo()
        }
    }

    private fun updatePlayerMuteState() {
        val player = mExoPlayer ?: return
        if (mHasAudio) {
            if (config.muteVideos) player.mute() else player.unmute()
        }
    }

    private fun setPosition(milliseconds: Long) {
        mExoPlayer?.seekTo(milliseconds)
        binding.theatreBottomBar.theatreSeekbar.progress = milliseconds.toInt()
        binding.theatreBottomBar.theatreCurrTime.text = milliseconds.getFormattedDuration()
    }

    private fun setLastVideoSavedPosition() {
        val seconds = config.getLastVideoPosition(mUri.toString())
        if (seconds > 0) {
            setPosition(seconds * 1000L)
        }
    }

    private fun videoCompleted() {
        val player = mExoPlayer ?: return
        clearLastVideoSavedProgress()
        mCurrTime = player.duration
        binding.theatreBottomBar.theatreSeekbar.progress = binding.theatreBottomBar.theatreSeekbar.max
        binding.theatreBottomBar.theatreCurrTime.text = mDuration.getFormattedDuration()
        pauseVideo()
    }

    private fun didVideoEnd(): Boolean {
        val currentPos = mExoPlayer?.currentPosition ?: 0
        val duration = mExoPlayer?.duration ?: 0
        return currentPos != 0L && currentPos >= duration
    }

    private fun saveVideoProgress() {
        if (!didVideoEnd()) {
            config.saveLastVideoPosition(
                mUri.toString(),
                mExoPlayer!!.currentPosition.toInt() / 1000
            )
        }
    }

    private fun clearLastVideoSavedProgress() {
        config.removeLastVideoPosition(mUri.toString())
    }

    private fun setVideoSize() {
        if (mVideoSize.x == 0 || mVideoSize.y == 0) {
            return
        }
        val videoProportion = mVideoSize.x.toFloat() / mVideoSize.y.toFloat()
        val display = windowManager.defaultDisplay
        val realMetrics = DisplayMetrics()
        display.getRealMetrics(realMetrics)
        val screenWidth = realMetrics.widthPixels
        val screenHeight = realMetrics.heightPixels
        val screenProportion = screenWidth.toFloat() / screenHeight.toFloat()

        binding.theatreSurface.layoutParams.apply {
            if (videoProportion > screenProportion) {
                width = screenWidth
                height = (screenWidth.toFloat() / videoProportion).toInt()
            } else {
                width = (videoProportion * screenHeight.toFloat()).toInt()
                height = screenHeight
            }
            binding.theatreSurface.layoutParams = this
        }

        if (config.screenRotation == ROTATE_BY_ASPECT_RATIO) {
            if (mVideoSize.x > mVideoSize.y) {
                requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE
            } else if (mVideoSize.x < mVideoSize.y) {
                requestedOrientation = SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            SCREEN_ORIENTATION_LANDSCAPE
        } else {
            SCREEN_ORIENTATION_PORTRAIT
        }
        showChrome(scheduleHide = true)
    }

    private fun doSkip(forward: Boolean) {
        val player = mExoPlayer ?: return
        val curr = player.currentPosition
        var newPosition = if (forward) curr + FAST_FORWARD_VIDEO_MS else curr - FAST_FORWARD_VIDEO_MS
        newPosition = newPosition.coerceIn(0, player.duration.coerceAtLeast(0))
        setPosition(newPosition)
        showSeekFeedback(forward, newPosition)
    }

    private fun pipSeek(forward: Boolean) {
        // Seek from the picture-in-picture action; do not show the fullscreen seek
        // feedback pill, which would leak into the small pop-up window.
        val player = mExoPlayer ?: return
        val curr = player.currentPosition
        var newPosition = if (forward) curr + FAST_FORWARD_VIDEO_MS else curr - FAST_FORWARD_VIDEO_MS
        newPosition = newPosition.coerceIn(0, player.duration.coerceAtLeast(0))
        setPosition(newPosition)
    }

    private fun showSeekFeedback(forward: Boolean, targetMs: Long) {
        val seconds = FAST_FORWARD_VIDEO_MS / 1000
        val label = if (forward) "${seconds}s" else "-${seconds}s"
        val text = getString(R.string.theatre_seek_feedback, label, targetMs.getFormattedDuration())

        val container = if (forward) binding.theatreSeekFeedbackForward else binding.theatreSeekFeedbackBackward
        val other = if (forward) binding.theatreSeekFeedbackBackward else binding.theatreSeekFeedbackForward
        val textView = if (forward) {
            binding.theatreSeekFeedbackForwardText
        } else {
            binding.theatreSeekFeedbackBackwardText
        }

        other.animate().alpha(0f).cancel()
        other.beGone()
        textView.text = text
        container.beVisible()
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(CHROME_FADE_DURATION.toLong()).start()

        mSeekFeedbackHandler.removeCallbacksAndMessages(null)
        mSeekFeedbackHandler.postDelayed({
            container.animate().alpha(0f).setDuration(CHROME_FADE_DURATION.toLong())
                .withEndAction { container.beGone() }
                .start()
        }, SEEK_FEEDBACK_DURATION)
    }

    private fun showChrome(scheduleHide: Boolean) {
        mChromeHandler.removeCallbacks(mHideChromeRunnable)
        mChromeVisible = true
        showSystemUI()
        setChromeInteractive(true)
        arrayOf(
            binding.theatreAppbar,
            binding.theatreBottomBar.root
        ).forEach {
            it.beVisible()
            it.animate().alpha(1f).setDuration(CHROME_FADE_DURATION.toLong()).start()
        }
        if (scheduleHide && !mIsDragged) {
            mChromeHandler.postDelayed(mHideChromeRunnable, CHROME_HIDE_DELAY)
        }
    }

    private fun hideChrome() {
        if (mIsDragged) {
            return
        }
        mChromeVisible = false
        hideSystemUI()
        setChromeInteractive(false)
        arrayOf(
            binding.theatreAppbar,
            binding.theatreBottomBar.root
        ).forEach {
            it.animate().alpha(0f).setDuration(CHROME_FADE_DURATION.toLong()).start()
        }
    }

    private fun setChromeInteractive(interactive: Boolean) {
        binding.theatreBottomBar.theatrePlayPause.isClickable = interactive
        binding.theatreBottomBar.theatreTracksButton.isClickable = interactive
        binding.theatreBottomBar.theatreRotateButton.isClickable = interactive
        binding.theatreBottomBar.theatreSeekbar.isEnabled = interactive
    }

    private fun toggleChrome() {
        if (mChromeVisible) {
            mChromeHandler.removeCallbacks(mHideChromeRunnable)
            hideChrome()
        } else {
            showChrome(scheduleHide = true)
        }
    }

    private fun showTrackSelector() {
        val fragment = TheatreTrackSelectorFragment()
        fragment.setListener(this)
        fragment.show(supportFragmentManager, TheatreTrackSelectorFragment::class.java.simpleName)
        mChromeHandler.removeCallbacks(mHideChromeRunnable)
    }

    private fun initTimeHolder() {
        binding.theatreBottomBar.theatreSeekbar.max = mDuration.toInt()
        binding.theatreBottomBar.theatreDuration.text = mDuration.getFormattedDuration()
        binding.theatreBottomBar.theatreCurrTime.text = mCurrTime.getFormattedDuration()
        binding.theatreBottomBar.theatreSeekbar.setColors(
            getProperPrimaryColor(),
            getProperPrimaryColor(),
            getProperPrimaryColor()
        )
        applyProperHorizontalInsets(binding.theatreBottomBar.root)
        setupTimer()
    }

    private fun setupTimer() {
        mTimerHandler.removeCallbacksAndMessages(null)
        mTimerHandler.post(object : Runnable {
            override fun run() {
                val player = mExoPlayer
                if (player != null && !mIsDragged && mIsPlaying) {
                    mCurrTime = player.currentPosition
                    binding.theatreBottomBar.theatreSeekbar.progress = mCurrTime.toInt()
                    binding.theatreBottomBar.theatreCurrTime.text = mCurrTime.getFormattedDuration()
                }
                mTimerHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    private fun resetPlayWhenReady() {
        mExoPlayer?.playWhenReady = false
        mPlayWhenReadyHandler.removeCallbacksAndMessages(null)
        mPlayWhenReadyHandler.postDelayed({
            if (mIsPlaying) {
                mExoPlayer?.playWhenReady = true
            }
        }, PLAY_WHEN_READY_DRAG_DELAY)
    }

    private fun releaseExoPlayer() {
        mExoPlayer?.apply {
            stop()
            release()
        }
        mExoPlayer = null
    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (mExoPlayer != null && fromUser) {
            setPosition(progress.toLong())
            binding.theatrePreciseSeek.text = progress.toLong().getFormattedDuration()
            resetPlayWhenReady()
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {
        mIsDragged = true
        mChromeHandler.removeCallbacks(mHideChromeRunnable)
        binding.theatrePreciseSeek.beVisible()
        binding.theatrePreciseSeek.alpha = 1f
        binding.theatrePreciseSeek.text =
            (binding.theatreBottomBar.theatreSeekbar.progress).toLong().getFormattedDuration()
    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        binding.theatrePreciseSeek.beGone()
        mIsDragged = false

        if (mExoPlayer == null) {
            return
        }

        if (mIsPlaying) {
            mExoPlayer!!.playWhenReady = true
        } else {
            mPlayWhenReadyHandler.removeCallbacksAndMessages(null)
        }
        showChrome(scheduleHide = true)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = false

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        mExoPlayer?.setVideoSurface(Surface(binding.theatreSurface.surfaceTexture))
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK || resultData?.data == null) {
            return
        }
        when (requestCode) {
            REQUEST_EXTERNAL_AUDIO -> {
                mExternalAudioUri = resultData.data
                mExternalAudioMime = contentResolver.getType(mExternalAudioUri!!)
                    ?: "audio/mpeg"
                mPendingSelectExternalAudio = true
                rebuildWithExternalTracks()
            }
            REQUEST_EXTERNAL_SUBTITLE -> {
                mExternalSubtitleUri = resultData.data
                mExternalSubtitleMime = contentResolver.getType(mExternalSubtitleUri!!)
                    ?: guessSubtitleMime(mExternalSubtitleUri!!)
                mUserDisabledTextTrack = false
                mExoPlayer?.let { player ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .build()
                }
                rebuildWithExternalTracks()
            }
        }
    }

    private fun guessSubtitleMime(uri: Uri): String {
        val name = getFilenameFromUri(uri).lowercase()
        return when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ssa") || name.endsWith(".ass") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun rebuildWithExternalTracks() {
        val player = mExoPlayer ?: return
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady
        player.stop()
        player.setMediaSource(buildMediaSource())
        player.prepare()
        if (position > 0) {
            player.seekTo(position)
        }
        player.playWhenReady = playWhenReady || mIsPlaying
        showChrome(scheduleHide = true)
    }

    private fun pickExternalAudio() {
        mUserTriggeredExternalNav = true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "audio/mpeg",
                    "audio/mp4",
                    "audio/aac",
                    "audio/flac",
                    "audio/wav",
                    "audio/x-wav",
                    "audio/ogg",
                    "audio/*"
                )
            )
        }
        startActivityForResult(intent, REQUEST_EXTERNAL_AUDIO)
    }

    private fun pickExternalSubtitle() {
        mUserTriggeredExternalNav = true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/x-subrip",
                    "text/vtt",
                    "text/x-ssa",
                    "text/x-ass",
                    "text/plain"
                )
            )
        }
        startActivityForResult(intent, REQUEST_EXTERNAL_SUBTITLE)
    }

    private fun trackOptions(trackType: Int): List<TheatreTrackOption> {
        val player = mExoPlayer ?: return emptyList()
        val options = mutableListOf<TheatreTrackOption>()
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == trackType && group.length > 0) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    options.add(
                        TheatreTrackOption(
                            label = trackLabel(format, trackIndex),
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            isSelected = group.isTrackSelected(trackIndex)
                        )
                    )
                }
            }
        }
        return options
    }

    private fun trackLabel(format: Format, trackIndex: Int): String {
        return format.label ?: format.language ?: format.id ?: "${trackIndex + 1}"
    }

    private fun applyTrackOverride(groupIndex: Int, trackIndex: Int, trackType: Int) {
        val player = mExoPlayer ?: return
        val groups = player.currentTracks.groups
        if (groupIndex !in groups.indices) {
            return
        }
        val group = groups[groupIndex]
        if (group.type != trackType) {
            return
        }
        val params = player.trackSelectionParameters.buildUpon()
        params.setTrackTypeDisabled(trackType, false)
        params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        player.trackSelectionParameters = params.build()
    }

    private fun clearTrackOverrides(trackType: Int) {
        val player = mExoPlayer ?: return
        val params = player.trackSelectionParameters.buildUpon()
        params.clearOverridesOfType(trackType)
        player.trackSelectionParameters = params.build()
    }

    override fun getAudioTracks(): List<TheatreTrackOption> = trackOptions(C.TRACK_TYPE_AUDIO)

    override fun getTextTracks(): List<TheatreTrackOption> = trackOptions(C.TRACK_TYPE_TEXT)

    override fun onAudioTrackSelected(groupIndex: Int, trackIndex: Int) {
        applyTrackOverride(groupIndex, trackIndex, C.TRACK_TYPE_AUDIO)
    }

    override fun onTextTrackSelected(groupIndex: Int, trackIndex: Int) {
        mUserDisabledTextTrack = false
        applyTrackOverride(groupIndex, trackIndex, C.TRACK_TYPE_TEXT)
    }

    override fun onAudioTrackDisabled() {
        clearTrackOverrides(C.TRACK_TYPE_AUDIO)
    }

    override fun onTextTrackDisabled() {
        mUserDisabledTextTrack = true
        val player = mExoPlayer ?: return
        val params = player.trackSelectionParameters.buildUpon()
        params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = params.build()
    }

    override fun onAddExternalAudioTrack() {
        pickExternalAudio()
    }

    override fun onAddExternalSubtitleTrack() {
        pickExternalSubtitle()
    }
}
