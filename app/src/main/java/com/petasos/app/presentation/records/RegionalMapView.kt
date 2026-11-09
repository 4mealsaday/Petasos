package com.petasos.app.presentation.records

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.petasos.app.R
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/** Offline public-boundary backdrop. Recorded lines are observations, not a routable navigation map. */
class RegionalMapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private class ShapePath(val shape: DisplayRegionShape, val path: Path, val bounds: RectF) {
        val screenPath = Path()
        var inViewport = false
    }
    private class RoutePath(val path: Path, val singlePoint: FloatArray?)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transform = Matrix()
    private val inverse = Matrix()
    private var shapePaths = emptyList<ShapePath>()
    private var paths = emptyList<RecordedPath>()
    private var screenRoutes = emptyList<RoutePath>()
    private val screenCurrent = FloatArray(2)
    private var drawCurrent = false
    private var drawingReady = false
    private var highlighted = emptySet<String>()
    private var selected: String? = null
    private var focus = false
    private var current: MapCoordinate? = null
    private val viewport = RectF()
    private var pressedRegion: String? = null
    var onRegionSelected: ((String) -> Unit)? = null

    init {
        minimumHeight = dp(260f).toInt()
        setBackgroundColor(context.getColor(R.color.records_map_water))
        contentDescription = context.getString(R.string.records_ui_map_description)
        isFocusable = true
    }

    fun submit(
        geometry: List<DisplayRegionShape>, paths: List<RecordedPath> = emptyList(),
        highlightedRegionIds: Set<String> = emptySet(), selectedRegionId: String? = null,
        focusOnPaths: Boolean = false,
    ) {
        if (shapePaths.size != geometry.size || shapePaths.zip(geometry).any { it.first.shape !== it.second }) {
            shapePaths = geometry.map { shape ->
                val path = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    shape.rings.forEach { ring ->
                        ring.forEachIndexed { i, point -> if (i == 0) moveTo(x(point), y(point)) else lineTo(x(point), y(point)) }
                        close()
                    }
                }
                ShapePath(shape, path, RectF().also { path.computeBounds(it, true) })
            }
        }
        this.paths = paths.toList(); highlighted = highlightedRegionIds.toSet(); selected = selectedRegionId; focus = focusOnPaths
        updateTransform(); invalidate()
    }

    fun setCurrentPosition(point: MapCoordinate?) {
        current = point?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -80.0..80.0 && it.longitude in -180.0..180.0 }
        updateTransform(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { updateTransform() }

    private fun updateTransform() {
        drawingReady = false
        drawCurrent = false
        screenRoutes = emptyList()
        if (width <= dp(28f) || height <= dp(28f) || shapePaths.isEmpty()) return
        var bounds: RectF? = null
        fun include(point: MapCoordinate) {
            val px = x(point); val py = y(point)
            if (bounds == null) bounds = RectF(px, py, px, py) else bounds!!.apply {
                left = min(left, px); right = max(right, px); top = min(top, py); bottom = max(bottom, py)
            }
        }
        if (focus) { paths.forEach { it.points.forEach(::include) }; current?.let(::include) }
        if (bounds == null) {
            val chosen = selected?.let { key -> shapePaths.firstOrNull { it.shape.regionId == key } }
            if (focus && chosen != null) bounds = RectF(chosen.bounds)
            else shapePaths.forEach { if (bounds == null) bounds = RectF(it.bounds) else bounds!!.union(it.bounds) }
        }
        val source = bounds ?: return
        val minimum = if (focus) .035f else .001f
        val extraX = max(source.width() * .12f, minimum)
        val extraY = max(source.height() * .12f, minimum)
        source.inset(-extraX, -extraY)
        viewport.set(source)
        val margin = dp(14f)
        transform.setRectToRect(source, RectF(margin, margin, width - margin, height - margin), Matrix.ScaleToFit.CENTER)
        transform.invert(inverse)
        // Projection and path allocation belong to changed data/size, never a draw frame.
        shapePaths.forEach { entry ->
            entry.inViewport = RectF.intersects(entry.bounds, viewport)
            entry.screenPath.reset()
            if (entry.inViewport) entry.path.transform(transform, entry.screenPath)
        }
        screenRoutes = paths.filter { it.points.isNotEmpty() }.map { line ->
            if (line.points.size == 1) {
                val point = line.points.single()
                RoutePath(Path(), floatArrayOf(x(point), y(point)).also(transform::mapPoints))
            } else {
                val route = Path()
                line.points.forEachIndexed { index, point ->
                    if (index == 0) route.moveTo(x(point), y(point)) else route.lineTo(x(point), y(point))
                }
                route.transform(transform)
                RoutePath(route, null)
            }
        }
        current?.let { point ->
            screenCurrent[0] = x(point); screenCurrent[1] = y(point)
            transform.mapPoints(screenCurrent)
            drawCurrent = true
        }
        drawingReady = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!drawingReady) return
        for (index in shapePaths.indices) {
            val entry = shapePaths[index]
            if (!entry.inViewport) continue
            paint.style = Paint.Style.FILL
            paint.color = when (entry.shape.regionId) {
                selected -> context.getColor(R.color.records_map_selected)
                in highlighted -> context.getColor(R.color.records_map_passage)
                else -> context.getColor(R.color.records_map_land)
            }
            canvas.drawPath(entry.screenPath, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(.65f); paint.color = context.getColor(R.color.records_map_border)
            canvas.drawPath(entry.screenPath, paint)
        }
        paint.color = context.getColor(if (focus) R.color.records_map_route else R.color.records_map_route_overview)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f); paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        for (index in screenRoutes.indices) {
            val route = screenRoutes[index]
            val point = route.singlePoint
            if (point != null) {
                paint.style = Paint.Style.FILL; canvas.drawCircle(point[0], point[1], dp(2f), paint); paint.style = Paint.Style.STROKE
            } else {
                canvas.drawPath(route.path, paint)
            }
        }
        if (drawCurrent) {
            paint.style = Paint.Style.FILL; paint.color = context.getColor(R.color.records_ui_selected_text); canvas.drawCircle(screenCurrent[0], screenCurrent[1], dp(10f), paint)
            paint.color = context.getColor(R.color.records_ui_blue); canvas.drawCircle(screenCurrent[0], screenCurrent[1], dp(7f), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onRegionSelected == null) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) {
            pressedRegion = hit(event.x, event.y)
            return pressedRegion != null
        }
        if (event.action == MotionEvent.ACTION_UP && pressedRegion != null) {
            if (pressedRegion == hit(event.x, event.y)) performClick()
            pressedRegion = null
            return true
        }
        if (event.action == MotionEvent.ACTION_CANCEL) pressedRegion = null
        return pressedRegion != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        pressedRegion?.let { onRegionSelected?.invoke(it) }
        return true
    }

    private fun hit(screenX: Float, screenY: Float): String? {
        val point = floatArrayOf(screenX, screenY); inverse.mapPoints(point)
        // Prefer the smallest exact public polygon when city/district geometries overlap.
        return shapePaths.filter { it.bounds.contains(point[0], point[1]) }.sortedBy { it.bounds.width() * it.bounds.height() }
            .firstOrNull { entry ->
                val scaled = Path(); entry.path.transform(Matrix().apply { setScale(10_000f, 10_000f) }, scaled)
                val bounds = RectF(); scaled.computeBounds(bounds, true)
                val clip = Region(bounds.left.toInt() - 1, bounds.top.toInt() - 1, bounds.right.toInt() + 1, bounds.bottom.toInt() + 1)
                Region().apply { setPath(scaled, clip) }.contains((point[0] * 10_000).toInt(), (point[1] * 10_000).toInt())
            }?.shape?.regionId
    }

    private fun x(point: MapCoordinate) = point.longitude.toFloat()
    private fun y(point: MapCoordinate) = (-ln(tan(Math.PI / 4 + Math.toRadians(point.latitude) / 2)) * 180 / Math.PI).toFloat()
    private fun dp(value: Float) = value * resources.displayMetrics.density
}

/** Shape drawn from the same public boundary; acquired colour never follows a map selection. */
class RegionSilhouetteView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sourcePath = Path()
    private val screenPath = Path()
    private val sourceBounds = RectF()
    private val targetBounds = RectF()
    private val placement = Matrix()
    fun submit(shape: DisplayRegionShape, collected: Boolean) {
        contentDescription = context.getString(if (collected) R.string.records_ui_collected_shape else R.string.records_ui_uncollected_shape, shape.name)
        sourcePath.reset()
        sourcePath.apply {
            fillType = Path.FillType.EVEN_ODD
            shape.rings.forEach { ring ->
                ring.forEachIndexed { i, p ->
                    val x = (p.longitude * kotlin.math.cos(Math.toRadians(36.0))).toFloat()
                    val y = -p.latitude.toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }; close()
            }
        }
        sourcePath.computeBounds(sourceBounds, true)
        paint.color = context.getColor(if (collected) R.color.records_map_route else R.color.records_map_uncollected)
        updatePlacement(); invalidate()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { updatePlacement() }
    private fun updatePlacement() {
        screenPath.reset()
        if (width <= 16 || height <= 16 || sourceBounds.isEmpty) return
        targetBounds.set(8f, 8f, width - 8f, height - 8f)
        placement.setRectToRect(sourceBounds, targetBounds, Matrix.ScaleToFit.CENTER)
        sourcePath.transform(placement, screenPath)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(screenPath, paint)
    }
}
