/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * In-app editor — gallery-image sticker picker dialog (foss flavor).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 */
package com.goodwy.gallery.videoeditor.panels

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.helpers.NOMEDIA
import com.goodwy.gallery.R
import com.goodwy.gallery.extensions.config
import com.goodwy.gallery.extensions.getNoMediaFoldersSync
import com.goodwy.gallery.extensions.shouldFolderBeVisible
import java.util.concurrent.Executors

/**
 * Sticker tool → Gallery feature: an in-app image picker styled after the
 * app's folder dialog. Folders are the MediaStore image buckets (images
 * only); tapping one opens its images, tapping an image marks it (single
 * selection) and OK confirms the pick. Search filters folder names.
 */
class VeGalleryPickerDialog(
    private val activity: Activity,
    private val accentColor: Int,
    private val onPicked: (uri: String) -> Unit,
) {
    private data class Folder(val bucketId: Long, val name: String, val count: Int, val coverUri: String)

    private val io = Executors.newSingleThreadExecutor()
    private var dialog: AlertDialog? = null
    private var folders: List<Folder> = emptyList()
    private var images: List<String> = emptyList()
    private var currentFolder: Folder? = null
    private var filter = ""
    private var selected: String? = null

    private val collection
        get() = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    // adapters are fields: the folder click swaps the grid to the images
    // adapter, so they must outlive the local scope in which they are made
    private lateinit var foldersAdapter: FoldersAdapter
    private lateinit var imagesAdapter: ImagesAdapter

    fun show() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_ve_gallery_picker, null)
        val back = view.findViewById<ImageView>(R.id.ve_picker_back)
        val title = view.findViewById<TextView>(R.id.ve_picker_title)
        val search = view.findViewById<EditText>(R.id.ve_picker_search)
        val grid = view.findViewById<RecyclerView>(R.id.ve_picker_grid)
        val empty = view.findViewById<TextView>(R.id.ve_picker_empty)
        val cancel = view.findViewById<TextView>(R.id.ve_picker_cancel)
        val ok = view.findViewById<TextView>(R.id.ve_picker_ok)

        // 4-column square tiles like the reference picker (folders and images alike)
        grid.layoutManager = GridLayoutManager(activity, 4)
        ok.setTextColor(accentColor)
        cancel.setTextColor(accentColor)

        foldersAdapter = FoldersAdapter { folder ->
            currentFolder = folder
            title.text = folder.name
            back.visibility = View.VISIBLE
            search.visibility = View.GONE
            io.execute {
                val list = queryImages(folder.bucketId)
                activity.runOnUiThread {
                    images = list
                    grid.adapter = imagesAdapter
                    updateEmpty(empty, grid, list.isEmpty())
                }
            }
        }
        imagesAdapter = ImagesAdapter { uri ->
            selected = if (selected == uri) null else uri
            refreshOk(ok)
        }

        fun showFolderLevel() {
            currentFolder = null
            selected = null
            refreshOk(ok)
            title.setText(R.string.ve_picker_title)
            back.visibility = View.GONE
            search.visibility = View.VISIBLE
            grid.adapter = foldersAdapter
            foldersAdapter.submit(foldersToShow())
            updateEmpty(empty, grid, folders.isEmpty())
        }

        back.setOnClickListener { showFolderLevel() }
        cancel.setOnClickListener { dialog?.dismiss() }
        ok.setOnClickListener {
            val uri = selected ?: return@setOnClickListener
            dialog?.dismiss()
            onPicked(uri)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = s?.toString().orEmpty()
                if (currentFolder == null) foldersAdapter.submit(foldersToShow())
            }
        })
        refreshOk(ok)

        val d = AlertDialog.Builder(activity).setView(view).create()
        dialog = d
        d.setOnDismissListener { io.shutdown() }
        d.setOnKeyListener { _, keyCode, _ ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && currentFolder != null) {
                showFolderLevel()
                true
            } else {
                false
            }
        }
        d.show()

        // compact dialog (default alert width), but rounded corners on the
        // app's background color instead of the stock window chrome (M19p)
        val corner = 14f * activity.resources.displayMetrics.density
        d.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = corner
                setColor(activity.baseConfig.backgroundColor)
            }
        )

        io.execute {
            val list = queryFolders()
            activity.runOnUiThread {
                folders = list
                if (d.isShowing) showFolderLevel()
            }
        }
    }

    private fun foldersToShow(): List<Folder> {
        val f = filter.trim()
        return if (f.isEmpty()) folders else folders.filter { it.name.contains(f, ignoreCase = true) }
    }

    private fun refreshOk(ok: TextView) {
        val enabled = selected != null
        ok.isEnabled = enabled
        ok.alpha = if (enabled) 1f else 0.5f
    }

    private fun updateEmpty(empty: TextView, grid: RecyclerView, isEmpty: Boolean) {
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        grid.visibility = if (isEmpty) View.INVISIBLE else View.VISIBLE
    }

    // ------------------------------------------------------------ MediaStore

    /**
     * Buckets visible by the app's HOME rules (M19p): the exact same check the
     * main grid runs (`shouldFolderBeVisible`) — excluded folders (unless
     * temporarily re-shown), hidden (dot/.nomedia) folders unless the user
     * shows hidden, included-folder overrides. Off-main-thread by contract.
     */
    @Suppress("DEPRECATION") // MediaStore DATA: same approach as MediaFetcher
    private fun queryFolders(): List<Folder> = try {
        val config = activity.config
        val excludedPaths = if (config.temporarilyShowExcluded) HashSet<String>() else config.excludedFolders
        val includedPaths = config.includedFolders
        val showHidden = config.shouldShowHidden
        val noMediaStatuses = HashMap<String, Boolean>()
        activity.getNoMediaFoldersSync().forEach { folder ->
            noMediaStatuses["$folder/$NOMEDIA"] = true
        }
        val visibleCache = HashMap<String, Boolean>()
        fun bucketVisible(path: String?): Boolean {
            // location unknown -> keep the bucket; never silently hide media
            if (path.isNullOrEmpty()) return true
            return visibleCache.getOrPut(path) {
                path.shouldFolderBeVisible(excludedPaths, includedPaths, showHidden, noMediaStatuses) { _, _ -> }
            }
        }

        val map = LinkedHashMap<Long, Folder>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
        )
        // DATE_ADDED DESC -> the first row per bucket is its newest cover
        activity.contentResolver.query(
            collection, projection, null, null,
            MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (c.moveToNext()) {
                val bucketId = c.getLong(bucketCol)
                if (map.containsKey(bucketId)) {
                    map[bucketId] = map.getValue(bucketId).copy(count = map.getValue(bucketId).count + 1)
                    continue
                }
                val dir = c.getString(dataCol)?.substringBeforeLast('/', "")
                if (!bucketVisible(dir)) continue
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString()
                map[bucketId] = Folder(bucketId, c.getString(nameCol) ?: "", 1, uri)
            }
        }
        map.values.sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        // no read permission -> the picker shows its empty state
        emptyList()
    }

    private fun queryImages(bucketId: Long): List<String> = try {
        val out = ArrayList<String>()
        activity.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            MediaStore.Images.Media.BUCKET_ID + "=?",
            arrayOf(bucketId.toString()),
            MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                out.add(ContentUris.withAppendedId(collection, c.getLong(idCol)).toString())
            }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }

    // -------------------------------------------------------------- adapters

    private inner class FoldersAdapter(private val onClick: (Folder) -> Unit) :
        RecyclerView.Adapter<FoldersAdapter.VH>() {

        private var shown: List<Folder> = emptyList()

        fun submit(list: List<Folder>) {
            shown = list
            notifyDataSetChanged()
        }

        inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_ve_gallery_folder, parent, false)
        ) {
            val cover: ImageView = itemView.findViewById(R.id.ve_folder_cover)
            val name: TextView = itemView.findViewById(R.id.ve_folder_name)
            val count: TextView = itemView.findViewById(R.id.ve_folder_count)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)
        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val folder = shown[position]
            holder.name.text = folder.name
            holder.count.text = activity.resources.getString(R.string.ve_picker_count, folder.count)
            Glide.with(holder.cover).load(folder.coverUri).centerCrop().into(holder.cover)
            holder.itemView.setOnClickListener { onClick(folder) }
        }
    }

    private inner class ImagesAdapter(private val onClick: (String) -> Unit) :
        RecyclerView.Adapter<ImagesAdapter.VH>() {

        /** accent circle behind the white check of the selected tile */
        private val checkBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
        }

        inner class VH(parent: ViewGroup) : RecyclerView.ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_ve_gallery_image, parent, false)
        ) {
            val thumb: ImageView = itemView.findViewById(R.id.ve_image_thumb)
            val check: ImageView = itemView.findViewById(R.id.ve_image_check)

            init {
                check.background = checkBg
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent)
        override fun getItemCount() = images.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uri = images[position]
            Glide.with(holder.thumb).load(uri).centerCrop().into(holder.thumb)
            val isSelected = uri == selected
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.thumb.alpha = if (isSelected) 0.55f else 1f
            holder.itemView.setOnClickListener {
                onClick(uri)
                notifyDataSetChanged()
            }
        }
    }
}
