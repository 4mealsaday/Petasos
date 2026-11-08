package com.petasos.app.demo

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.sqlite.db.SimpleSQLiteQuery
import com.petasos.app.playback.PlaybackMode

/** Runs only in the separate demo package. Fixed markers/counts only; no product data or SDK input. */
class DemoScenarioInstrumentation : Instrumentation() {
    private val marks = mutableListOf<String>()
    private var stage = "bootstrap"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val started = SystemClock.elapsedRealtime()
        val result = Bundle()
        try {
            bootstrap()
            stage = "reroute"
            reroutedStory()
            stage = "time_equivalence"
            equivalentAdvances()
            stage = "controls_and_expiry"
            controlsAndExpiry()
            stage = "durable_close"
            failedClose()
            result.putString("outcome", "PASS")
            result.putString("reason", "completed")
        } catch (failure: Throwable) {
            result.putInt("failure_line", failure.stackTrace.firstOrNull {
                it.className.startsWith("com.petasos.app.demo.")
            }?.lineNumber ?: 0)
            result.putString("outcome", "FAIL")
            result.putString("reason", "demo_scenario_check_failed")
        }
        result.putString("stage", stage)
        result.putString("events", marks.joinToString(","))
        result.putString("scope", "separate_demo_plain_Application_no_permissions_components_or_product_storage_actual_core_Room_public_stories_synthetic_time_route_silent_ports_not_final_UI_or_live_acceptance")
        result.putLong("elapsed_ms", SystemClock.elapsedRealtime() - started)
        finish(if (result.getString("outcome") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    @Suppress("DEPRECATION")
    private fun bootstrap() {
        check(targetContext.packageName == DemoFixtureStore.PACKAGE)
        check(targetContext.applicationContext.javaClass == Application::class.java)
        val info = targetContext.packageManager.getPackageInfo(targetContext.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS)
        check(info.requestedPermissions.isNullOrEmpty() && info.activities.isNullOrEmpty() &&
            info.services.isNullOrEmpty() && info.providers.isNullOrEmpty() && info.receivers.isNullOrEmpty())
        val impostor = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.petasos.app"
        }
        val rejected = try { DemoFixtureStore(impostor, DemoVirtualTime()); false }
            catch (failure: IllegalStateException) { failure.message == "DEMO_PACKAGE_REQUIRED" }
        check(rejected)
        marks += "demo_plain_application_zero_permissions_components_and_product_package_rejected"
    }

    private fun reroutedStory() {
        var gate = false
        withSession({ gate }) { fixture, session ->
            check(!session.play() && !session.advanceBy(1) && !session.changeRoute(DemoRouteChoice.YUSEONG))
            check(!session.next() && !session.previous())
            check(session.snapshot().elapsedMs == 100_000L && !session.snapshot().playIntent)
            fixture.store.database.openHelper.writableDatabase.execSQL("""CREATE TRIGGER demo_music_commit_before_story
                BEFORE INSERT ON playback_attempt WHEN NEW.kind='story' AND NEW.paragraph_id IN
                (SELECT p.paragraph_id FROM paragraph p JOIN story_version v ON v.story_version_id=p.story_version_id
                 WHERE v.story_id='$FINAL') AND NOT EXISTS
                (SELECT 1 FROM playback_attempt WHERE kind='music' AND state='completed')
                BEGIN SELECT RAISE(ABORT, 'demo_music_commit_required'); END""")
            gate = true
            check(session.play())
            val initial = session.snapshot()
            check(initial.activeStoryId == FIRST && initial.offeredStoryId == OLD)
            check(initial.earliestStoryStartElapsedMs == 144_000L)
            check(session.advanceBy(34_000))
            check(session.snapshot().mode == PlaybackMode.MUSIC && session.snapshot().musicPositionMs == 0L)
            check(session.advanceBy(2_000) && session.stop())
            check(session.snapshot().musicPositionMs == 2_000L && !session.snapshot().playIntent)
            check(session.snapshot().activeStoryId == null && session.snapshot().activeStoryTitle == null)
            check(count(fixture, "SELECT count(*) FROM playback_attempt WHERE kind='music' AND state='interrupted' AND position_ms=2000") == 1L)
            check(session.changeRoute(DemoRouteChoice.YUSEONG) && session.advanceBy(1_000))
            check(!session.snapshot().playIntent)
            check(session.play())
            val rerouted = session.snapshot()
            check(rerouted.offeredStoryId == FINAL && rerouted.earliestStoryStartElapsedMs == 145_000L)
            check(checkNotNull(rerouted.planningRevision) > checkNotNull(initial.planningRevision))
            check(session.advanceBy(7_999))
            check(attempts(fixture, FINAL) == 0L && session.snapshot().musicPositionMs == 9_999L)
            check(session.advanceBy(1))
            val result = session.snapshot()
            check(result.mode == PlaybackMode.STORY && result.activeStoryId == FINAL)
            check(result.startedStoryIds == listOf(FIRST, FINAL))
            check(result.automaticStartedStoryIds == listOf(FINAL))
            check(result.completedMusicCount == 1L && attempts(fixture, OLD) == 0L)
            gate = false
            session.reconcileGate()
            check(!session.snapshot().playIntent)
            check(!session.play() && !session.advanceBy(1))
            check(session.stop())
            marks += "demo_gate_and_length_music_stop_resume_reroute_exact_story_pass"
        }
    }

    private fun equivalentAdvances() {
        fun run(chunks: List<Long>): List<Any?> {
            var value: List<Any?>? = null
            withSession({ true }) { fixture, session ->
                check(session.play())
                chunks.forEach { check(session.advanceBy(it)) }
                val state = session.snapshot()
                check(state.activeStoryId == OLD && state.completedMusicCount == 1L)
                value = listOf(state.elapsedMs, state.mode, state.routeRevision, state.planningRevision,
                    state.currentRegionId, state.targetRegionId, state.earliestStoryStartElapsedMs,
                    state.offeredStoryId, state.activeStoryId, state.musicPositionMs,
                    state.completedStoryCount, state.completedParagraphCount, state.completedMusicCount,
                    state.startedStoryIds, state.automaticStartedStoryIds,
                    count(fixture, "SELECT count(*) FROM playback_attempt"))
            }
            return checkNotNull(value)
        }
        check(run(listOf(44_000L)) == run(List(44) { 1_000L }))
        marks += "demo_large_and_incremental_advance_same_committed_history_and_projection"
    }

    private fun controlsAndExpiry() {
        withSession({ true }) { fixture, session ->
            check(session.play() && session.advanceBy(36_000))
            check(session.previous())
            check(session.snapshot().musicPositionMs == 0L && session.snapshot().completedMusicCount == 0L)
            check(session.next())
            check(session.snapshot().completedMusicCount == 0L && attempts(fixture, OLD) == 0L)
            check(session.stop())
            check(session.changeRoute(DemoRouteChoice.OBSERVATION_GAP))
            check(session.advanceBy(10_001))
            check(session.play())
            check(session.snapshot().targetRegionId == null)
            val receiptTime = session.snapshot().elapsedMs
            repeat(3) { check(session.snapshot().elapsedMs == receiptTime && session.snapshot().targetRegionId == null) }
            check(count(fixture, "SELECT count(*) FROM route_point") == 0L)
            check(count(fixture, "SELECT count(*) FROM conversation_turn") == 0L)
            check(count(fixture, "SELECT count(*) FROM preference") == 0L)
            marks += "demo_skip_previous_and_expired_observation_do_not_fabricate_music_completion_or_location"
        }
    }

    private fun failedClose() {
        withSession({ true }) { fixture, session ->
            check(session.play() && session.stop())
            val sql = fixture.store.database.openHelper.writableDatabase
            sql.execSQL("""CREATE TRIGGER demo_close_failure BEFORE UPDATE OF status ON session
                WHEN NEW.status='closed' BEGIN SELECT RAISE(ABORT, 'demo_injected_close_failure'); END""")
            check(!session.close())
            check(!fixture.closeAndDeleteAfterAcknowledgedClose() && fixture.ownedDatabasePresent())
            sql.execSQL("DROP TRIGGER demo_close_failure")
            check(session.close())
            marks += "demo_failed_durable_close_preserves_fixture_and_retry_acknowledges_success"
        }
    }

    private fun withSession(gate: () -> Boolean, body: (DemoFixtureStore, DemoScenarioSession) -> Unit) {
        val time = DemoVirtualTime()
        val fixture = DemoFixtureStore(targetContext, time)
        var session: DemoScenarioSession? = null
        try {
            session = DemoScenarioSession(fixture, gate)
            body(fixture, session)
        } finally {
            if (session?.close() == true) {
                check(fixture.closeAndDeleteAfterAcknowledgedClose())
                check(!fixture.ownedDatabasePresent())
            } else fixture.closeKeepingFiles()
            time.close()
        }
    }

    private fun count(fixture: DemoFixtureStore, statement: String, vararg args: Any?): Long =
        fixture.store.database.openHelper.readableDatabase.query(SimpleSQLiteQuery(statement, args)).use {
            check(it.moveToFirst()); it.getLong(0)
        }
    private fun attempts(fixture: DemoFixtureStore, story: String) = count(fixture,
        """SELECT count(*) FROM playback_attempt a JOIN paragraph p ON p.paragraph_id=a.paragraph_id
            JOIN story_version v ON v.story_version_id=p.story_version_id WHERE a.kind='story' AND v.story_id=?""", story)

    companion object {
        private const val FIRST = "03849d89-1640-5603-be8b-9835d371ed02"
        private const val OLD = "07a9db6f-b2a5-5bbe-91ff-68e905e86125"
        private const val FINAL = "666b77d3-aba4-5ea6-b9fc-1a4c6556eef5"
    }
}
