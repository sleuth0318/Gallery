/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Small RecyclerView adapters powering the video editor's bottom tool row and
 * the sub-panel pickers. Colors are resolved by the activity (app theme),
 * never hardcoded. GPLv3, see LICENSE.
 */
package com.goodwy.gallery.videoeditor.panels

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.goodwy.commons.views.MyTextView
import com.goodwy.gallery.R

/** One entry of the scrollable bottom tool row (icon + label). */
data class VeTool(
    val id: String,
    val labelRes: Int,
    val iconRes: Int,
)

class VeToolAdapter(
    private val tools: List<VeTool>,
    private val activeColor: () -> Int,
    private val idleColor: () -> Int,
    private val onClick: (VeTool) -> Unit,
) : RecyclerView.Adapter<VeToolAdapter.VH>() {

    var activeToolId: String? = null
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_ve_tool, parent, false)
    ) {
        val icon: ImageView = itemView.findViewById(R.id.ve_tool_icon)
        val label: TextView = itemView.findViewById(R.id.ve_tool_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount() = tools.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tool = tools[position]
        holder.icon.setImageResource(tool.iconRes)
        holder.label.setText(tool.labelRes)
        val active = tool.id == activeToolId
        val color = if (active) activeColor() else idleColor()
        holder.icon.setColorFilter(color)
        holder.label.setTextColor(color)
        holder.itemView.setOnClickListener { onClick(tool) }
    }
}

/** One entry of the Adjust tool's chips row (icon + label + key). */
data class VeAdjustItem(
    val key: String,
    val labelRes: Int,
    val iconRes: Int,
)

/**
 * Icon+label chips for the Adjust tool. The selected slot and every slot that
 * differs from its default are tinted with the active color.
 */
class VeAdjustAdapter(
    private val items: List<VeAdjustItem>,
    private val activeColor: () -> Int,
    private val idleColor: () -> Int,
    private val onClick: (VeAdjustItem) -> Unit,
) : RecyclerView.Adapter<VeAdjustAdapter.VH>() {

    var selectedKey: String? = null
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var modifiedKeys: Set<String> = emptySet()
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_ve_adjust, parent, false)
    ) {
        val icon: ImageView = itemView.findViewById(R.id.ve_adjust_icon)
        val label: TextView = itemView.findViewById(R.id.ve_adjust_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.iconRes)
        holder.label.setText(item.labelRes)
        val emphasized = item.key == selectedKey || modifiedKeys.contains(item.key)
        val color = if (emphasized) activeColor() else idleColor()
        holder.icon.setColorFilter(color)
        holder.label.setTextColor(color)
        holder.itemView.setOnClickListener { onClick(item) }
    }
}

/** Thumbnail + label row entry (filters & overlay presets). */
data class VeThumbEntry(
    val label: String,
    var cached: Bitmap? = null,
    val provider: () -> Bitmap?,
)

class VeThumbAdapter(
    private val entries: List<VeThumbEntry>,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<VeThumbAdapter.VH>() {

    var selectedIndex = 0
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_ve_thumb_label, parent, false)
    ) {
        val thumb: ImageView = itemView.findViewById(R.id.ve_thumb)
        val label: TextView = itemView.findViewById(R.id.ve_thumb_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount() = entries.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = entries[position]
        holder.label.text = entry.label
        if (entry.cached == null) {
            entry.cached = try {
                entry.provider()
            } catch (e: Exception) {
                null
            }
        }
        entry.cached?.let { holder.thumb.setImageBitmap(it) }
        holder.thumb.alpha = if (position == selectedIndex) 1f else 0.7f
        holder.itemView.setOnClickListener { onClick(position) }
    }
}

/** Simple horizontal chip row (aspect ratios, fonts). */
class VeChipAdapter(
    private var labels: List<String>,
    private val typefaceFor: ((Int) -> Typeface?)? = null,
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<VeChipAdapter.VH>() {

    var selectedIndex = -1
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private var selectedColor = 0xFF66A3FF.toInt()
    private var idleColor = 0xFFFFFFFF.toInt()

    fun setColors(selected: Int, idle: Int) {
        selectedColor = selected
        idleColor = idle
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateLabels(newLabels: List<String>) {
        labels = newLabels
        notifyDataSetChanged()
    }

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_ve_chip, parent, false)
    ) {
        val chip: MyTextView = itemView.findViewById(R.id.ve_chip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount() = labels.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.chip.text = labels[position]
        holder.chip.setTextColor(if (position == selectedIndex) selectedColor else idleColor)
        typefaceFor?.invoke(position)?.let { holder.chip.typeface = it }
        holder.chip.setOnClickListener { onClick(position) }
    }
}

/** Emoji grid within the sticker panel. */
class VeEmojiAdapter(
    private val emojis: List<String>,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<VeEmojiAdapter.VH>() {

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_ve_emoji, parent, false)
    ) {
        val emoji: TextView = itemView.findViewById(R.id.ve_emoji)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount() = emojis.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = emojis[position]
        holder.emoji.text = e
        holder.emoji.setOnClickListener { onClick(e) }
    }
}

/** Shape grid within the sticker panel (tinted by the panel's current color). */
class VeShapeAdapter(
    private val shapes: List<String>,
    private val bitmapFor: (String) -> Bitmap,
    private val onClick: (String) -> Unit,
) : RecyclerView.Adapter<VeShapeAdapter.VH>() {

    class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_ve_shape, parent, false)
    ) {
        val shape: ImageView = itemView.findViewById(R.id.ve_shape)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)

    override fun getItemCount() = shapes.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val id = shapes[position]
        holder.shape.setImageBitmap(try {
            bitmapFor(id)
        } catch (e: Exception) {
            null
        })
        holder.shape.setOnClickListener { onClick(id) }
    }
}
