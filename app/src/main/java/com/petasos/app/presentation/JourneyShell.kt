package com.petasos.app.presentation

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.petasos.app.BuildConfig
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.presentation.records.CurrentRegionLabel
import com.petasos.app.presentation.records.RecordsDataSource
import com.petasos.app.presentation.records.TripsScreen

/** Page navigation and display only. Consent, playback and persistence remain with their owners. */
class JourneyShell(private val activity: Activity, private val onPageChanged: () -> Unit) {
    private enum class Page(val viewId: Int, val parent: Page?) {
        HOME(R.id.page_home, null),
        TRIPS(R.id.page_trips, null),
        SETTINGS(R.id.page_settings, null),
        INTERESTS(R.id.page_interests, null),
        SOURCES(R.id.page_sources, null),
        ABOUT(R.id.page_about, null),
        ONBOARD_INFO(R.id.page_onboarding_info, null),
        ONBOARD_INTERESTS(R.id.page_onboarding_interests, null),
    }

    private val preferences = activity.getSharedPreferences("journey_presentation", Activity.MODE_PRIVATE)
    private val trips = TripsScreen(activity)
    private var page = if (preferences.getBoolean(KEY_ONBOARDED, false)) Page.HOME else Page.ONBOARD_INFO
    private var source: RecordsDataSource? = null
    private var sourceHost: PetasosSessionHost? = null
    private var host: PetasosSessionHost? = null
    private var allowed = false
    private var disposed = false
    private var companionEnabled = false
    private var companionStopping = false
    private var companionNotice: String? = null
    private var resetArmedAt = 0L
    private var regionLabel: CurrentRegionLabel? = null
    private var regionName: String? = null
    var onCompanionToggle: (() -> Unit)? = null
        set(value) { field = value; renderHome() }
    var onReturnToNavigation: (() -> Unit)? = null
    var onOnboardingContinue: (() -> Unit)? = null
    /** Home readiness row "설정 필요": asks for that one permission (or explains the overlay setting). */
    var onFixReadiness: ((ReadyItem) -> Unit)? = null
    enum class ReadyItem { LOCATION, MIC, OVERLAY }
    private val nowPlaying = object : Runnable {
        override fun run() {
            if (disposed) return
            renderHome()
            if (companionEnabled && page == Page.HOME) activity.window.decorView.postDelayed(this, NOW_PLAYING_POLL_MS)
        }
    }

    init {
        bind(R.id.home_primary) {
            if (companionEnabled) onReturnToNavigation?.invoke() else onCompanionToggle?.invoke()
        }
        bind(R.id.home_stop) { onCompanionToggle?.invoke() }
        bind(R.id.home_trips_card) { go(Page.TRIPS) }
        bind(R.id.home_settings) { go(Page.SETTINGS) }
        bind(R.id.trips_settings) { go(Page.SETTINGS) }
        bind(R.id.trips_back) { go(Page.HOME) }
        bind(R.id.settings_back) { go(Page.HOME) }
        bind(R.id.settings_row_interests) { go(Page.INTERESTS) }
        bind(R.id.settings_row_sources) { go(Page.SOURCES) }
        bind(R.id.settings_row_about) { go(Page.ABOUT) }
        bind(R.id.interests_back) { go(Page.SETTINGS) }
        bind(R.id.sources_back) { go(Page.SETTINGS) }
        bind(R.id.about_back) { go(Page.SETTINGS) }
        bind(R.id.onboarding_continue) { onOnboardingContinue?.invoke(); go(Page.ONBOARD_INTERESTS) }
        bind(R.id.onboarding_skip) { finishOnboarding() }
        bind(R.id.settings_row_reset) { resetListening() }
        bind(R.id.home_ready_location_state) { if (!ready(ReadyItem.LOCATION)) onFixReadiness?.invoke(ReadyItem.LOCATION) }
        bind(R.id.home_ready_mic_state) { if (!ready(ReadyItem.MIC)) onFixReadiness?.invoke(ReadyItem.MIC) }
        bind(R.id.home_ready_overlay_state) { if (!ready(ReadyItem.OVERLAY)) onFixReadiness?.invoke(ReadyItem.OVERLAY) }
        activity.findViewById<TextView>(R.id.about_version).text = activity.getString(R.string.about_version, BuildConfig.VERSION_NAME)
        val demoRows = if (BuildConfig.DEMO) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.settings_row_reset).visibility = demoRows
        activity.findViewById<View>(R.id.settings_reset_divider).visibility = demoRows
        renderPages(); renderHome()
    }

    val sourcesVisible: Boolean get() = allowed && page == Page.SOURCES

    fun update(value: PetasosSessionHost?, available: Boolean, titleForStory: (String) -> String?, replay: (String) -> Unit) {
        if (disposed) return
        if (value !== host || allowed != available) host?.setCaptionVisible(false)
        host = value; allowed = available
        if (sourceHost !== value) {
            sourceHost = value
            source = value?.let { current -> object : RecordsDataSource {
                override fun readAtlas(tripOffset: Int) = current.readRecordsAtlas(tripOffset)
                override fun readRegion(regionId: String) = current.readRecordsRegion(regionId)
                override fun readTrip(tripId: String) = current.readRecordsTripMap(tripId)
                override fun storyTitle(versionId: String) = titleForStory(versionId)
                override fun replayStory(versionId: String) { if (allowed && host === current) replay(versionId) }
            } }
        }
        listOf(R.id.home_settings, R.id.home_trips_card, R.id.trips_settings, R.id.trips_back, R.id.settings_back,
            R.id.settings_row_interests, R.id.settings_row_sources, R.id.settings_row_about, R.id.interests_back,
            R.id.sources_back, R.id.about_back, R.id.onboarding_continue, R.id.onboarding_skip,
        ).forEach { activity.findViewById<View>(it).isEnabled = available }
        trips.update(source, available)
        trips.setActive(available && page == Page.TRIPS)
        syncRegionLabel()
        renderHome()
        updateReset()
    }

    /** Called once interests were stored (true) or storing failed (false). */
    fun interestsSaved(stored: Boolean = true) {
        if (disposed) return
        when (page) {
            Page.ONBOARD_INTERESTS -> {
                if (stored) finishOnboarding() else notice(R.id.onboarding_notice, R.string.interests_failed)
            }
            Page.INTERESTS -> {
                if (stored) go(Page.SETTINGS) else notice(R.id.interests_notice, R.string.interests_failed)
            }
            else -> Unit
        }
    }

    /** Actual control owner supplies state; a tap never optimistically paints the app as ON. */
    fun updateCompanionState(enabled: Boolean, stopping: Boolean, notice: String? = null) {
        if (disposed) return
        val turnedOn = enabled && !companionEnabled
        // OFF ends the trip: re-read the records so the Home summary and map include it.
        val ended = !enabled && !stopping && (companionEnabled || companionStopping)
        companionEnabled = enabled; companionStopping = stopping; companionNotice = notice
        syncRegionLabel()
        renderHome()
        if (turnedOn) { activity.window.decorView.removeCallbacks(nowPlaying); nowPlaying.run() }
        if (ended) trips.refresh()
        updateReset()
    }

    private fun syncRegionLabel() {
        val wanted = if (allowed && companionEnabled && !companionStopping) host else null
        if (regionLabel?.host === wanted) return
        regionLabel?.stop(); regionLabel = null
        if (wanted == null) { regionName = null; return }
        regionLabel = CurrentRegionLabel(activity.applicationContext, wanted) { name -> regionName = name; renderHome() }.also { it.start() }
    }

    fun showHome() { if (!disposed) go(Page.HOME) }
    fun showSettings() { if (!disposed) go(Page.SETTINGS) }

    private fun renderHome() {
        if (disposed) return
        val primary = activity.findViewById<Button>(R.id.home_primary)
        val stop = activity.findViewById<Button>(R.id.home_stop)
        val ready = allowed && onCompanionToggle != null && !companionStopping
        primary.isEnabled = ready
        stop.isEnabled = ready
        stop.visibility = if (companionEnabled || companionStopping) View.VISIBLE else View.GONE
        primary.setText(when {
            companionStopping -> R.string.home_stopping
            companionEnabled -> R.string.home_return_navigation
            else -> R.string.home_start
        })
        val on = companionEnabled || companionStopping
        activity.findViewById<View>(R.id.home_off_group).visibility = if (on) View.GONE else View.VISIBLE
        activity.findViewById<View>(R.id.home_on_group).visibility = if (on) View.VISIBLE else View.GONE
        if (on) renderOn() else renderReadiness()
        activity.findViewById<TextView>(R.id.home_notice).apply {
            text = companionNotice.orEmpty()
            visibility = if (companionNotice.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    fun ready(item: ReadyItem): Boolean = when (item) {
        ReadyItem.LOCATION -> com.petasos.app.platform.android.location.LocationAccess.hasRequiredPermissions(activity)
        ReadyItem.MIC -> activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ReadyItem.OVERLAY -> com.petasos.app.platform.pleos.navigation.PleosCompanionDisplaySupport.inspect(activity).navigationSurfaceAvailable
    }

    private fun renderReadiness() {
        listOf(ReadyItem.LOCATION to R.id.home_ready_location_state, ReadyItem.MIC to R.id.home_ready_mic_state,
            ReadyItem.OVERLAY to R.id.home_ready_overlay_state).forEach { (item, id) ->
            val ok = ready(item)
            activity.findViewById<TextView>(id).apply {
                setText(if (ok) R.string.home_ready_ok else R.string.home_ready_fix)
                setTextColor(activity.getColor(if (ok) R.color.positive else R.color.primary))
                val check = if (ok) activity.getDrawable(R.drawable.ic_check)?.mutate()?.apply {
                    setTint(activity.getColor(R.color.positive)); setBounds(0, 0, dp(28), dp(28))
                } else null
                setCompoundDrawablesRelative(check, null, null, null)
                setBackgroundResource(if (ok) 0 else R.drawable.bg_button_secondary)
                isClickable = !ok
            }
        }
    }

    /** Region big, then what is playing; before the region is confirmed only the waiting line. */
    private fun renderOn() {
        val current = host
        val snapshot = current?.snapshot()
        val mode = snapshot?.mode
        val region = regionName
        activity.findViewById<TextView>(R.id.home_on_region).apply {
            text = region.orEmpty(); visibility = if (region == null) View.GONE else View.VISIBLE
        }
        val kind: Int?; val title: String?; val icon: Int
        when (mode) {
            com.petasos.app.playback.PlaybackMode.STORY, com.petasos.app.playback.PlaybackMode.PAUSED -> {
                kind = R.string.home_on_story; title = current?.selectedTitle(); icon = R.drawable.ic_menu_book
            }
            com.petasos.app.playback.PlaybackMode.MUSIC -> {
                val assetId = snapshot.musicCheckpoint?.track?.assetId
                kind = R.string.home_on_music; icon = R.drawable.ic_music_note
                title = assetId?.let { listOf(current.musicTitle(it), current.musicArtist(it)).filter(String::isNotBlank).joinToString(" · ") }
            }
            com.petasos.app.playback.PlaybackMode.IDLE, null -> { kind = null; title = null; icon = R.drawable.ic_menu_book }
            else -> { kind = R.string.home_on_talk; title = current?.lastHeardText(); icon = R.drawable.ic_chat_bubble }
        }
        val waiting = region == null && kind == null
        activity.findViewById<View>(R.id.home_on_waiting).visibility = if (waiting) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.home_on_now).visibility = if (kind == null) View.GONE else View.VISIBLE
        if (kind != null) {
            activity.findViewById<android.widget.ImageView>(R.id.home_on_now_icon).setImageResource(icon)
            activity.findViewById<TextView>(R.id.home_on_now_kind).setText(kind)
            activity.findViewById<TextView>(R.id.home_on_now_title).text = title.orEmpty()
        }
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private fun updateReset() {
        val row = activity.findViewById<View>(R.id.settings_row_reset)
        row.isEnabled = allowed && !companionEnabled && !companionStopping
        row.alpha = if (row.isEnabled) 1f else 0.45f
    }

    private fun resetListening() {
        if (!BuildConfig.DEMO || !allowed || companionEnabled || companionStopping) {
            if (companionEnabled) notice(R.id.settings_notice, R.string.settings_reset_busy)
            return
        }
        val desc = activity.findViewById<TextView>(R.id.settings_reset_desc)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - resetArmedAt > 5_000) {
            resetArmedAt = now
            desc.setText(R.string.settings_reset_confirm)
            desc.setTextColor(activity.getColor(R.color.negative))
            desc.postDelayed({ if (!disposed) { resetArmedAt = 0; desc.setText(R.string.settings_reset_desc); desc.setTextColor(activity.getColor(R.color.text_sub)) } }, 5_000)
            return
        }
        resetArmedAt = 0
        desc.setText(R.string.settings_reset_desc)
        desc.setTextColor(activity.getColor(R.color.text_sub))
        com.petasos.app.storage.DemoListeningHistory.reset(activity.applicationContext) { ok ->
            activity.runOnUiThread {
                if (disposed) return@runOnUiThread
                notice(R.id.settings_notice, if (ok) R.string.settings_reset_done else R.string.settings_reset_failed, error = !ok)
                if (ok) trips.refresh()
            }
        }
    }

    private fun notice(id: Int, text: Int, error: Boolean = true) {
        activity.findViewById<TextView>(id).apply {
            setText(text)
            setTextColor(activity.getColor(if (error) R.color.negative else R.color.text_sub))
            visibility = View.VISIBLE
        }
    }

    fun onLowMemory() = trips.onLowMemory()
    fun saveTransientState() = trips.saveTransientState() // No live text or map camera enters Activity saved state.

    fun dispose() {
        if (disposed) return
        allowed = false
        regionLabel?.stop(); regionLabel = null
        activity.window.decorView.removeCallbacks(nowPlaying)
        host?.setCaptionVisible(false); host = null; sourceHost = null; source = null
        trips.dispose()
        disposed = true
    }

    private fun bind(id: Int, action: () -> Unit) {
        activity.findViewById<View>(id).setOnClickListener { if (allowed) action() }
    }

    private fun finishOnboarding() {
        preferences.edit().putBoolean(KEY_ONBOARDED, true).apply()
        go(Page.HOME)
    }

    private fun go(next: Page) {
        if (page == next) return
        page = next
        renderPages()
        trips.setActive(allowed && page == Page.TRIPS)
        if (page == Page.TRIPS || page == Page.HOME) trips.refresh()
        if (page == Page.HOME && companionEnabled) { activity.window.decorView.removeCallbacks(nowPlaying); nowPlaying.run() }
        listOf(R.id.interests_notice, R.id.onboarding_notice, R.id.settings_notice).forEach { activity.findViewById<View>(it).visibility = View.GONE }
        onPageChanged()
    }

    private fun renderPages() {
        Page.values().forEach { activity.findViewById<View>(it.viewId).visibility = if (it == page) View.VISIBLE else View.GONE }
    }

    private companion object { const val KEY_ONBOARDED = "onboarded"; const val NOW_PLAYING_POLL_MS = 1_000L }
}
