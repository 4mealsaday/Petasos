package com.petasos.app.presentation.records

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.R
import com.petasos.app.records.TripCollectionKind
import com.petasos.app.records.TripRecordStatus
import com.petasos.app.records.TripResultHeader
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Parked, read-only records UI. Hidden/rebound panels discard user data and fence all late callbacks. */
class RecordsPanel(context: Context) : LinearLayout(context) {
    private enum class Page { MAP, ALBUM, TRIPS, REGION, TRIP }
    private val body: LinearLayout
    private val scroll: ScrollView
    private val refreshButton: Button
    private val tabs: Map<Page, Button>
    private val geometryWorker = Executors.newSingleThreadExecutor()
    private var source: RecordsDataSource? = null
    private var available = false
    private var disposed = false
    private var epoch = 0L
    private var read = 0L
    private var page = Page.MAP
    private var atlas: RecordsAtlasSnapshot? = null
    private var region: RecordsRegionSnapshot? = null
    private var trip: RecordsTripSnapshot? = null
    private var geometry = emptyList<DisplayRegionShape>()
    private var selectedRegion: String? = null
    private var albumPage = 0
    private var onlyCollected = false

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(12), dp(20), 0)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        LayoutInflater.from(context).inflate(R.layout.records_panel, this, true)
        body = findViewById(R.id.records_ui_body)
        scroll = findViewById(R.id.records_ui_scroll)
        refreshButton = findViewById<Button>(R.id.records_ui_refresh).apply { setOnClickListener { refresh() }; styleButton(this, false) }
        tabs = mapOf(Page.MAP to findViewById(R.id.records_ui_tab_map), Page.ALBUM to findViewById(R.id.records_ui_tab_album), Page.TRIPS to findViewById(R.id.records_ui_tab_trips))
        tabs.forEach { (target, button) -> button.setOnClickListener { if (available) {
            read++; page = target
            if (atlas == null) refresh() else render()
        } } }
        render()
    }

    fun update(value: RecordsDataSource?, allowed: Boolean) {
        if (disposed) return
        val usable = allowed && value != null
        if (source === value && available == usable) return
        epoch++; read++; source = value; available = usable
        atlas = null; region = null; trip = null; selectedRegion = null; albumPage = 0; page = Page.MAP
        render()
        if (usable) { loadGeometry(); refresh() }
    }

    fun refresh() {
        val current = source?.takeIf { available && !disposed } ?: return
        val serial = ++read
        showMessage(R.string.records_ui_loading)
        observe(current, current.readAtlas(atlas?.tripOffset ?: 0), serial) {
            atlas = it
            when (page) {
                Page.REGION -> selectedRegion?.let(::openRegion) ?: render()
                Page.TRIP -> trip?.results?.header?.tripId?.let(::openTrip) ?: run { page = Page.TRIPS; render() }
                else -> render()
            }
        }
    }

    fun dispose() {
        if (disposed) return
        update(null, false); disposed = true; geometryWorker.shutdownNow()
    }

    override fun onDetachedFromWindow() {
        update(null, false)
        super.onDetachedFromWindow()
    }

    private fun loadGeometry() {
        if (geometry.isNotEmpty()) return
        val token = epoch
        geometryWorker.execute {
            val values = try { RecordsGeometryCatalog.load(context.applicationContext) } catch (_: Exception) { emptyList() }
            post { if (!disposed && available && token == epoch) { geometry = values; render() } }
        }
    }

    private fun openRegion(id: String) {
        val current = source?.takeIf { available } ?: return
        selectedRegion = id; page = Page.REGION; region = null
        val serial = ++read; showMessage(R.string.records_ui_loading)
        observe(current, current.readRegion(id), serial) { region = it; render() }
    }

    private fun openTrip(id: String) {
        val current = source?.takeIf { available } ?: return
        page = Page.TRIP; trip = null
        val serial = ++read; showMessage(R.string.records_ui_loading)
        observe(current, current.readTrip(id), serial) { trip = it; render() }
    }

    private fun <T> observe(current: RecordsDataSource, future: ListenableFuture<T>, serial: Long, accept: (T) -> Unit) {
        val token = epoch
        future.addListener({
            if (disposed || !available || source !== current || token != epoch || serial != read) return@addListener
            try { accept(future.get()) } catch (_: Exception) { showMessage(R.string.records_ui_failed) }
        }, context.mainExecutor)
    }

    private fun render() {
        tabs.forEach { (target, button) -> button.isEnabled = available; styleButton(button, page == target || (page == Page.REGION && target == Page.ALBUM) || (page == Page.TRIP && target == Page.TRIPS)) }
        refreshButton.isEnabled = available
        body.removeAllViews()
        if (!available) { text(body, context.getString(R.string.records_ui_unavailable)); return }
        if (atlas == null) { text(body, context.getString(R.string.records_ui_loading)); return }
        when (page) {
            Page.MAP -> renderMap()
            Page.ALBUM -> renderAlbum()
            Page.TRIPS -> renderTrips()
            Page.REGION -> renderRegion()
            Page.TRIP -> renderTrip()
        }
        scroll.scrollTo(0, 0)
    }

    private fun renderMap() {
        val data = atlas ?: return
        text(body, context.getString(R.string.records_ui_collected_count, data.collectedRegions.size), true)
        map(body, data.paths, focus = false)
        text(body, context.getString(if (data.paths.isEmpty()) R.string.records_ui_empty_routes else R.string.records_ui_map_caption))
        if (data.routeSamplesTruncated) text(body, context.getString(R.string.records_ui_map_limited))
        attribution()
        data.collectedRegions.take(12).forEach { proof ->
            val shape = geometry.firstOrNull { it.regionId == proof.regionId } ?: return@forEach
            button(body, name(shape)) { openRegion(shape.regionId) }
        }
        button(body, context.getString(R.string.records_ui_album)) { page = Page.ALBUM; albumPage = 0; render() }
    }

    private fun renderAlbum() {
        val collected = atlas?.collectedRegions.orEmpty().map { it.regionId }.toSet()
        val filter = row(body)
        button(filter, context.getString(R.string.records_ui_all_regions), weighted = true, selected = !onlyCollected) { onlyCollected = false; albumPage = 0; render() }
        button(filter, context.getString(R.string.records_ui_only_collected), weighted = true, selected = onlyCollected) { onlyCollected = true; albumPage = 0; render() }
        text(body, context.getString(R.string.records_ui_album_note))
        val shapes = geometry.filter { !onlyCollected || it.regionId in collected }.sortedWith(compareBy<DisplayRegionShape> { it.regionId !in collected }.thenBy { it.code })
        if (shapes.isEmpty()) { text(body, context.getString(R.string.records_ui_no_regions)); return }
        albumPage = albumPage.coerceIn(0, (shapes.size - 1) / ALBUM_PAGE_SIZE)
        val columns = if (resources.configuration.screenWidthDp < 720) 2 else 3
        shapes.drop(albumPage * ALBUM_PAGE_SIZE).take(ALBUM_PAGE_SIZE).chunked(columns).forEach { entries ->
            val line = row(body)
            entries.forEach { shape ->
                val earned = shape.regionId in collected
                val tile = LinearLayout(context).apply {
                    orientation = VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundResource(R.drawable.bg_glass_surface)
                    isSelected = earned
                    isClickable = true; isFocusable = true
                    contentDescription = name(shape) + ", " + context.getString(if (earned) R.string.records_ui_collected else R.string.records_ui_not_collected)
                    setOnClickListener { openRegion(shape.regionId) }
                }
                line.addView(tile, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                tile.addView(RegionSilhouetteView(context).apply { submit(shape, earned); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(LayoutParams.MATCH_PARENT, dp(100)))
                text(tile, name(shape), true).gravity = Gravity.CENTER
                text(tile, context.getString(if (earned) R.string.records_ui_collected else R.string.records_ui_not_collected)).gravity = Gravity.CENTER
            }
            repeat(columns - entries.size) { line.addView(View(context), LayoutParams(0, 1, 1f)) }
        }
        pager(albumPage, (shapes.size + ALBUM_PAGE_SIZE - 1) / ALBUM_PAGE_SIZE) { albumPage = it; render() }
        attribution()
    }

    private fun renderRegion() {
        button(body, context.getString(R.string.records_ui_back)) { page = Page.ALBUM; render() }
        val value = region ?: run { text(body, context.getString(R.string.records_ui_loading)); return }
        val shape = geometry.firstOrNull { it.regionId == value.regionId }
        if (shape != null) {
            body.addView(RegionSilhouetteView(context).apply { submit(shape, value.collected) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(150)))
            text(body, name(shape), true)
        }
        text(body, context.getString(if (value.collected) R.string.records_ui_collected else R.string.records_ui_not_collected))
        text(body, context.getString(R.string.records_ui_region_stories), true)
        text(body, context.getString(R.string.records_ui_region_stories_note))
        if (value.stories.isEmpty()) text(body, context.getString(R.string.records_ui_no_stories))
        value.stories.forEach { story ->
            val title = source?.storyTitle(story.versionId)
            if (title == null) text(body, context.getString(R.string.records_ui_story_unavailable), true)
            else button(body, context.getString(R.string.records_ui_replay, title)) {
                if (available && source?.storyTitle(story.versionId) != null) source?.replayStory(story.versionId)
            }
            text(body, context.getString(R.string.records_ui_completed_on, date(story.earnedAt)))
        }
        if (value.moreStories) text(body, context.getString(R.string.records_ui_more_details))
        text(body, context.getString(R.string.records_ui_linked_trips), true)
        if (value.trips.isEmpty()) text(body, context.getString(R.string.records_ui_no_trips))
        value.trips.forEach { entry -> button(body, tripTitle(entry)) { openTrip(entry.tripId) } }
        if (value.moreTrips) text(body, context.getString(R.string.records_ui_more_details))
        attribution()
    }

    private fun renderTrips() {
        val data = atlas ?: return
        text(body, context.getString(R.string.records_ui_trips_count, data.totalTrips), true)
        if (data.trips.isEmpty()) text(body, context.getString(R.string.records_ui_no_trips))
        data.trips.forEach { entry -> button(body, tripTitle(entry)) { openTrip(entry.tripId) } }
        val pageCount = ((data.totalTrips + 19) / 20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (pageCount > 1) pager(data.tripOffset / 20, pageCount) { next ->
            val current = source?.takeIf { available } ?: return@pager
            val serial = ++read; showMessage(R.string.records_ui_loading)
            observe(current, current.readAtlas(next * 20), serial) { atlas = it; render() }
        }
    }

    private fun renderTrip() {
        button(body, context.getString(R.string.records_ui_back)) { page = Page.TRIPS; render() }
        val value = trip ?: run { text(body, context.getString(R.string.records_ui_loading)); return }
        val result = value.results ?: run { text(body, context.getString(R.string.records_ui_no_trips)); return }
        text(body, tripTitle(result.header), true)
        map(body, value.paths, focus = true)
        text(body, context.getString(if (value.paths.isEmpty()) R.string.records_ui_empty_routes else R.string.records_ui_map_caption))
        if (value.routeSamplesTruncated) text(body, context.getString(R.string.records_ui_map_limited))
        val cards = result.collectionsWithProofInTrip.items
        text(body, context.getString(R.string.records_ui_trip_collections, cards.count { it.kind == TripCollectionKind.REGION }, cards.count { it.kind == TripCollectionKind.STORY }))
        if (result.collectionsWithProofInTrip.hasMore) text(body, context.getString(R.string.records_ui_more_details))
        text(body, context.getString(R.string.records_ui_summaries), true)
        if (result.savedSummaries.items.isEmpty()) text(body, context.getString(R.string.records_ui_no_summaries))
        result.savedSummaries.items.forEach { text(body, it.summaryText) }
        if (result.savedSummaries.hasMore) text(body, context.getString(R.string.records_ui_more_details))
        attribution()
    }

    private fun map(parent: LinearLayout, paths: List<RecordedPath>, focus: Boolean) {
        parent.addView(RegionalMapView(context).apply {
            submit(geometry, paths, atlas?.collectedRegions.orEmpty().map { it.regionId }.toSet(), focusOnPaths = focus)
            onRegionSelected = { if (available) openRegion(it) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.journey_records_map_height)).apply { bottomMargin = dp(8) })
    }

    private fun pager(index: Int, count: Int, change: (Int) -> Unit) {
        val line = row(body)
        button(line, context.getString(R.string.records_ui_previous), weighted = true) { change(index - 1) }.isEnabled = index > 0
        text(line, context.getString(R.string.records_ui_page, index + 1, count)).apply { gravity = Gravity.CENTER; layoutParams = LayoutParams(0, resources.getDimensionPixelSize(R.dimen.journey_records_button_height), 1f) }
        button(line, context.getString(R.string.records_ui_next), weighted = true) { change(index + 1) }.isEnabled = index + 1 < count
    }

    private fun attribution() { text(body, context.getString(R.string.records_ui_attribution)).setTextSize(
        android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.journey_records_attribution_size)) }
    private fun showMessage(id: Int) { body.removeAllViews(); text(body, context.getString(id)) }
    private fun row(parent: LinearLayout) = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
    private fun text(parent: LinearLayout, value: String, heading: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(
            if (heading) R.dimen.journey_records_heading_size else R.dimen.journey_records_body_size))
        setTextColor(context.getColor(if (heading) R.color.records_ui_text else R.color.records_ui_secondary))
        if (heading) setTypeface(null, Typeface.BOLD)
        setPadding(dp(4), dp(8), dp(4), dp(8))
        parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private fun button(parent: LinearLayout, label: String, weighted: Boolean = false, selected: Boolean = false, click: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.journey_records_button_text_size))
        minHeight = resources.getDimensionPixelSize(R.dimen.journey_records_button_height)
        setPadding(dp(14), dp(8), dp(14), dp(8)); styleButton(this, selected)
        setOnClickListener { if (available) click() }
        parent.addView(this, LayoutParams(if (weighted) 0 else LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, if (weighted) 1f else 0f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }
    private fun styleButton(button: Button, selected: Boolean) {
        button.setBackgroundResource(R.drawable.bg_glass_surface)
        button.isSelected = selected
        button.setTextColor(context.getColor(R.color.records_ui_blue))
    }
    private fun date(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))
    private fun tripTitle(entry: TripResultHeader) = context.getString(R.string.records_ui_trip_title, date(entry.startedAt), context.getString(when (entry.status) {
        TripRecordStatus.ACTIVE -> R.string.records_ui_trip_active
        TripRecordStatus.INTERRUPTED -> R.string.records_ui_trip_interrupted
        TripRecordStatus.CLOSED -> R.string.records_ui_trip_closed
    }))
    private fun name(shape: DisplayRegionShape): String {
        val parent = when (shape.code) { "41461", "41463", "41465" -> "용인시"; "44131", "44133" -> "천안시"; else -> null }
        val province = mapOf("11" to "서울", "26" to "부산", "27" to "대구", "28" to "인천", "29" to "광주", "30" to "대전", "31" to "울산", "36" to "세종", "41" to "경기", "43" to "충북", "44" to "충남", "46" to "전남", "47" to "경북", "48" to "경남", "50" to "제주", "51" to "강원", "52" to "전북")[shape.code.take(2)]
        return listOfNotNull(province, parent, shape.name).joinToString(" ")
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val ALBUM_PAGE_SIZE = 12 }
}
