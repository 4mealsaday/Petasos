package com.petasos.app.presentation

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.text.InputFilter
import android.widget.EditText
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.CheckBox
import android.widget.TextView
import com.petasos.app.R
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import com.petasos.app.playback.PlaybackMode
import com.petasos.app.capability.navigation.NavigationScreenCommand
import com.petasos.app.capability.navigation.NavigationScreenCommands
import com.petasos.app.capability.navigation.NavigationScreenCommandState
import com.petasos.app.platform.pleos.navigation.PleosNavigationScreens
import com.petasos.app.presentation.records.*

/** Page navigation and display only. Consent, playback and persistence remain with their owners. */
class JourneyShell(private val activity: Activity, private val onPageChanged: () -> Unit) {
    private enum class Page { HOME, RECORDS, SETTINGS }
    private val preferences = activity.getSharedPreferences("journey_presentation", Activity.MODE_PRIVATE)
    private var page = Page.HOME
    private val records = RecordsPanel(activity)
    private val caption = activity.findViewById<TextView>(R.id.journey_caption)
    private val title = activity.findViewById<TextView>(R.id.journey_current_title)
    private val navigationNotice = activity.findViewById<TextView>(R.id.journey_navigation_notice)
    private val captionSwitch = activity.findViewById<CheckBox>(R.id.journey_caption_switch)
    private var source: RecordsDataSource? = null
    private var sourceHost: PetasosSessionHost? = null
    private var host: PetasosSessionHost? = null
    private var allowed = false
    private var disposed = false
    private var searchDialog: AlertDialog? = null
    private var optionsExpanded = false
    private var companionEnabled = false
    private var companionStopping = false
    private var companionNotice: String? = null
    var onCompanionToggle: (() -> Unit)? = null
        set(value) { field = value; renderHome() }
    private val navigation = NavigationScreenCommands(
        permissionGranted = { activity.checkSelfPermission(PleosNavigationScreens.PERMISSION) == PackageManager.PERMISSION_GRANTED },
        createGateway = { PleosNavigationScreens(activity.applicationContext) },
        onState = { state -> navigationNotice.setText(when (state) {
            NavigationScreenCommandState.IDLE -> R.string.journey_navigation_hint
            NavigationScreenCommandState.CONNECTING -> R.string.journey_navigation_connecting
            NavigationScreenCommandState.REQUESTED -> R.string.journey_navigation_requested
            NavigationScreenCommandState.UNAVAILABLE -> R.string.journey_navigation_unavailable
            NavigationScreenCommandState.PERMISSION_DENIED -> R.string.journey_navigation_permission
        }) },
    )

    init {
        caption.movementMethod = android.text.method.ScrollingMovementMethod()
        activity.findViewById<FrameLayout>(R.id.journey_records_container).addView(records,
            FrameLayout.LayoutParams(-1, -1))
        captionSwitch.isChecked = preferences.getBoolean("captions", true)
        captionSwitch.setOnCheckedChangeListener { _, checked ->
            savePresentationFlag("captions", checked)
            renderContent()
        }
        bind(R.id.journey_open_navigation) { navigation.request(NavigationScreenCommand.Foreground) }
        bind(R.id.journey_search_destination) { searchDestination() }
        bind(R.id.journey_show_route) { navigation.request(NavigationScreenCommand.CurrentRoute) }
        bindHomeIcon(R.id.journey_companion_toggle, R.drawable.ic_petasos)
        bindHomeIcon(R.id.journey_nav_records, R.drawable.ic_journey_album)
        bind(R.id.journey_companion_toggle) { onCompanionToggle?.invoke() }
        bind(R.id.journey_nav_listen) { go(Page.HOME) }
        bind(R.id.journey_nav_records) { go(Page.RECORDS) }
        bind(R.id.journey_nav_settings) { go(Page.SETTINGS) }
        bind(R.id.journey_playback_options) {
            optionsExpanded = !optionsExpanded
            renderPages(); renderContent(); updateNavigationVisibility()
        }
        renderPages(); renderHome()
    }

    val settingsVisible: Boolean get() = allowed && page == Page.SETTINGS

    fun update(value: PetasosSessionHost?, available: Boolean, titleForStory: (String) -> String?, replay: (String) -> Unit) {
        if (disposed) return
        if (value !== host || allowed != available) {
            host?.setCaptionVisible(false)
        }
        host = value; allowed = available
        if (sourceHost !== value) {
            sourceHost = value
            source = value?.let { current -> object : RecordsDataSource {
                override fun readAtlas(tripOffset: Int) = current.readRecordsAtlas(tripOffset)
                override fun readRegion(regionId: String) = current.readRecordsRegion(regionId)
                override fun readTrip(tripId: String) = current.readRecordsTripMap(tripId)
                override fun storyTitle(versionId: String) = titleForStory(versionId)
                override fun replayStory(versionId: String) { if (allowed && host === current) { openAdvancedPlayback(); replay(versionId) } }
            } }
        }
        listOf(R.id.journey_nav_listen, R.id.journey_nav_records, R.id.journey_nav_settings,
            R.id.journey_playback_options, R.id.journey_caption_switch, R.id.journey_open_navigation,
            R.id.journey_search_destination, R.id.journey_show_route).forEach { activity.findViewById<View>(it).isEnabled = available }
        records.update(source, available && page == Page.RECORDS)
        renderHome(); renderContent()
        updateNavigationVisibility()
    }

    // Saving interests keeps the user in Settings; there is no introductory page or implicit start.
    fun interestsSaved() = Unit

    /** Actual control owner supplies state; a tap never optimistically paints the app as ON. */
    fun updateCompanionState(enabled: Boolean, stopping: Boolean, notice: String? = null) {
        if (disposed) return
        companionEnabled = enabled; companionStopping = stopping; companionNotice = notice
        renderHome()
    }

    fun showHome() { if (!disposed) go(Page.HOME) }
    fun showSettings() { if (!disposed) go(Page.SETTINGS) }

    private fun renderHome() {
        if (disposed) return
        activity.findViewById<Button>(R.id.journey_companion_toggle).apply {
            isEnabled = allowed && onCompanionToggle != null && !companionStopping
            isSelected = companionEnabled
            setText(when {
                companionStopping -> R.string.journey_home_stopping
                companionEnabled -> R.string.journey_home_on
                else -> R.string.journey_home_off
            })
        }
        activity.findViewById<TextView>(R.id.journey_home_notice).apply {
            text = companionNotice.orEmpty()
            visibility = if (companionNotice.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun openAdvancedPlayback() {
        optionsExpanded = true
        if (page != Page.SETTINGS) go(Page.SETTINGS)
        else { renderPages(); renderContent(); updateNavigationVisibility() }
        activity.findViewById<ScrollView>(R.id.journey_settings_page).post {
            if (!disposed && page == Page.SETTINGS && optionsExpanded)
                activity.findViewById<View>(R.id.journey_listen_page).requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, 1, 1), false)
        }
    }

    fun onLowMemory() = Unit
    fun saveTransientState() = Unit // No search text or live caption enters Activity saved state.

    fun dispose() {
        if (disposed) return
        allowed = false; updateNavigationVisibility()
        host?.setCaptionVisible(false); host = null; sourceHost = null; source = null
        caption.text = ""; title.text = ""
        records.dispose(); navigation.close()
        disposed = true
    }

    @android.annotation.SuppressLint("UseCompatLoadingForDrawables") // Native Activity and API 28+ framework vectors; no AppCompat theme or demo dependency.
    private fun bindHomeIcon(buttonId: Int, drawableId: Int) {
        val size = activity.resources.getDimensionPixelSize(R.dimen.journey_home_icon_size)
        val icon = activity.getDrawable(drawableId)?.mutate()?.apply { setBounds(0, 0, size, size) }
        activity.findViewById<Button>(buttonId).setCompoundDrawables(null, icon, null, null)
    }

    private fun bind(id: Int, action: () -> Unit) {
        activity.findViewById<View>(id).setOnClickListener { if (allowed) action() }
    }

    // Native Activity/shared presentation has no required KTX dependency. Keep asynchronous apply.
    @android.annotation.SuppressLint("UseKtx")
    private fun savePresentationFlag(key: String, value: Boolean) { preferences.edit().putBoolean(key, value).apply() }

    private fun go(next: Page) {
        if (page == next) return
        page = next
        renderPages(); renderContent(); updateNavigationVisibility()
        records.update(source, allowed && page == Page.RECORDS)
        onPageChanged()
    }

    private fun renderPages() {
        fun visible(id: Int, show: Boolean) { activity.findViewById<View>(id).visibility = if (show) View.VISIBLE else View.GONE }
        visible(R.id.journey_home_page, page == Page.HOME)
        visible(R.id.journey_records_container, page == Page.RECORDS)
        visible(R.id.journey_settings_page, page == Page.SETTINGS)
        visible(R.id.journey_nav_listen, page != Page.HOME)
        visible(R.id.journey_nav_settings, page == Page.RECORDS)
        visible(R.id.journey_playback_options_body, optionsExpanded)
        activity.findViewById<Button>(R.id.journey_playback_options).setText(
            if (optionsExpanded) R.string.journey_playback_options_hide else R.string.journey_playback_options)
        activity.findViewById<Button>(R.id.records_save_keywords).setText(R.string.records_keywords_save)
        activity.findViewById<ScrollView>(R.id.journey_settings_page).scrollTo(0, 0)
    }

    private fun renderContent() {
        val current = host
        val visible = allowed && page == Page.SETTINGS && optionsExpanded
        current?.setCaptionVisible(visible && captionSwitch.isChecked)
        if (!visible) { caption.text = ""; title.text = ""; return }
        val state = current?.snapshot()
        title.text = when {
            state?.closed != false -> ""
            state.mode == PlaybackMode.MUSIC -> state.musicCheckpoint?.track?.assetId?.let {
                activity.getString(R.string.journey_title_music, current?.musicTitle(it).orEmpty(), current?.musicArtist(it).orEmpty())
            }.orEmpty()
            state.mode == PlaybackMode.STORY -> current?.selectedTitle().orEmpty()
            else -> ""
        }
        val currentCaption = current?.currentCaption()
        val captionText = when {
            !captionSwitch.isChecked -> activity.getString(R.string.journey_caption_hidden)
            currentCaption != null -> currentCaption.value
            state?.mode == PlaybackMode.MUSIC -> activity.getString(R.string.journey_music_caption)
            state?.mode == PlaybackMode.PAUSED -> activity.getString(R.string.journey_paused_caption)
            else -> activity.getString(R.string.journey_caption_idle)
        }
        if (caption.text.toString() != captionText) { caption.text = captionText; caption.scrollTo(0, 0) }
        activity.findViewById<Button>(R.id.playback_play).setText(if (state?.mode == PlaybackMode.PAUSED) R.string.journey_resume else R.string.playback_play)
        val talkButton = activity.findViewById<View>(R.id.playback_talk)
        talkButton.alpha = if (current?.supportsLiveInput() == true) 1f else 0.55f
        talkButton.contentDescription = activity.getString(
            if (current?.supportsLiveInput() == true) R.string.journey_talk_description else R.string.journey_talk_unavailable)
    }

    private fun updateNavigationVisibility() {
        val active = allowed && page == Page.SETTINGS && optionsExpanded && !disposed
        navigation.setAvailable(active)
        if (!active) { searchDialog?.dismiss(); searchDialog = null }
    }

    private fun searchDestination() {
        if (page != Page.SETTINGS || !optionsExpanded || disposed || !allowed || searchDialog != null) return
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.journey_destination_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(100))
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setPadding(24, 16, 24, 16)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.journey_search_destination)
            .setMessage(R.string.journey_destination_explanation)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.journey_destination_search, null)
            .create()
        searchDialog = dialog
        dialog.setOnDismissListener { input.text.clear(); if (searchDialog === dialog) searchDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val keyword = input.text.toString().trim()
                if (keyword.isBlank()) { input.error = activity.getString(R.string.journey_destination_required); return@setOnClickListener }
                if (allowed && !disposed && page == Page.SETTINGS && optionsExpanded) navigation.request(NavigationScreenCommand.Search(keyword))
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
