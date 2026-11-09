package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import com.petasos.app.MainActivity
import com.petasos.app.R
import com.petasos.app.presentation.interests.InterestGridView
import com.google.common.util.concurrent.SettableFuture
import com.petasos.app.platform.media.PetasosMediaService
import com.petasos.app.platform.pleos.navigation.PleosCompanionDisplaySupport
import com.petasos.app.storage.StorageDatabase
import java.util.concurrent.TimeUnit
import com.petasos.app.presentation.records.RecordsGeometryCatalog
import com.petasos.app.presentation.records.RegionSilhouetteView
import com.petasos.app.presentation.records.RegionalMapView

/**
 * Actual parked Activity navigation and read-only records views. No synthetic CarUx, fixture rows,
 * preference/consent writes, permission dialogs, playback or microphone commands are issued here.
 * The unsupported home ON is clicked and must remain OFF without starting a companion session.
 * Only fixed outcome markers and public geometry counts leave this probe; UI text is never logged.
 */
object ProductJourneyUiProbe {
    class Failure(val outcome: String, val code: String) : RuntimeException(code)

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        val context = instrumentation.targetContext
        val publicShapes = RecordsGeometryCatalog.load(context)
        requireProbe(publicShapes.size == 261 && publicShapes.map { it.regionId }.distinct().size == 261,
            "journey_ui_public_geometry_inventory_changed")
        val deadline = SystemClock.elapsedRealtime() + 70_000L
        var activity: MainActivity? = null
        val storage = StorageDatabase.open(context, "petasos.db")
        fun count(sql: String): Long = storage.openHelper.readableDatabase.query(sql).use {
            requireProbe(it.moveToFirst(), "journey_ui_read_only_storage_unavailable")
            it.getLong(0)
        }

        fun <T : Any> onMain(action: () -> T): T {
            val result = SettableFuture.create<T>()
            Handler(Looper.getMainLooper()).post {
                if (!result.isCancelled) try { result.set(action()) } catch (error: Throwable) { result.setException(error) }
            }
            return try { result.get(8, TimeUnit.SECONDS) }
            catch (error: java.util.concurrent.ExecutionException) { throw (error.cause ?: error) }
            catch (_: java.util.concurrent.TimeoutException) {
                result.cancel(false)
                throw Failure("FAIL", "journey_ui_main_timeout")
            }
        }
        fun waitFor(code: String, timeoutMs: Long = 8_000, blocked: Boolean = false, predicate: () -> Boolean) {
            val until = minOf(deadline, SystemClock.elapsedRealtime() + timeoutMs)
            while (SystemClock.elapsedRealtime() < until) {
                if (onMain(predicate)) return
                SystemClock.sleep(25)
            }
            if (!onMain(predicate)) throw Failure(if (blocked) "BLOCKED" else "FAIL", code)
        }

        try {
            val screen = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = screen
            fun visibleGate(): Boolean = screen.findViewById<View>(R.id.preview_content).isShown
            fun click(id: Int) {
                onMain {
                    if (!visibleGate()) throw Failure("BLOCKED", "journey_ui_actual_car_ux_unavailable")
                    val button = screen.findViewById<View>(id)
                    requireProbe(button.isEnabled && button.isShown && button.performClick(), "journey_ui_control_unavailable")
                }
            }
            waitFor("journey_ui_actual_car_ux_unavailable", 15_000, blocked = true) {
                visibleGate() && screen.findViewById<View>(R.id.journey_companion_toggle).isEnabled
            }
            mark("journey_ui_actual_main_activity_current_car_ux_allows_parked_navigation")
            val pointBaseline = count("SELECT count(*) FROM route_point")
            val segmentBaseline = count("SELECT count(*) FROM recording_segment")
            val preferenceBaseline = count("SELECT COALESCE(MAX(version),0) FROM preference")
            requireProbe(count("SELECT count(*) FROM recording_segment WHERE status='active'") == 0L,
                "journey_ui_off_activity_started_recording")

            val display = PleosCompanionDisplaySupport.inspect(context)
            if (display.navigationSurfaceAvailable) throw Failure("BLOCKED", "journey_ui_update_overlay_acceptance_required")
            onMain {
                requireProbe(screen.findViewById<View>(R.id.journey_home_page).isShown,
                    "journey_ui_home_not_default")
                requireProbe(!screen.findViewById<View>(R.id.journey_nav_settings).isShown &&
                    !screen.findViewById<View>(R.id.journey_nav_listen).isShown &&
                    !screen.findViewById<View>(R.id.journey_listen_page).isShown,
                    "journey_ui_home_has_unrequested_navigation_or_player")
                if (PetasosMediaService.currentHost?.isCompanionActive() == true)
                    throw Failure("BLOCKED", "journey_ui_existing_companion_active")
            }
            click(R.id.journey_companion_toggle)
            SystemClock.sleep(500)
            onMain {
                requireProbe(PetasosMediaService.currentHost?.isCompanionActive() == false,
                    "journey_ui_unsupported_on_started_companion")
                requireProbe(PetasosMediaService.currentHost?.snapshot()?.playIntent != true,
                    "journey_ui_unsupported_on_started_playback")
                requireProbe(!screen.findViewById<View>(R.id.journey_companion_toggle).isSelected,
                    "journey_ui_unsupported_on_claimed_enabled")
                requireProbe(screen.findViewById<View>(R.id.journey_home_page).isShown,
                    "journey_ui_unsupported_on_navigated_away")
            }
            mark("journey_ui_home_two_controls_unsupported_on_remains_off_no_navigation_or_permission_request")
            click(R.id.journey_nav_records)
            click(R.id.journey_nav_settings)
            waitFor("journey_ui_interests_not_ready") {
                screen.findViewById<InterestGridView>(R.id.records_interest_grid).let {
                    it.isShown && it.isEnabled && it.childCount == 12 && it.width > 0
                }
            }
            val initialSelection = onMain {
                val grid = screen.findViewById<InterestGridView>(R.id.records_interest_grid)
                val tiles = children(grid).filterIsInstance<CheckBox>()
                val density = screen.resources.displayMetrics.density
                requireProbe(tiles.size == 12 && tiles.all { it.height >= 100 * density && it.width > 0 },
                    "journey_ui_interest_touch_targets_invalid")
                requireProbe(tiles.map { it.text.toString() }.distinct().size == 12, "journey_ui_interest_labels_not_unique")
                tiles.map { it.isChecked }
            }
            mark("journey_ui_twelve_large_interest_tiles_rendered_without_selection_or_save")

            click(R.id.journey_nav_listen)
            click(R.id.journey_nav_records)
            waitFor("journey_ui_records_map_not_ready") {
                descendants(screen.findViewById(R.id.records_ui_body)).any { it is RegionalMapView }
            }
            mark("journey_ui_read_only_records_atlas_map_loaded")
            click(R.id.records_ui_tab_album)
            waitFor("journey_ui_album_not_ready") {
                descendants(screen.findViewById(R.id.records_ui_body)).any { it is RegionSilhouetteView }
            }
            // Walk the public atlas pages. The collected/not-collected state and private counts are not emitted.
            var renderedShapes = 0
            var pages = 0
            while (true) {
                val hasNext = onMain {
                    if (!visibleGate()) throw Failure("BLOCKED", "journey_ui_actual_car_ux_unavailable")
                    val body = screen.findViewById<View>(R.id.records_ui_body)
                    val count = descendants(body).count { it is RegionSilhouetteView }
                    requireProbe(count in 1..12, "journey_ui_album_page_shape_count_invalid")
                    renderedShapes += count
                    pages++
                    requireProbe(pages <= 22, "journey_ui_album_pagination_unbounded")
                    val next = descendants(body).filterIsInstance<Button>().singleOrNull {
                        it.text == context.getString(R.string.records_ui_next)
                    } ?: throw Failure("FAIL", "journey_ui_album_pager_missing")
                    if (next.isEnabled) {
                        screen.findViewById<ScrollView>(R.id.records_ui_scroll).fullScroll(View.FOCUS_DOWN)
                        requireProbe(next.performClick(), "journey_ui_album_next_rejected")
                        true
                    } else false
                }
                if (!hasNext) break
            }
            requireProbe(renderedShapes == publicShapes.size, "journey_ui_album_public_inventory_mismatch")
            mark("journey_ui_public_album_261_silhouettes_across_22_pages_no_award_commands")
            onMain {
                val tile = descendants(screen.findViewById(R.id.records_ui_body))
                    .filterIsInstance<RegionSilhouetteView>().first().parent as View
                requireProbe(tile.isClickable && tile.performClick(), "journey_ui_region_tile_rejected")
            }
            waitFor("journey_ui_region_detail_not_ready") {
                val content = descendants(screen.findViewById(R.id.records_ui_body))
                content.any { it is RegionSilhouetteView } && content.filterIsInstance<TextView>().any {
                    it.text == context.getString(R.string.records_ui_region_stories)
                }
            }
            mark("journey_ui_region_detail_contains_completed_story_section_no_replay")

            click(R.id.journey_nav_settings)
            waitFor("journey_ui_interest_reload_not_ready") {
                screen.findViewById<InterestGridView>(R.id.records_interest_grid).isEnabled
            }
            onMain {
                requireProbe(children(screen.findViewById<InterestGridView>(R.id.records_interest_grid))
                    .filterIsInstance<CheckBox>().map { it.isChecked } == initialSelection,
                    "journey_ui_interest_selection_changed")
            }
            mark("journey_ui_navigation_and_read_only_records_pass_no_preference_consent_permission_or_media_commands")

            click(R.id.journey_nav_listen)
            onMain {
                val home = screen.findViewById<View>(R.id.journey_home_page)
                requireProbe(home.isShown && descendants(home).none { it is RegionalMapView || it.javaClass.name.startsWith("com.naver.maps.") },
                    "journey_ui_home_contains_own_live_map")
                // Layout-only narrow-window coverage: no controller or SDK is attached to this tree.
                listOf(360, 600, 900).forEach { widthDp ->
                    val configuration = android.content.res.Configuration(screen.resources.configuration).apply {
                        screenWidthDp = widthDp
                        screenHeightDp = 800
                        smallestScreenWidthDp = minOf(widthDp, 800)
                    }
                    val preview = android.view.ContextThemeWrapper(screen.createConfigurationContext(configuration), R.style.Theme_Petasos)
                    val root = android.widget.FrameLayout(preview)
                    android.view.LayoutInflater.from(preview).inflate(R.layout.activity_main, root)
                    root.findViewById<View>(R.id.preview_content).visibility = View.VISIBLE
                    root.measure(View.MeasureSpec.makeMeasureSpec((widthDp * screen.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((800 * screen.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    val grid = root.findViewById<ViewGroup>(R.id.journey_home_actions)
                    requireProbe(grid.width > 0 && children(grid).size == 2 && children(grid).all { control ->
                        control.width > 0 && control.height >= 160 * screen.resources.displayMetrics.density &&
                            control.left >= 0 && control.right <= grid.width
                    }, "journey_ui_home_narrow_controls_overflow")
                }
            }
            mark("journey_ui_home_two_large_controls_fit_360_600_900_dp_no_own_live_map")
            requireProbe(count("SELECT count(*) FROM route_point") == pointBaseline &&
                count("SELECT count(*) FROM recording_segment") == segmentBaseline &&
                count("SELECT count(*) FROM recording_segment WHERE status='active'") == 0L &&
                count("SELECT COALESCE(MAX(version),0) FROM preference") == preferenceBaseline,
                "journey_ui_off_navigation_changed_location_or_preferences")
            mark("journey_ui_off_and_unsupported_on_no_location_samples_segments_or_preference_changes")

        } finally {
            try { activity?.let { screen ->
                onMain { screen.finish() }
                waitFor("journey_ui_activity_finish_unconfirmed", 4_000) { screen.isDestroyed }
                mark("journey_ui_activity_finished_without_clear_data_or_uninstall")
            } } finally { storage.close() }
        }
    }

    private fun children(group: ViewGroup): List<View> = (0 until group.childCount).map(group::getChildAt)
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) children(view).flatMap(::descendants) else emptyList()
    private fun requireProbe(condition: Boolean, code: String) {
        if (!condition) throw Failure("FAIL", code)
    }
}
