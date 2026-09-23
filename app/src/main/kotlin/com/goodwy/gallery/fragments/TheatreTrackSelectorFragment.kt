package com.goodwy.gallery.fragments

import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.goodwy.commons.extensions.*
import com.goodwy.commons.views.MyTextView
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.DialogTheatreTrackSelectorBinding

data class TheatreTrackOption(
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean
)

interface TheatreTrackSelectionListener {
    fun getAudioTracks(): List<TheatreTrackOption>
    fun getTextTracks(): List<TheatreTrackOption>
    fun onAudioTrackSelected(groupIndex: Int, trackIndex: Int)
    fun onTextTrackSelected(groupIndex: Int, trackIndex: Int)
    fun onAudioTrackDisabled()
    fun onTextTrackDisabled()
    fun onAddExternalAudioTrack()
    fun onAddExternalSubtitleTrack()
}

class TheatreTrackSelectorFragment : BottomSheetDialogFragment() {
    private var listener: TheatreTrackSelectionListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomBottomSheetDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = DialogTheatreTrackSelectorBinding.inflate(inflater, container, false)
        val background = ResourcesCompat.getDrawable(
            resources,
            com.goodwy.commons.R.drawable.bottom_sheet_bg,
            requireContext().theme
        )
        (background as LayerDrawable).findDrawableByLayerId(com.goodwy.commons.R.id.bottom_sheet_background)
            .applyColorFilter(requireContext().getProperBackgroundColor())

        binding.apply {
            root.setBackgroundDrawable(background)
            requireContext().updateTextColors(theatreTrackSelectorHolder)

            theatreAudioDisable.alpha = 0.5f
            theatreSubtitlesDisable.alpha = 0.5f

            populateTracks(
                theatreAudioTracksHolder,
                listener?.getAudioTracks() ?: emptyList()
            ) { option ->
                listener?.onAudioTrackSelected(option.groupIndex, option.trackIndex)
                dismiss()
            }
            populateTracks(
                theatreSubtitlesTracksHolder,
                listener?.getTextTracks() ?: emptyList()
            ) { option ->
                listener?.onTextTrackSelected(option.groupIndex, option.trackIndex)
                dismiss()
            }

            theatreAudioDisable.setOnClickListener {
                listener?.onAudioTrackDisabled()
                dismiss()
            }
            theatreSubtitlesDisable.setOnClickListener {
                listener?.onTextTrackDisabled()
                dismiss()
            }
            theatreAddExternalAudio.setOnClickListener {
                listener?.onAddExternalAudioTrack()
                dismiss()
            }
            theatreAddExternalSubtitle.setOnClickListener {
                listener?.onAddExternalSubtitleTrack()
                dismiss()
            }

            theatreAudioHeaderArrow.applyColorFilter(requireContext().getProperTextColor())
            theatreSubtitlesHeaderArrow.applyColorFilter(requireContext().getProperTextColor())
        }

        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
        return binding.root
    }

    private fun populateTracks(
        holder: LinearLayout,
        tracks: List<TheatreTrackOption>,
        onClick: (TheatreTrackOption) -> Unit
    ) {
        holder.removeAllViews()
        val context = requireContext()
        val mediumMargin = context.resources.getDimensionPixelSize(
            com.goodwy.commons.R.dimen.medium_margin
        )
        val labelSize = context.resources.getDimension(
            com.goodwy.commons.R.dimen.medium_text_size
        )

        tracks.forEach { option ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, mediumMargin, 0, mediumMargin)
            }

            val check = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = mediumMargin
                }
                visibility = if (option.isSelected) View.VISIBLE else View.INVISIBLE
                setImageResource(R.drawable.ic_check_outline)
                applyColorFilter(context.getProperPrimaryColor())
                contentDescription = null
            }
            row.addView(check)

            val label = MyTextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                text = option.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSize)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(label)

            row.setOnClickListener { onClick(option) }
            holder.addView(row)
        }
    }

    fun setListener(trackSelectionListener: TheatreTrackSelectionListener) {
        listener = trackSelectionListener
    }
}
