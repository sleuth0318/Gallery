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
import android.view.MotionEvent
import android.view.View
import com.goodwy.gallery.videoeditor.effects.OverlayBitmapFactory
import com.goodwy.gallery.videoeditor.model.VeBrushStroke
import com.goodwy.gallery.videoeditor.model.VeOverlayItem
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class OverlayStageView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, MOVE_ITEMS, BRUSH, FOCUS_CENTER }

    interface Listener {
        fun onItemMoved(item: VeOverlayItem, finished: Boolean)
        fun onItemDeleted(item: VeOverlayItem)
        fun onItemTapped(item: VeOverlayItem)
        fun onStrokeFinished(stroke: VeBrushStroke)
        fun onFocusCenterChanged(x: Float, y: Float)
        fun onStageTappedEmpty()
    }

    var listener: Listener? = null
    var mode: Mode = Mode.IDLE
        set(value) {
            field = value
            invalidate()
        }

    var brushColor: Int = 0xFFE53935.toInt()
    var brushSizeFraction: Float = 0.012f

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

    private fun displayedRect(): RectF {
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

    fun baseBox(item: VeOverlayItem, r: RectF): RectF {
        val isText = item.kind == VeOverlayItem.Kind.TEXT
        val w = r.width() * (if (isText) 0.55f else 0.24f) * item.scale
        val h = if (isText) r.height() * 0.20f * item.scale else w * r.height() / r.width()
        val c = normToView(PointF(item.centerX, item.centerY), r)
        return RectF(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = displayedRect()
        if (r.width() <= 0f) return

        for (s in strokes) drawStroke(canvas, s.points, s.color, s.sizeFraction, r)
        liveStroke?.let { drawStroke(canvas, it, liveStrokeColor, liveStrokeSize, r) }

        for (item in items) {
            val box = baseBox(item, r)
            val wPx = box.width().toInt().coerceAtLeast(8)
            val hPx = box.height().toInt().coerceAtLeast(8)
            val bmp = itemBitmap(item, wPx, hPx) ?: continue
            val cX = box.centerX()
            val cY = box.centerY()
            canvas.save()
            canvas.rotate(item.rotationDegrees, cX, cY)
            canvas.drawBitmap(bmp, null, box, itemPaint)
            canvas.restore()
            if (item.id == selectedId && mode == Mode.MOVE_ITEMS) {
                canvas.save()
                canvas.rotate(item.rotationDegrees, cX, cY)
                canvas.drawRect(box, selectionPaint)
                canvas.restore()
            }
        }

        if (showFocusMarker && mode == Mode.FOCUS_CENTER) {
            val p = normToView(PointF(focusX, focusY), r)
            canvas.drawCircle(p.x, p.y, 16f * resources.displayMetrics.density, markerPaint)
            canvas.drawCircle(p.x, p.y, 3f * resources.displayMetrics.density, markerPaint.apply {
                style = Paint.Style.FILL
            })
            markerPaint.style = Paint.Style.STROKE
        }
    }

    private val bitmapCache = HashMap<String, Bitmap>()

    private fun itemBitmap(item: VeOverlayItem, wPx: Int, hPx: Int): Bitmap? {
        val key = "${item.id}|${item.content}|${item.color}|${item.fontFamily}|$wPx|$hPx"
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
    private var lastX = 0f
    private var lastY = 0f
    private var startSpan = 0f
    private var startAngle = 0f
    private var startScale = 1f
    private var startRotation = 0f
    private var downTime = 0L
    private var moved = false
    private var longPressHandled = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = displayedRect()
        when (mode) {
            Mode.IDLE -> return false
            Mode.BRUSH -> return handleBrush(event, r)
            Mode.FOCUS_CENTER -> return handleFocus(event, r)
            Mode.MOVE_ITEMS -> return handleItems(event, r)
        }
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
                val hit = items.lastOrNull { hitTest(it, event.x, event.y, r) }
                if (hit != null) {
                    activeItemId = hit.id
                    selectedId = hit.id
                    lastX = event.x
                    lastY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    invalidate()
                    return true
                }
                selectedId = -1L
                invalidate()
                listener?.onStageTappedEmpty()
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && activeItemId != -1L) {
                    startSpan = span(event)
                    startAngle = angle(event)
                    currentItem()?.let {
                        startScale = it.scale
                        startRotation = it.rotationDegrees
                    }
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val item = currentItem() ?: return false
                if (!moved && event.pointerCount == 1) {
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
                        val newScale = (startScale * scaleFactor).coerceIn(0.25f, 6f)
                        val newRotation = startRotation + Math.toDegrees((newAngle - startAngle).toDouble()).toFloat()
                        updateItem(item.copy(scale = newScale, rotationDegrees = normalizeAngle(newRotation)), finished = false)
                    }
                    moved = true
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(dx) > 0.5f || abs(dy) > 0.5f) moved = true
                lastX = event.x
                lastY = event.y
                updateItem(item.moved(dx / r.width(), dy / r.height()), finished = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val item = currentItem()
                if (item != null) {
                    if (!moved) {
                        listener?.onItemTapped(item)
                    }
                    listener?.onItemMoved(item, finished = true)
                }
                activeItemId = -1L
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
