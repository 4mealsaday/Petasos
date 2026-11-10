package com.petasos.app.presentation.records

import android.app.Activity
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.common.util.concurrent.ListenableFuture
import com.petasos.app.BuildConfig
import com.petasos.app.R
import com.petasos.app.presentation.map.JourneyMapStatus
import com.petasos.app.presentation.map.MapRoute
import com.petasos.app.presentation.map.MapScene
import com.petasos.app.presentation.map.ProductJourneyMap
import com.petasos.app.records.TripResultHeader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * "내 여행": the Naver map with every recorded journey on the left, regions/journeys on the right, plus the
 * summary numbers on Home. Read-only: it renders committed history and never grants any authority.
 */
class TripsScreen(private val activity: Activity) {
    private enum class Tab { REGIONS, JOURNEYS }

    private val resources = activity.resources
    private val map = ProductJourneyMap(activity)
    private val attribution = activity.findViewById<TextView>(R.id.trips_map_attribution)
    private val list = activity.findViewById<LinearLayout>(R.id.trips_list)
    private val scroll = activity.findViewById<View>(R.id.trips_scroll)
    private val tabRegions = activity.findViewById<TextView>(R.id.trips_tab_regions)
    private val tabJourneys = activity.findViewById<TextView>(R.id.trips_tab_journeys)
    private val homeSummary = activity.findViewById<TextView>(R.id.home_trips_summary)
    private val homeLatest = activity.findViewById<TextView>(R.id.home_trips_latest)
    private val worker = Executors.newSingleThreadExecutor()
    private val boldFont: Typeface? = resources.getFont(R.font.pretendard_bold)
    private val dateTime = SimpleDateFormat(activity.getString(R.string.trips_date_format), Locale.KOREA)
    private val dateShort = SimpleDateFormat(activity.getString(R.string.trips_date_short_format), Locale.KOREA)

    private var source: RecordsDataSource? = null
    private var available = false
    private var disposed = false
    private var epoch = 0L
    private var read = 0L
    private var loaded = false
    private var failed = false
    private var active = false
    private var atlas: RecordsAtlasSnapshot? = null
    private var regionStories = emptyMap<String, List<RecordsStory>>()
    private var routes = emptyList<MapRoute>()
    private var tripEnds = emptyMap<String, Pair<MapCoordinate, MapCoordinate>>()
    private var geometry = emptyList<DisplayRegionShape>()
    private var locator: RegionLocator? = null
    private var tab = Tab.REGIONS
    private var selectedRegion: String? = null
    private var selectedTrip: String? = null
    private var tripSnapshot: RecordsTripSnapshot? = null
    private var replayingVersion: String? = null
    private var startedAt = 0L

    init {
        val frame = activity.findViewById<FrameLayout>(R.id.trips_map_frame)
        frame.setBackgroundResource(R.drawable.bg_card)
        frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, resources.getDimension(R.dimen.radius_card))
            }
        }
        frame.addView(map, 0, FrameLayout.LayoutParams(-1, -1))
        map.onStatusChanged = { status ->
            attribution.setText(if (status == JourneyMapStatus.OFFLINE || status == JourneyMapStatus.UNAVAILABLE)
                R.string.trips_map_offline else R.string.trips_map_attribution)
        }
        tabRegions.setOnClickListener { selectTab(Tab.REGIONS) }
        tabJourneys.setOnClickListener { selectTab(Tab.JOURNEYS) }
        renderList()
    }

    /** Screen entry and source changes both land here; stale callbacks are fenced by [epoch]/[read]. */
    fun update(value: RecordsDataSource?, allowed: Boolean) {
        if (disposed) return
        val usable = allowed && value != null
        if (source === value && available == usable) return
        epoch++; read++; source = value; available = usable
        atlas = null; regionStories = emptyMap(); routes = emptyList(); tripEnds = emptyMap(); loaded = false; failed = false
        selectedRegion = null; selectedTrip = null; tripSnapshot = null; tab = Tab.REGIONS
        renderHome(); renderList(); updateScene()
        if (usable) { loadGeometry(); refresh() }
    }

    /** Re-read the committed history (called on every screen entry; there is no refresh button). */
    fun refresh() {
        val current = source?.takeIf { available && !disposed } ?: return
        val serial = ++read
        val token = epoch
        failed = false
        startedAt = android.os.SystemClock.elapsedRealtime()
        observe(current.readAtlas(0), serial, token) { snapshot ->
            if (BuildConfig.DEBUG) android.util.Log.i("PetasosTrips", "atlas read ${android.os.SystemClock.elapsedRealtime() - startedAt}ms, paths=${snapshot.paths.size} samples=${snapshot.paths.sumOf { it.points.size }} truncated=${snapshot.routeSamplesTruncated}")
            val collected = snapshot.collectedRegions
            val pending = HashMap<String, List<RecordsStory>>()
            if (collected.isEmpty()) { accept(snapshot, pending); return@observe }
            var remaining = collected.size
            collected.forEach { proof ->
                observe(current.readRegion(proof.regionId), serial, token, onFailure = {
                    if (--remaining == 0) accept(snapshot, pending)
                }) { region ->
                    pending[proof.regionId] = region.stories
                    if (--remaining == 0) accept(snapshot, pending)
                }
            }
        }
    }

    private fun accept(snapshot: RecordsAtlasSnapshot, stories: Map<String, List<RecordsStory>>) {
        val token = epoch
        // Thinning is display-only and runs off the main thread: the atlas can carry 30,000 samples.
        worker.execute {
            val thinned = snapshot.paths.mapIndexedNotNull { index, path ->
                val points = RouteSimplifier.thin(path.points)
                if (points.size < 2) null else MapRoute(path.tripId + "#" + index, points, true) to path.tripId
            }
            if (BuildConfig.DEBUG) android.util.Log.i("PetasosTrips", "thinned ${snapshot.paths.sumOf { it.points.size }} -> ${thinned.sumOf { it.first.points.size }} points in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms since refresh")
            val ends = snapshot.paths.groupBy { it.tripId }.mapValues { (_, paths) ->
                paths.first().points.first() to paths.last().points.last()
            }
            activity.runOnUiThread {
                if (disposed || token != epoch) return@runOnUiThread
                atlas = snapshot; regionStories = stories; loaded = true
                tripKeys = thinned.associate { it.first.key to it.second }
                routes = thinned.map { it.first }
                tripEnds = ends
                renderHome(); renderList(); updateScene()
            }
        }
    }

    private var tripKeys = emptyMap<String, String>()

    fun setActive(value: Boolean) {
        if (disposed || active == value) return
        active = value
        if (value) { map.onStart(); map.onResume(); updateScene() } else { map.onPause(); map.onStop() }
    }

    fun onLowMemory() = map.onLowMemory()
    fun saveTransientState() = map.saveTransientState()

    fun dispose() {
        if (disposed) return
        update(null, false)
        setActive(false)
        map.onDestroy()
        disposed = true
        worker.shutdownNow()
    }

    // ---------------------------------------------------------------- data plumbing

    private fun loadGeometry() {
        if (geometry.isNotEmpty()) return
        val token = epoch
        worker.execute {
            val values = try { RecordsGeometryCatalog.load(activity.applicationContext) } catch (_: Exception) { emptyList() }
            val finder = if (values.isEmpty()) null else RegionLocator(values)
            activity.runOnUiThread {
                if (disposed || !available || token != epoch) return@runOnUiThread
                geometry = values; locator = finder
                map.submitGeometry(values)
                renderList(); updateScene()
            }
        }
    }

    private fun <T> observe(future: ListenableFuture<T>, serial: Long, token: Long, onFailure: () -> Unit = { failed = true; renderList() }, accept: (T) -> Unit) {
        future.addListener({
            if (disposed || !available || token != epoch || serial != read) return@addListener
            try { accept(future.get()) } catch (_: Exception) { onFailure() }
        }, activity.mainExecutor)
    }

    private fun selectTab(next: Tab) {
        if (tab == next) return
        tab = next; selectedRegion = null; selectedTrip = null; tripSnapshot = null
        renderList(); updateScene()
    }

    private fun toggleRegion(id: String) {
        selectedRegion = if (selectedRegion == id) null else id
        renderList(); updateScene()
    }

    private fun toggleTrip(id: String) {
        if (selectedTrip == id) { selectedTrip = null; tripSnapshot = null; renderList(); updateScene(); return }
        selectedTrip = id; tripSnapshot = null
        renderList(); updateScene()
        val current = source?.takeIf { available } ?: return
        val token = epoch
        val request = current.readTrip(id)
        request.addListener({
            if (disposed || !available || token != epoch || selectedTrip != id) return@addListener
            try { tripSnapshot = request.get() } catch (_: Exception) { /* Row simply shows no summary. */ }
            renderList()
        }, activity.mainExecutor)
    }

    // ---------------------------------------------------------------- map

    private fun updateScene() {
        if (disposed) return
        val trip = selectedTrip
        val region = selectedRegion?.let { id -> geometry.firstOrNull { it.regionId == id } }
        val shown = routes.map { route ->
            route.copy(emphasized = trip == null || tripKeys[route.key] == trip)
        }
        val focus = when {
            trip != null -> shown.filter { it.emphasized }.flatMap { it.points }
            region != null -> region.rings.flatten()
            else -> shown.flatMap { it.points }
        }
        map.setScene(MapScene(shown, if (trip == null) region?.rings.orEmpty() else emptyList(), focus, if (trip == null) region?.regionId else null))
    }

    // ---------------------------------------------------------------- home summary

    private fun renderHome() {
        val data = atlas
        val regions = data?.collectedRegions?.size ?: 0
        val stories = regionStories.values.flatten().map { it.versionId }.toSet().size
        if (data == null || (regions == 0 && stories == 0 && data.trips.isEmpty())) {
            homeSummary.setText(R.string.home_trips_empty)
            homeLatest.visibility = View.GONE
            return
        }
        val text = SpannableStringBuilder()
        fun number(value: Int) {
            val start = text.length
            text.append(value.toString())
            text.setSpan(AbsoluteSizeSpan(resources.getDimensionPixelSize(R.dimen.text_display)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            boldFont?.let { text.setSpan(TypefaceSpan(it), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        number(regions); text.append("곳 · 이야기 "); number(stories); text.append("편")
        homeSummary.text = text
        val latest = data.trips.maxOfOrNull { it.startedAt }
        homeLatest.visibility = if (latest == null) View.GONE else View.VISIBLE
        if (latest != null) homeLatest.text = activity.getString(R.string.home_trips_latest, dateShort.format(Date(latest)))
    }

    // ---------------------------------------------------------------- right panel

    private fun renderList() {
        if (disposed) return
        tabRegions.isSelected = tab == Tab.REGIONS
        tabJourneys.isSelected = tab == Tab.JOURNEYS
        list.removeAllViews()
        val data = atlas
        when {
            !available -> message(R.string.trips_unavailable)
            failed && data == null -> message(R.string.trips_failed)
            data == null || !loaded -> message(R.string.trips_loading)
            tab == Tab.REGIONS -> renderRegions(data)
            else -> renderJourneys(data)
        }
        scroll.scrollTo(0, 0)
    }

    private fun renderRegions(data: RecordsAtlasSnapshot) {
        val ordered = data.collectedRegions.sortedBy { it.earnedAt }
        val entries = ordered.mapNotNull { proof -> geometry.firstOrNull { it.regionId == proof.regionId } }
        if (entries.isEmpty()) { message(R.string.trips_empty); return }
        entries.forEachIndexed { index, shape ->
            val stories = regionStories[shape.regionId].orEmpty()
            val selected = selectedRegion == shape.regionId
            val row = row(selected) { toggleRegion(shape.regionId) }
            row.addView(RegionSilhouetteView(activity).apply {
                submit(shape, true); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(R.style.Text_Title, RegionLocator.displayName(shape)))
            texts.addView(text(R.style.Text_Caption, activity.getString(R.string.trips_stories_count, stories.size)))
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(20) })
            list.addView(row)
            if (selected) addStories(stories)
            if (index < entries.lastIndex) list.addView(divider())
        }
    }

    private fun addStories(stories: List<RecordsStory>) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.primary_wash)
            setPadding(dp(24), 0, dp(24), dp(20))
        }
        if (stories.isEmpty()) box.addView(text(R.style.Text_Body, activity.getString(R.string.trips_no_stories)).apply { setPadding(0, dp(4), 0, dp(8)) })
        stories.forEach { story ->
            val title = source?.storyTitle(story.versionId)
            val line = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val labels = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(R.style.Text_Body, title ?: activity.getString(R.string.trips_story_unavailable)))
            labels.addView(text(R.style.Text_Caption, dateTime.format(Date(story.earnedAt))))
            line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (title != null) {
                val replaying = replayingVersion == story.versionId
                line.addView(Button(activity, null, 0, R.style.Widget_Petasos_Button_Secondary).apply {
                    setText(if (replaying) R.string.trips_replaying else R.string.trips_replay)
                    isEnabled = !replaying
                    minHeight = dp(64); minimumHeight = dp(64)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_label))
                    setOnClickListener {
                        if (!available || source?.storyTitle(story.versionId) == null) return@setOnClickListener
                        replayingVersion = story.versionId
                        source?.replayStory(story.versionId)
                        renderList()
                        postDelayed({ if (!disposed && replayingVersion == story.versionId) { replayingVersion = null; renderList() } }, 4_000)
                    }
                }, LinearLayout.LayoutParams(-2, dp(64)).apply { marginStart = dp(16) })
            }
            box.addView(line)
        }
        list.addView(box)
    }

    private fun renderJourneys(data: RecordsAtlasSnapshot) {
        if (data.trips.isEmpty()) { message(R.string.trips_empty); return }
        data.trips.forEachIndexed { index, header ->
            val selected = selectedTrip == header.tripId
            val row = row(selected) { toggleTrip(header.tripId) }
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(R.style.Text_Title, dateTime.format(Date(header.startedAt))))
            range(header)?.let { texts.addView(text(R.style.Text_Body, it)) }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(R.style.Text_Caption, duration(header)).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
            list.addView(row)
            if (selected) addSummary()
            if (index < data.trips.lastIndex) list.addView(divider())
        }
    }

    private fun addSummary() {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.color.primary_wash)
            setPadding(dp(24), 0, dp(24), dp(20))
        }
        box.addView(text(R.style.Text_Label, activity.getString(R.string.trips_journey_summary_title)).apply { setPadding(0, dp(4), 0, dp(8)) })
        val summaries = tripSnapshot?.results?.savedSummaries?.items.orEmpty()
        if (summaries.isEmpty()) box.addView(text(R.style.Text_Body, activity.getString(R.string.trips_journey_no_summary)))
        summaries.forEach { box.addView(text(R.style.Text_Body, it.summaryText).apply { setPadding(0, 0, 0, dp(8)) }) }
        list.addView(box)
    }

    private fun range(header: TripResultHeader): String? {
        val ends = tripEnds[header.tripId] ?: return null
        val finder = locator ?: return null
        val from = finder.findOrNearest(ends.first)?.let(RegionLocator::displayName)
        val to = finder.findOrNearest(ends.second)?.let(RegionLocator::displayName)
        return when {
            from == null && to == null -> null
            from == null || to == null || from == to -> from ?: to
            else -> activity.getString(R.string.trips_journey_range, from, to)
        }
    }

    private fun duration(header: TripResultHeader): String {
        val end = header.endedAt ?: header.lastObservedAt ?: return activity.getString(R.string.trips_journey_active)
        val minutes = ((end - header.startedAt) / 60_000L).toInt()
        return when {
            minutes < 1 -> activity.getString(R.string.trips_duration_seconds)
            minutes < 60 -> activity.getString(R.string.trips_duration_minutes, minutes)
            minutes % 60 == 0 -> activity.getString(R.string.trips_duration_hours_only, minutes / 60)
            else -> activity.getString(R.string.trips_duration_hours, minutes / 60, minutes % 60)
        }
    }

    // ---------------------------------------------------------------- view helpers

    private fun message(id: Int) {
        list.addView(text(R.style.Text_Body, activity.getString(id)).apply { setTextColor(activity.getColor(R.color.text_sub)); setPadding(dp(32), dp(32), dp(32), dp(32)) })
    }

    private fun row(selected: Boolean, click: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(96)
        setPadding(dp(24), dp(16), dp(24), dp(16))
        setBackgroundResource(R.drawable.bg_row)
        isSelected = selected
        isClickable = true; isFocusable = true
        setOnClickListener { if (available) click() }
    }

    private fun text(style: Int, value: String) = TextView(activity, null, 0, style).apply { text = value }
    private fun divider() = View(activity).apply {
        setBackgroundColor(activity.getColor(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(24); marginEnd = dp(24) }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
