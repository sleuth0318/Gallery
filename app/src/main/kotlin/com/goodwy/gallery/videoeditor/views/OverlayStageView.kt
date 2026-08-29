/*
 * Copyright (C) 2024  Goodwy Gallery contributors
 *
 * Interactive transparent stage stacked on top of the video preview:
 *  - MOVE_ITEMS mode: drag/pinch/rotate stickers & text (long-press deletes),
 *  - BRUSH mode: freehand strokes,
 *  - FOCUS_CENTER mode: tap/drag sets the blur focus point.
 * Coordinates are normalized to the *displayed* video rect (letterbox-aware),
 * which keeps them in sync with the normalized export coordinates. GPLv3.
 */
package com.goodwy.gallery.videoeditor.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.goodwy.gallery.videoeditor.effects.OverlayBitmapFactory
import com.goodwy.gallery.videoeditor.model.OverlayGeometry
import com.goodwy.gallery.videoeditor.model.VeBrushStroke
import com.goodwy.gallery.videoeditor.model.VeOverlayItem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** M19r extreme-resize bounds: every overlay box side (drag handles AND
 *  pinch) is limited only by [MIN_SIDE_DP]dp .. [MAX_BOX_FRAME]x the frame
 *  on that axis — from "very smallest" to "very largest". */
private const val MIN_SIDE_DP = 6f
private const val MAX_BOX_FRAME = 4f

class OverlayStageView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, MOVE_ITEMS, BRUSH, FOCUS_CENTER, CROP }

    interface Listener {
        fun onItemMoved(item: VeOverlayItem, finished: Boolean)
        fun onItemDeleted(item: VeOverlayItem)
        fun onItemTapped(item: VeOverlayItem)
        fun onStrokeFinished(stroke: VeBrushStroke)
        fun onFocusCenterChanged(x: Float, y: Float)
        fun onStageTappedEmpty()
        /** Free-transform crop window changed (normalized to the displayed rect). */
        fun onCropChanged(left: Float, top: Float, right: Float, bottom: Float, finished: Boolean)
    }

    var listener: Listener? = null
    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    /** Playback toggle callback, fired for a double-tap on "empty" canvas
     *  (wired by the activity). Touches aimed at content — brush strokes, item
     *  bodies, resize or crop handles — keep their normal meaning instead and
     *  never toggle playback. */
    var onCanvasDoubleTap: (() -> Unit)? = null

    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onCanvasDoubleTap?.invoke()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }  // the stage has its own long-press (delete item)

    /** Latched on every ACTION_DOWN: did that touch begin on empty canvas?
     *  Only those gesture streams are fed to [tapDetector], so manipulating an
     *  item/handle or painting a stroke can never toggle playback. */
    private var doubleTapEligible = false

    private fun isDoubleTapEligible(e: MotionEvent, r: RectF): Boolean = when (mode) {
        Mode.BRUSH -> false   // every brush touch paints — keep strokes pure
        Mode.MOVE_ITEMS ->
            hitResizeHandle(e.x, e.y, r) == null && items.none { hitTest(it, e.x, e.y, r) }
        Mode.CROP -> {
            // eligible everywhere except within touch-reach of a drag handle
            val cr = cropViewRect(r)
            val touchR = 22f * resources.displayMetrics.density
            handleDirs.none { d ->
                abs(e.x - (cr.centerX() + d[0] * cr.width() / 2f)) <= touchR &&
                    abs(e.y - (cr.centerY() + d[1] * cr.height() / 2f)) <= touchR
            }
        }
        else -> true   // IDLE / FOCUS_CENTER: bare canvas gestures only
    }

    var brushColor: Int = 0xFFE53935.toInt()
    var brushSizeFraction: Float = 0.012f
    /** While true (brush-size slider being dragged), a true-size preview circle
     *  is drawn in the center of the video rect, filled with the brush color. */
    var showBrushSizeIndicator: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var items: List<VeOverlayItem> = emptyList()
    private var strokes: List<VeBrushStroke> = emptyList()
    private var liveStroke: MutableList<PointF>? = null
    private var liveStrokeColor = brushColor
    private var liveStrokeSize = brushSizeFraction

    private var contentAspect = 16f / 9f    // outW/outH of the edited video
    private var selectedId: Long = -1L
    var showFocusMarker = false
    var focusX = 0.5f
    var focusY = 0.55f

    private val itemPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    /** Handle/frame blue, matching resize.jpg. */
    private val HANDLE_BLUE = 0xFF3E7BFA.toInt()

    /** Selected TEXT boxes get a solid frame + 8 square drag handles (resize.jpg style). */
    private val textFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HANDLE_BLUE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * resources.displayMetrics.density
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HANDLE_BLUE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * resources.displayMetrics.density
    }

    /** The 8 handle spots as (sx, sy) direction pairs: corners + edge midpoints. */
    private val handleDirs = arrayOf(
        intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        intArrayOf(-1, 0), intArrayOf(1, 0),
        intArrayOf(-1, 1), intArrayOf(0, 1), intArrayOf(1, 1),
    )

    private val brushPreviewFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cropDimPaint = Paint().apply { color = 0x80000000.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val brushPreviewOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    /** Displayed media-frame rect in view coords — also read by the activity
     *  for the brush eyedropper's touch-to-surface pixel mapping. */
    fun displayedRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return RectF(0f, 0f, vw, vh)
        val viewAspect = vw / vh
        return if (viewAspect > contentAspect) {
            val w = vh * contentAspect
            RectF((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
        } else {
            val h = vw / contentAspect
            RectF(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
        }
    }

    private fun normToView(p: PointF, r: RectF) = PointF(r.left + p.x * r.width(), r.top + p.y * r.height())
    private fun viewToNorm(x: Float, y: Float, r: RectF) =
        PointF(((x - r.left) / r.width()).coerceIn(0f, 1f), ((y - r.top) / r.height()).coerceIn(0f, 1f))

    fun setContentAspect(aspect: Float) {
        if (aspect > 0f) {
            contentAspect = aspect
            invalidate()
        }
    }

    fun setContent(items: List<VeOverlayItem>, strokes: List<VeBrushStroke>, preserveSelection: Boolean = true) {
        this.items = items
        this.strokes = strokes
        if (!preserveSelection) selectedId = -1L
        if (items.none { it.id == selectedId }) selectedId = items.lastOrNull()?.id ?: -1L
        invalidate()
    }

    // ------------------------------------------------------- free-crop state

    /** Free-form crop window, normalized 0..1 of the displayed video rect. */
    private var cropL = 0f
    private var cropT = 0f
    private var cropR = 1f
    private var cropB = 1f

    fun setCropRect(l: Float, t: Float, r: Float, b: Float) {
        cropL = l
        cropT = t
        cropR = r
        cropB = b
        invalidate()
    }

    internal fun cropViewRect(r: RectF) = RectF(
        r.left + cropL * r.width(), r.top + cropT * r.height(),
        r.left + cropR * r.width(), r.top + cropB * r.height(),
    )

    /** The currently selected item id, -1 when nothing is selected. */
    fun selectedItemId(): Long = selectedId

    /** Selects an item programmatically (shows the dashed box in MOVE_ITEMS mode). */
    fun selectItem(id: Long) {
        selectedId = id
        invalidate()
    }

    /** The item's box in THIS view's coordinates (used to overlay the inline text editor). */
    fun itemRect(item: VeOverlayItem): RectF = baseBox(item, displayedRect())

    /** Id of the item currently edited inline: its content is hidden on stage (the
     * inline editor draws it instead), only the selection frame remains. -1 = none. */
    private var editingItemId = -1L

    fun setEditingItemId(id: Long) {
        editingItemId = id
        invalidate()
    }

    fun baseBox(item: VeOverlayItem, r: RectF): RectF {
        // single source of truth shared with the export baker (OverlayGeometry)
        val (w, h) = OverlayGeometry.itemBoxSize(item, r.width(), r.height())
        val c = normToView(PointF(item.centerX, item.centerY), r)
        return RectF(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
    }

    /**
     * Clamps a proposed view-space center so the item's whole rotation-aware
     * AABB stays inside the canvas rect [r] (the media frame, or the crop
     * window while a crop is active). If the AABB exceeds the rect on an
     * axis, the center snaps to the rect middle on that axis. Keeping items
     * fully inside is also an EXPORT hard requirement: media3's BitmapOverlay
     * anchor must stay within [-1, 1] or Transformer fails the export.
     */
    private fun clampCenterInside(item: VeOverlayItem, cx: Float, cy: Float, r: RectF): PointF {
        val box = baseBox(item, r)
        val hw = box.width() / 2f
        val hh = box.height() / 2f
        val rad = Math.toRadians(item.rotationDegrees.toDouble())
        val cos = abs(kotlin.math.cos(rad)).toFloat()
        val sin = abs(kotlin.math.sin(rad)).toFloat()
        val aabbHw = hw * cos + hh * sin
        val aabbHh = hw * sin + hh * cos
        val minX = r.left + aabbHw
        val maxX = r.right - aabbHw
        val minY = r.top + aabbHh
        val maxY = r.bottom - aabbHh
        val nx = if (minX > maxX) r.centerX() else cx.coerceIn(minX, maxX)
        val ny = if (minY > maxY) r.centerY() else cy.coerceIn(minY, maxY)
        return PointF(nx, ny)
    }

    private fun normPoint(x: Float, y: Float, r: RectF) = PointF((x - r.left) / r.width(), (y - r.top) / r.height())

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = displayedRect()
        if (r.width() <= 0f) return

        for (s in strokes) drawStroke(canvas, s.points, s.color, s.sizeFraction, r)
        liveStroke?.let { drawStroke(canvas, it, liveStrokeColor, liveStrokeSize, r) }

        for (item in items) {
            val box = baseBox(item, r)
            val cX = box.centerX()
            val cY = box.centerY()
            if (item.id == editingItemId) {
                // the inline text editor renders the content; keep only the frame
                if (mode == Mode.MOVE_ITEMS) {
                    canvas.save()
                    canvas.rotate(item.rotationDegrees, cX, cY)
                    canvas.drawRect(box, selectionPaint)
                    canvas.restore()
                }
                continue
            }
            val wPx = box.width().toInt().coerceAtLeast(8)
            val hPx = box.height().toInt().coerceAtLeast(8)
            val bmp = itemBitmap(item, wPx, hPx) ?: continue
            canvas.save()
            canvas.rotate(item.rotationDegrees, cX, cY)
            canvas.drawBitmap(bmp, null, box, itemPaint)
            canvas.restore()
            if (item.id == selectedId && mode == Mode.MOVE_ITEMS) {
                canvas.save()
                canvas.rotate(item.rotationDegrees, cX, cY)
                // every kind (text/sticker/shape) gets the solid frame + handles
                drawHandles(canvas, box)
                canvas.restore()
            }
        }

        if (showBrushSizeIndicator) {
            // true-size brush preview: fill diameter == the stroke width that
            // brushSizeFraction paints (sizeFraction * content width)
            val radius = (brushSizeFraction * r.width() / 2f).coerceAtLeast(1f)
            brushPreviewFill.color = brushColor
            canvas.drawCircle(r.centerX(), r.centerY(), radius, brushPreviewFill)
            canvas.drawCircle(r.centerX(), r.centerY(), radius, brushPreviewOutline)
        }

        if (showFocusMarker && mode == Mode.FOCUS_CENTER) {
            val p = normToView(PointF(focusX, focusY), r)
            canvas.drawCircle(p.x, p.y, 16f * resources.displayMetrics.density, markerPaint)
            canvas.drawCircle(p.x, p.y, 3f * resources.displayMetrics.density, markerPaint.apply {
                style = Paint.Style.FILL
            })
            markerPaint.style = Paint.Style.STROKE
        }

        if (mode == Mode.CROP) drawCropOverlay(canvas, r)
    }

    /** Free-transform UI: dimmed surround, thirds grid, frame + 8 handles. */
    private fun drawCropOverlay(canvas: Canvas, r: RectF) {
        val cr = cropViewRect(r)
        canvas.drawRect(r.left, r.top, r.right, cr.top, cropDimPaint)
        canvas.drawRect(r.left, cr.bottom, r.right, r.bottom, cropDimPaint)
        canvas.drawRect(r.left, cr.top, cr.left, cr.bottom, cropDimPaint)
        canvas.drawRect(cr.right, cr.top, r.right, cr.bottom, cropDimPaint)
        val w3 = cr.width() / 3f
        val h3 = cr.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(cr.left + w3 * i, cr.top, cr.left + w3 * i, cr.bottom, gridPaint)
            canvas.drawLine(cr.left, cr.top + h3 * i, cr.right, cr.top + h3 * i, gridPaint)
        }
        drawHandles(canvas, cr)
    }

    private val bitmapCache = HashMap<String, Bitmap>()

    private fun itemBitmap(item: VeOverlayItem, wPx: Int, hPx: Int): Bitmap? {
        val key = "${item.id}|${item.content}|${item.color}|${item.fontFamily}|${item.textAlign}|${item.backgroundColor}|$wPx|$hPx"
        bitmapCache[key]?.let { return it }
        if (bitmapCache.size > 60) bitmapCache.clear()
        return try {
            OverlayBitmapFactory.renderItem(item, wPx, hPx).also { bitmapCache[key] = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun drawStroke(canvas: Canvas, points: List<PointF>, color: Int, sizeFraction: Float, r: RectF) {
        if (points.isEmpty()) return
        strokePaint.color = color
        strokePaint.strokeWidth = (sizeFraction * r.width()).coerceAtLeast(2f)
        if (points.size == 1) {
            val p = normToView(points[0], r)
            canvas.drawCircle(p.x, p.y, strokePaint.strokeWidth / 2f, strokePaint)
            return
        }
        val path = Path()
        points.forEachIndexed { i, np ->
            val p = normToView(np, r)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        canvas.drawPath(path, strokePaint)
    }

    // ----------------------------------------------------------------- touch

    private var activeItemId = -1L
    private var downOnItem = false
    private var lastX = 0f
    private var lastY = 0f
    private var startSpan = 0f
    private var startAngle = 0f
    private var startScale = 1f
    private var startRotation = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressHandled = false

    // border-handle resize session (selected TEXT item)
    private var resizing = false
    private var resizeSX = 0
    private var resizeSY = 0
    private var resizeTheta = 0f
    private val resizeAnchor = PointF()

    @SuppressLint("ClickableViewAccessibility")
    /** While set, EVERY touch is routed to it and all stage gestures are
     *  suspended (brush eyedropper: the activity drags the color loupe). */
    var touchInterceptor: ((MotionEvent) -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = displayedRect()
        touchInterceptor?.let {
            it(event)
            return true
        }
        // Double-tap empty canvas = play/pause. Eligibility is latched on DOWN
        // and only eligible streams are fed to the detector, so a second tap
        // it consumes never reaches the gesture handlers below.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            doubleTapEligible = isDoubleTapEligible(event, r)
        }
        if (doubleTapEligible && tapDetector.onTouchEvent(event)) return true
        val handled = when (mode) {
            Mode.IDLE -> false
            Mode.BRUSH -> handleBrush(event, r)
            Mode.FOCUS_CENTER -> handleFocus(event, r)
            Mode.CROP -> handleCrop(event, r)
            Mode.MOVE_ITEMS -> handleItems(event, r)
        }
        val result = handled || doubleTapEligible
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            doubleTapEligible = false
        }
        // Eligible-but-unhandled touches are still consumed: nothing below the
        // stage needs them (PlayerView has no controller) and keeping the full
        // stream lets the detector observe both taps of a double tap.
        return result
    }

    // free-crop drag session: 0 none, 1 handle, 2 move
    // (there is intentionally NO "tap outside to draw a new window" mode:
    //  near-full-frame windows leave only hairline dim margins, so a tap aiming
    //  at an edge handle and missing by a few px collapsed the whole crop to a
    //  degenerate rect — handles + inside-move are the complete gesture set)
    private var cropDragMode = 0
    private var cropHandleSX = 0
    private var cropHandleSY = 0

    private fun handleCrop(event: MotionEvent, r: RectF): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val cr = cropViewRect(r)
                val touchR = 22f * resources.displayMetrics.density
                var best: IntArray? = null
                var bestD = Float.MAX_VALUE
                for (d in handleDirs) {
                    val hx = cr.centerX() + d[0] * cr.width() / 2f
                    val hy = cr.centerY() + d[1] * cr.height() / 2f
                    val dist = maxOf(abs(event.x - hx), abs(event.y - hy))
                    if (dist <= touchR && dist < bestD) {
                        bestD = dist
                        best = d
                    }
                }
                cropDragMode = when {
                    best != null -> {
                        cropHandleSX = best[0]
                        cropHandleSY = best[1]
                        1
                    }
                    cr.contains(event.x, event.y) -> 2
                    else -> 0   // dim surround: ignore (see cropDragMode comment)
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val n = viewToNorm(event.x, event.y, r)
                val minSize = 0.08f
                when (cropDragMode) {
                    1 -> {
                        if (cropHandleSX < 0) cropL = n.x.coerceIn(0f, cropR - minSize)
                        else if (cropHandleSX > 0) cropR = n.x.coerceIn(cropL + minSize, 1f)
                        if (cropHandleSY < 0) cropT = n.y.coerceIn(0f, cropB - minSize)
                        else if (cropHandleSY > 0) cropB = n.y.coerceIn(cropT + minSize, 1f)
                    }
                    2 -> {
                        val dx = (event.x - lastX) / r.width()
                        val dy = (event.y - lastY) / r.height()
                        val w = cropR - cropL
                        val h = cropB - cropT
                        cropL = (cropL + dx).coerceIn(0f, 1f - w)
                        cropR = cropL + w
                        cropT = (cropT + dy).coerceIn(0f, 1f - h)
                        cropB = cropT + h
                    }
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                listener?.onCropChanged(cropL, cropT, cropR, cropB, finished = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (cropDragMode != 0) {
                    cropDragMode = 0
                    // a degenerate window (accidental tap outside) resets to full frame
                    if (cropR - cropL < 0.05f || cropB - cropT < 0.05f) {
                        cropL = 0f
                        cropT = 0f
                        cropR = 1f
                        cropB = 1f
                    }
                    listener?.onCropChanged(cropL, cropT, cropR, cropB, finished = true)
                }
                return true
            }
        }
        return true
    }

    private fun handleBrush(event: MotionEvent, r: RectF): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                liveStrokeColor = brushColor
                liveStrokeSize = brushSizeFraction
                liveStroke = mutableListOf(viewToNorm(event.x, event.y, r))
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                liveStroke?.add(viewToNorm(event.x, event.y, r))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val pts = liveStroke
                if (pts != null && pts.isNotEmpty()) {
                    listener?.onStrokeFinished(VeBrushStroke(liveStrokeColor, liveStrokeSize, pts.toList()))
                }
                liveStroke = null
                invalidate()
                return true
            }
        }
        return true
    }

    private fun handleFocus(event: MotionEvent, r: RectF): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val n = viewToNorm(event.x, event.y, r)
                focusX = n.x
                focusY = n.y
                invalidate()
                listener?.onFocusCenterChanged(n.x, n.y)
                return true
            }
        }
        return true
    }

    private fun handleItems(event: MotionEvent, r: RectF): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = event.eventTime
                moved = false
                longPressHandled = false
                resizing = false
                lastX = event.x
                lastY = event.y
                // grab a border handle of the selected TEXT box: start resizing
                hitResizeHandle(event.x, event.y, r)?.let { dir ->
                    val item = items.firstOrNull { it.id == selectedId } ?: return@let
                    activeItemId = item.id
                    downOnItem = false
                    resizing = true
                    resizeSX = dir[0]
                    resizeSY = dir[1]
                    resizeTheta = item.rotationDegrees
                    val box = baseBox(item, r)
                    val ax = -dir[0] * box.width() / 2f
                    val ay = -dir[1] * box.height() / 2f
                    val rad = Math.toRadians(resizeTheta.toDouble())
                    resizeAnchor.set(
                        box.centerX() + (ax * kotlin.math.cos(rad) - ay * kotlin.math.sin(rad)).toFloat(),
                        box.centerY() + (ax * kotlin.math.sin(rad) + ay * kotlin.math.cos(rad)).toFloat(),
                    )
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                val hit = items.lastOrNull { hitTest(it, event.x, event.y, r) }
                if (hit != null) {
                    activeItemId = hit.id
                    selectedId = hit.id
                    downOnItem = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                // empty space: the selection STAYS enabled — the touch is still
                // consumed so a 2nd finger can pinch/rotate the selected item.
                downOnItem = false
                if (selectedId != -1L) {
                    invalidate()
                    return true
                }
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (resizing) return true
                // second finger ANYWHERE on stage scales/rotates the currently
                // selected item (outside-the-box pinch)
                if (event.pointerCount == 2) {
                    if (activeItemId == -1L && selectedId != -1L) activeItemId = selectedId
                    if (activeItemId != -1L) {
                        startSpan = span(event)
                        startAngle = angle(event)
                        currentItem()?.let {
                            startScale = it.scale
                            startRotation = it.rotationDegrees
                        }
                        moved = true
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizing) {
                    resizeMove(event.x, event.y, r)
                    return true
                }
                if (!moved && event.pointerCount == 1 && !downOnItem) {
                    // single-finger drag outside a box: only tracks tap-vs-move
                    val dist = sqrt(((event.x - lastX) * (event.x - lastX) + (event.y - lastY) * (event.y - lastY)).toDouble()).toFloat()
                    if (dist > 12f * resources.displayMetrics.density) moved = true
                    return true
                }
                val item = currentItem() ?: return false
                if (!moved && event.pointerCount == 1 && downOnItem) {
                    val dt = event.eventTime - downTime
                    val dist = sqrt(((event.x - lastX) * (event.x - lastX) + (event.y - lastY) * (event.y - lastY)).toDouble()).toFloat()
                    if (dt > 450 && dist < 12f * resources.displayMetrics.density && !longPressHandled) {
                        longPressHandled = true
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        listener?.onItemDeleted(item)
                        activeItemId = -1L
                        return true
                    }
                }
                if (event.pointerCount >= 2) {
                    val newSpan = span(event)
                    val newAngle = angle(event)
                    if (startSpan > 0f) {
                        val scaleFactor = (newSpan / startSpan).coerceIn(0.5f, 3f)
                        val newScale = (startScale * scaleFactor).coerceIn(0.05f, 20f)
                        val newRotation = normalizeAngle(startRotation + Math.toDegrees((newAngle - startAngle).toDouble()).toFloat())
                        val raw = item.copy(scale = newScale, rotationDegrees = newRotation)
                        // same min..max pixel bounds as the drag handles (M19r) —
                        // clamp only the gesture's OWN direction so a rotate-only
                        // pinch never re-clamps an already extreme box
                        var fitted = raw
                        val b = baseBox(raw, r)
                        if (b.width() > 0f && b.height() > 0f) {
                            val minSide = MIN_SIDE_DP * resources.displayMetrics.density
                            val maxFactor = min(r.width() * MAX_BOX_FRAME / b.width(), r.height() * MAX_BOX_FRAME / b.height())
                            val minFactor = max(minSide / b.width(), minSide / b.height())
                            val startRatio = startScale / newScale
                            val f = when {
                                newScale >= startScale && maxFactor < 1f -> maxFactor.coerceAtLeast(startRatio)
                                newScale < startScale && minFactor > 1f -> if (startRatio >= minFactor) minFactor else startRatio
                                else -> 1f
                            }
                            fitted = raw.copy(scale = newScale * f)
                        }
                        val pc = normToView(PointF(fitted.centerX, fitted.centerY), r)
                        val cc = clampCenterInside(fitted, pc.x, pc.y, r)
                        val cn = normPoint(cc.x, cc.y, r)
                        updateItem(fitted.copy(centerX = cn.x, centerY = cn.y), finished = false)
                    }
                    moved = true
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) > 0.5f || abs(dy) > 0.5f) moved = true
                lastX = event.x
                lastY = event.y
                // keep the whole element inside the media/crop canvas — items
                // dragged off-frame anchored outside [-1, 1] and killed exports
                val proposed = item.moved(dx / r.width(), dy / r.height())
                val pc = normToView(PointF(proposed.centerX, proposed.centerY), r)
                val cc = clampCenterInside(proposed, pc.x, pc.y, r)
                val cn = normPoint(cc.x, cc.y, r)
                updateItem(proposed.copy(centerX = cn.x, centerY = cn.y), finished = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (resizing) {
                    resizing = false
                    currentItem()?.let { listener?.onItemMoved(it, finished = true) }
                    activeItemId = -1L
                    downOnItem = false
                    return true
                }
                val item = currentItem()
                if (item != null) {
                    if (!moved) {
                        listener?.onItemTapped(item)
                    }
                    listener?.onItemMoved(item, finished = true)
                } else if (!downOnItem && !moved) {
                    // clean-preview tap on empty canvas: drop the selection so
                    // the 8 handles disappear; tapping an item re-selects it
                    // instantly (DOWN hit re-sets selectedId) (M19s)
                    if (selectedId != -1L) {
                        selectedId = -1L
                        invalidate()
                    }
                    listener?.onStageTappedEmpty()
                }
                activeItemId = -1L
                downOnItem = false
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // continue single-finger drag from the average of the two points
                if (event.pointerCount == 2) {
                    val remaining = if (event.getPointerId(event.actionIndex) == event.getPointerId(0)) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                    currentItem()?.let { startScale = it.scale; startRotation = it.rotationDegrees }
                }
                return true
            }
        }
        return true
    }

    /** Draws the solid frame + 8 square handles of a selected item (canvas is
     *  pre-rotated into the item's local space by the caller). */
    private fun drawHandles(canvas: Canvas, box: RectF) {
        canvas.drawRect(box, textFramePaint)
        val hs = 5.5f * resources.displayMetrics.density
        for (d in handleDirs) {
            val hx = box.centerX() + d[0] * box.width() / 2f
            val hy = box.centerY() + d[1] * box.height() / 2f
            canvas.drawRect(hx - hs, hy - hs, hx + hs, hy + hs, handleFillPaint)
            canvas.drawRect(hx - hs, hy - hs, hx + hs, hy + hs, handleStrokePaint)
        }
    }

    /** Returns the (sx, sy) handle under (x, y) on the selected item, or null. */
    private fun hitResizeHandle(x: Float, y: Float, r: RectF): IntArray? {
        val item = items.firstOrNull { it.id == selectedId } ?: return null
        if (item.id == editingItemId) return null
        val box = baseBox(item, r)
        val cx = box.centerX()
        val cy = box.centerY()
        val rad = Math.toRadians(-item.rotationDegrees.toDouble())
        val dx = x - cx
        val dy = y - cy
        val lx = (cx + dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)).toFloat()
        val ly = (cy + dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)).toFloat()
        // fat-finger target, shrunk for small boxes so neighbours stay reachable
        val touchR = minOf(22f * resources.displayMetrics.density, minOf(box.width(), box.height()) * 0.45f)
        var best: IntArray? = null
        var bestD = Float.MAX_VALUE
        for (d in handleDirs) {
            val hx = cx + d[0] * box.width() / 2f
            val hy = cy + d[1] * box.height() / 2f
            val dist = maxOf(abs(lx - hx), abs(ly - hy))
            if (dist <= touchR && dist < bestD) {
                bestD = dist
                best = d
            }
        }
        return best
    }

    /** Drags a border handle: resizes the box along the handle's axis (corners do
     *  both) while the OPPOSITE edge/corner stays anchored. Rotation-aware. */
    private fun resizeMove(x: Float, y: Float, r: RectF) {
        val item = currentItem() ?: return
        val density = resources.displayMetrics.density
        // pointer position in the item's local (unrotated) frame, origin at the anchor
        val radInv = Math.toRadians(-resizeTheta.toDouble())
        val dx = x - resizeAnchor.x
        val dy = y - resizeAnchor.y
        val lx = (dx * kotlin.math.cos(radInv) - dy * kotlin.math.sin(radInv)).toFloat()
        val ly = (dx * kotlin.math.sin(radInv) + dy * kotlin.math.cos(radInv)).toFloat()
        val box = baseBox(item, r)
        var newW = box.width()
        var newH = box.height()
        // M19r: very smallest .. very largest — bounded only by 6dp .. 4x frame
        val minSide = MIN_SIDE_DP * density
        if (resizeSX != 0) newW = (lx * resizeSX).coerceIn(minSide, r.width() * MAX_BOX_FRAME)
        if (resizeSY != 0) newH = (ly * resizeSY).coerceIn(minSide, r.height() * MAX_BOX_FRAME)
        // emoji (square) & gallery images (natural aspect) resize UNIFORMLY:
        // both axes follow the dominant one, the shape can never be stretched
        if (OverlayGeometry.isUniformResize(item)) {
            val uni = maxOf(newW / box.width(), newH / box.height())
            newW = box.width() * uni
            newH = box.height() * uni
        }
        // new center = anchor + rotated (sx*newW/2, sy*newH/2)
        val rad = Math.toRadians(resizeTheta.toDouble())
        val ox = resizeSX * newW / 2f
        val oy = resizeSY * newH / 2f
        val cx = resizeAnchor.x + (ox * kotlin.math.cos(rad) - oy * kotlin.math.sin(rad)).toFloat()
        val cy = resizeAnchor.y + (ox * kotlin.math.sin(rad) + oy * kotlin.math.cos(rad)).toFloat()
        val (baseW, baseH) = OverlayGeometry.itemBaseSize(item, r.width(), r.height())
        val cn = normPoint(cx, cy, r)
        val raw = item.copy(
            centerX = cn.x,
            centerY = cn.y,
            sizeX = (newW / baseW).coerceIn(0.02f, 100f),
            sizeY = (newH / baseH).coerceIn(0.02f, 100f),
        )
        // growth could push an edge past the canvas — pull the center back in
        val cc = clampCenterInside(raw, cx, cy, r)
        val ccN = normPoint(cc.x, cc.y, r)
        updateItem(raw.copy(centerX = ccN.x, centerY = ccN.y), finished = false)
        moved = true
    }

    private fun hitTest(item: VeOverlayItem, x: Float, y: Float, r: RectF): Boolean {
        val box = baseBox(item, r)
        // grow the box a touch for easier grabbing
        val grow = 10f * resources.displayMetrics.density
        box.inset(-grow, -grow)
        // rotate the point into the item's local space
        val cx = box.centerX()
        val cy = box.centerY()
        val rad = Math.toRadians(-item.rotationDegrees.toDouble())
        val dx = x - cx
        val dy = y - cy
        val lx = (cx + dx * kotlin.math.cos(rad) - dy * kotlin.math.sin(rad)).toFloat()
        val ly = (cy + dx * kotlin.math.sin(rad) + dy * kotlin.math.cos(rad)).toFloat()
        return box.contains(lx, ly)
    }

    private fun currentItem(): VeOverlayItem? = items.firstOrNull { it.id == activeItemId }

    private fun updateItem(updated: VeOverlayItem, finished: Boolean) {
        items = items.map { if (it.id == updated.id) updated else it }
        listener?.onItemMoved(updated, finished)
        invalidate()
    }

    private fun span(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun angle(e: MotionEvent): Float {
        if (e.pointerCount < 2) return 0f
        return atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble()).toFloat()
    }

    private fun normalizeAngle(a: Float): Float {
        var v = a % 360f
        if (v > 180f) v -= 360f
        if (v < -180f) v += 360f
        return v
    }
}
