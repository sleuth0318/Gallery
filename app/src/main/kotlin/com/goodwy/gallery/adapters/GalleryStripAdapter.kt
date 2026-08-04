package com.goodwy.gallery.adapters

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.extensions.*
import com.goodwy.gallery.R
import com.goodwy.gallery.databinding.ItemGalleryStripBinding
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.loadImage
import com.goodwy.gallery.helpers.ROUNDED_CORNERS_SMALL
import com.goodwy.gallery.models.Medium

class GalleryStripAdapter(
    private val activity: com.goodwy.gallery.activities.ViewPagerActivity,
    val media: ArrayList<Medium>,
    private val itemClick: (Medium) -> Unit
) : RecyclerView.Adapter<GalleryStripAdapter.ViewHolder>() {

    private val config = activity.config
    private val hasOTGConnected = activity.hasOTGConnected()
    private val animateGifs = config.animateGifs

    inner class ViewHolder(val binding: ItemGalleryStripBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    itemClick(media[position])
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryStripBinding.inflate(activity.layoutInflater, parent, false)
        return ViewHolder(binding)
    }

    override fun getItemCount() = media.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val medium = media.getOrNull(position) ?: return
        holder.binding.apply {
            var path = medium.path
            if (hasOTGConnected && activity.isPathOnOTG(path)) {
                path = path.getOTGPublicPath(activity)
            }

            galleryStripThumbnail.setBackgroundResource(R.drawable.placeholder_rounded_small)

            activity.loadImage(
                type = medium.type,
                path = path,
                target = galleryStripThumbnail,
                horizontalScroll = false,
                animateGifs = animateGifs,
                cropThumbnails = true,
                roundCorners = ROUNDED_CORNERS_SMALL,
                signature = medium.getKey()
            )

            if (medium.isVideo() || medium.isPortrait()) {
                galleryStripPlay.beVisible()
                galleryStripPlay.setImageResource(
                    if (medium.isVideo()) R.drawable.ic_play_vector else R.drawable.ic_portrait_photo_vector
                )
            } else {
                galleryStripPlay.beGone()
            }
        }
    }
}
