package com.petasos.app.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Process
import java.io.File
import com.petasos.app.MainActivity
import com.petasos.app.capability.location.LocationEvent
import com.petasos.app.capability.location.LocationReadingKind
import com.petasos.app.capability.location.LocationOperation
import com.petasos.app.capability.speech.SpeechInputEvent
import com.petasos.app.capability.speech.SpeechOutputEvent
import com.petasos.app.platform.pleos.location.PleosLocationSource
import com.petasos.app.platform.pleos.speech.PleosSpeechInput
import com.petasos.app.platform.pleos.speech.PleosSpeechOutput
import com.petasos.app.storage.StorageIntegrationProbe
import com.petasos.app.storage.SummaryStorageProbe
import com.petasos.app.storage.SummaryRightsProbe
import com.petasos.app.storage.ContentInstallProbe
import com.petasos.app.storage.ContentRuntimeProbe
import com.petasos.app.generation.GenerationIntegrationProbe
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Explicit, separately installed test APK. The product UI never runs these probes. */
class SdkProbeInstrumentation : Instrumentation() {
    private val events = LinkedBlockingQueue<Any>()
    private val observed = mutableListOf<String>()
    private var probe = ""
    private var startedAt = 0L
    private var fixturePort = 0
    private var bundledStoryIndex: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        probe = arguments?.getString("probe").orEmpty()
        fixturePort = arguments?.getString("fixturePort")?.toIntOrNull() ?: 0
        bundledStoryIndex = arguments?.getString("bundledStoryIndex")
        start()
    }

    override fun onStart() {
        startedAt = SystemClock.elapsedRealtime()
        var outcome = "PASS"
        var reason = "completed"
        try {
            if (probe == "bundled-playback") {
                if (bundledStoryIndex?.matches(Regex("[0-9]")) != true)
                    throw ProbeFailure("FAIL", "bundled_story_index_required_0_to_9")
            } else if (bundledStoryIndex != null) {
                throw ProbeFailure("FAIL", "bundled_story_index_only_for_bundled_playback")
            }
            when (probe) {
                "location" -> location()
                "android-location-contract" -> com.petasos.app.storage.AndroidLocationContractProbe.run(this, ::mark)
                "android-speech-capability" -> AndroidSpeechCapabilityProbe.run(this, ::mark)
                "tts-connect", "tts-speak", "tts-stop", "tts-close" -> speechOutput()
                "stt-connect", "stt-ready", "stt-listen", "stt-cancel", "stt-close" -> speechInput()
                "stt-pcm" -> SttPcmProbe.run(this, ::mark)
                "stt-owned-pcm" -> OwnedPcmInputProbe.run(this, ::mark)
                "stt-owned-eof" -> OwnedPcmInputProbe.run(this, ::mark, finiteEof = true)
                "stt-owned-stream" -> OwnedPcmInputProbe.run(this, ::mark, continuousSilence = true)
                "stt-owned-delayed" -> OwnedPcmInputProbe.run(this, ::mark, continuousSilence = true, prefixSilenceMs = 1_500)
                "stt-owned-listen" -> OwnedPcmInputProbe.run(this, ::mark, realMicrophone = true)
                "vehicle-preferences" -> com.petasos.app.storage.VehiclePreferenceProbe.run(this, ::mark)
                "trip-results" -> com.petasos.app.storage.TripResultsProbe.run(this, ::mark)
                "trip-lifecycle" -> com.petasos.app.storage.TripLifecycleProbe.run(this, ::mark)
                "app-trip-lifecycle" -> ProductTripLifecycleProbe.run(this, ::mark)
                "app-location-live" -> ProductLocationProbe.run(this, ::mark)
                "location-storage" -> com.petasos.app.storage.LocationStorageProbe.run(this, ::mark)
                "region-storage" -> com.petasos.app.storage.RegionStorageProbe.run(this, ::mark)
                "region-policy-storage" -> com.petasos.app.storage.RegionPolicyStorageProbe.run(this, ::mark)
                "region-hierarchy" -> com.petasos.app.storage.RegionHierarchyProbe.run(this, ::mark)
                "bundled-stories" -> com.petasos.app.storage.BundledStoryCatalogProbe.run(this, ::mark)
                "regional-content" -> com.petasos.app.storage.RegionalContentProbe.run(this, ::mark)
                "landmark-storage" -> com.petasos.app.storage.LandmarkStorageProbe.run(this, ::mark)
                "landmark-policy-storage" -> {
                    com.petasos.app.storage.LandmarkPolicyStorageProbe.run(this, ::mark)
                    com.petasos.app.storage.BundledLandmarkPolicyProbe.run(this, ::mark)
                }
                "representative-route-runtime" -> com.petasos.app.storage.RepresentativeRouteRuntimeProbe.run(this, ::mark)
                "automatic-planning" -> com.petasos.app.storage.AutomaticPlanningProbe.run(this, ::mark)
                "local-commands" -> com.petasos.app.storage.ExplicitPlaybackCommandProbe.run(this, ::mark)
                "session-recovery" -> com.petasos.app.storage.SessionRecoveryProbe.run(this, ::mark)
                "recovery-music-gap" -> com.petasos.app.storage.RecoveryMusicGapProbe.run(this, ::mark)
                "navigation" -> NavigationSdkProbe.run(this, ::mark)
                "auxiliary-route" -> AuxiliaryRouteProbe.run(this, ::mark)
                "music-runtime" -> com.petasos.app.storage.MusicRuntimeProbe.run(this, ::mark)
                "media-session" -> MediaSessionControlProbe.run(this, ::mark)
                "app-playback" -> ProductPlaybackProbe.run(this, ::mark)
                "bundled-playback" -> ProductPlaybackProbe.run(this, ::mark, checkNotNull(bundledStoryIndex).toInt())
                "app-music" -> ProductMusicProbe.run(this, ::mark)
                "app-story-generation" -> ProductStoryGenerationProbe.run(this, ::mark)
                "story-generation-storage" -> com.petasos.app.storage.StoryGenerationStorageProbe.run(this, ::mark)
                "resume-context" -> com.petasos.app.storage.ResumeContextProbe.run(this, ::mark)
                "generation-planning-live" -> com.petasos.app.generation.PlanningGenerationProbe.run(this, ::mark)
                "car-policy-ui" -> CarPolicyPresentationProbe.run(this, ::mark)
                "generation-provision" -> com.petasos.app.generation.DevelopmentGenerationProbe.provision(this, ::mark, fixturePort)
                "generation-live" -> com.petasos.app.generation.DevelopmentGenerationProbe.run(this, ::mark)
                "storage" -> StorageIntegrationProbe.run(this, ::mark)
                "summary-storage" -> SummaryStorageProbe.run(this, ::mark)
                "summary-rights" -> SummaryRightsProbe.run(this, ::mark)
                "content-install" -> ContentInstallProbe.run(this, ::mark)
                "content-install-local" -> ContentInstallProbe.installLocal(this, ::mark)
                "content-runtime" -> ContentRuntimeProbe.run(this, ::mark)
                "summary-work" -> SummaryWorkIntegrationProbe.run(this, ::mark)
                "generation-https" -> GenerationIntegrationProbe.run(this, ::mark, fixturePort)
                "runtime-scripted" -> RuntimeIntegrationProbe.run(this, ::mark, realTts = false)
                "runtime-tts" -> RuntimeIntegrationProbe.run(this, ::mark, realTts = true)
                "runtime-tts-stop" -> RuntimeIntegrationProbe.runTtsStop(this, ::mark)
                else -> throw ProbeFailure("FAIL", "unknown_probe")
            }
        } catch (failure: ProbeFailure) {
            outcome = failure.outcome
            reason = failure.code
        } catch (failure: SttPcmProbe.Failure) {
            outcome = "BLOCKED"
            reason = failure.code
        } catch (failure: ProductLocationProbe.Failure) {
            outcome = failure.outcome
            reason = failure.code
        } catch (failure: AndroidSpeechCapabilityProbe.Failure) {
            outcome = "BLOCKED"
            reason = failure.code
        } catch (failure: AuxiliaryRouteProbe.Failure) {
            outcome = failure.outcome
            reason = failure.code
        } catch (failure: com.petasos.app.storage.ExplicitPlaybackCommandProbe.Failure) {
            outcome = "FAIL"
            reason = failure.code
        } catch (failure: Throwable) {
            // Exception messages and stack traces can include vendor payloads.
            outcome = "FAIL"
            reason = failure.javaClass.simpleName
        }
        finish(Activity.RESULT_OK, Bundle().apply {
            putString("probe", probe)
            putString("outcome", outcome)
            putString("reason", reason)
            putString("events", observed.joinToString(","))
            putLong("elapsed_ms", SystemClock.elapsedRealtime() - startedAt)
            putString("scope", when (probe) {
                "stt-owned-pcm" -> "actual_Pleos_PCM_with_finite_synthetic_capture_no_microphone"
                "stt-owned-eof" -> "actual_Pleos_PCM_exact_zero_tail_trimmed_finite_EOF_and_complete_call_timing_no_microphone"
                "stt-owned-stream" -> "actual_Pleos_PCM_with_continuous_synthetic_capture_no_microphone"
                "stt-owned-delayed" -> "actual_Pleos_PCM_continuous_synthetic_capture_with_1500ms_prefix_silence_no_microphone"
                "stt-owned-listen" -> "actual_owned_AudioRecord_and_Pleos_PCM_user_opt_in"
                "app-story-generation" -> "actual_product_public_story_generation_install_TTS_and_Stop_no_microphone"
                "story-generation-storage" -> "actual_Room_generated_story_install_with_synthetic_data_no_network_or_audio"
                "resume-context" -> "actual_Room_Keystore_frozen_completed_only_context_and_revocation_synthetic_data"
                "generation-planning-live" -> "actual_Android_HTTPS_story_install_and_bounded_resume_public_fixed_context"
                "app-playback" -> "actual_product_media_service_installed_public_story_TTS_and_durable_playback_no_microphone"
                "bundled-playback" -> "one_explicit_SHA_pinned_bundled_public_story_actual_TTS_all_required_paragraphs_durable_monotonic_callback_durations_no_acoustic_duration_claim_Activity_microphone_model_location_or_consent_change_history_retained"
                "app-music" -> "actual_product_public_story_then_licensed_bundled_music_Stop_resume_natural_end_and_durable_commits_no_microphone_or_model"
                "vehicle-preferences" -> "actual_Room_explicit_vehicle_preferences_with_disposable_synthetic_data_no_capture"
                "trip-results" -> "actual_Room_read_only_committed_trip_results_disposable_synthetic_data_no_capture"
                "trip-lifecycle" -> "actual_Room_explicit_trip_end_and_recovery_disposable_synthetic_data_no_capture"
                "representative-route-runtime" -> "disposable_Room_SHA_pinned_public_stories_synthetic_route_clock_silent_music_exact_rerouted_story_after_music_commit_no_product_database_location_audio_microphone_network"
                "automatic-planning" -> "actual_Room_automatic_region_interest_planning_synthetic_content_scripted_runtime_no_microphone_location_SDK_or_model"
                "local-commands" -> "actual_Room_Keystore_explicit_local_commands_scripted_input_output_and_generation_no_microphone_audio_location_or_model"
                "app-trip-lifecycle" -> "actual_product_public_TTS_end_trip_durable_results_new_trip_and_Stop_no_microphone_or_model"
                "app-location-live" -> "actual_product_existing_location_consent_observed_Activity_onStop_return_or_explicit_close_reopen_fallback_Room_counts_no_inferred_Home_background_acceptance_coordinate_output_permission_change_VirtualConsole_or_model"
                "location-storage" -> "actual_Room_location_and_region_proof_with_synthetic_coordinates_and_boundary"
                "android-location-contract" -> "public_Android_provider_metadata_synthetic_Location_mapping_and_Room_no_location_subscription_or_permission_change"
                "android-speech-capability" -> "public_Android_speech_availability_Korean_support_and_TTS_voice_metadata_no_recognition_capture_PCM_synthesis_download_or_product_database_not_privacy_or_speech_acceptance"
                "region-storage" -> "actual_packaged_NGII_geometry_Room_provenance_synthetic_coordinates_pending_policy_no_capture"
                "region-policy-storage" -> "disposable_Room_packaged_9_reviews_plus_separate_synthetic_policies_exact_proofs_revocation_no_product_database_consent_or_capture"
                "region-hierarchy" -> "disposable_Room_reviewed_hierarchy_leaf_card_parent_story_revocation_no_product_policy_or_capture"
                "bundled-stories" -> "disposable_Room_packaged_stories_and_9_region_reviews_no_trip_collection_audio_or_network"
                "regional-content" -> "actual_Room_v1_v2_migration_reviewed_region_binding_synthetic_content_no_location_audio_or_model"
                "recovery-music-gap" -> "actual_Room_durable_music_boundary_recovery_synthetic_scripted_no_capture_audio_or_model"
                "landmark-storage" -> "actual_Room_reviewed_landmark_policy_coordinate_and_association_synthetic_content_no_location_audio_consent_change_or_model"
                "landmark-policy-storage" -> "disposable_Room_synthetic_policy_regressions_plus_actual_two_packaged_public_landmark_reviews_current_candidates_exact_reinstall_rights_and_rejection_preservation_no_product_database_location_audio_or_model"
                "session-recovery" -> "actual_Room_Keystore_TX04_recovery_with_synthetic_data"
                "navigation" -> "actual_read_only_NaviHelper_no_route_mutation"
                "auxiliary-route" -> "one_actual_HTTPS_public_official_route_example_disposable_NGII_hierarchy_projection_unchanged_packaged_policies_synthetic_Android_anchor_no_real_location_navigation_audio_model_or_product_records"
                "music-runtime" -> "actual_Media3_and_Room_with_synthetic_test_tone_no_microphone_or_model"
                "media-session" -> "actual_MediaController_commands_and_product_browse_no_audio_or_microphone"
                "car-policy-ui" -> "actual_CarUx_observation_detached_synthetic_mapper_renderer_gates_no_vehicle_mutation_normal_target_startup"
                "generation-provision" -> "private_debug_localhost_TLS_device_configuration_install_no_model_call"
                "generation-live" -> "actual_Android_HTTPS_OpenRouter_fixed_nonpersonal_input_no_microphone"
                "storage" -> "actual_Room_SQLite_Keystore_with_synthetic_test_data"
                "summary-storage" -> "actual_Room_Keystore_TX03_with_synthetic_summary_no_model_call"
                "summary-rights" -> "actual_Room_Keystore_summary_evidence_gates_with_synthetic_transport_no_network_or_audio"
                "content-install" -> "actual_Room_TX05_in_disposable_databases_optional_reviewed_local_bundle_no_network_or_audio"
                "content-install-local" -> "actual_Room_TX05_reviewed_local_bundle_persisted_in_product_database_no_network_or_audio"
                "content-runtime" -> "actual_Room_Keystore_installed_content_runtime_context_and_generation_client_with_synthetic_transport_no_network_or_audio"
                "summary-work" -> "actual_WorkManager_test_driver_and_Room_with_scripted_generation_no_live_model"
                "generation-https" -> "actual_HTTPS_to_local_server_with_test_CA_and_synthetic_provider_no_external_model"
                "runtime-scripted" -> "actual_Room_Keystore_with_scripted_speech_and_generation_no_microphone_or_network"
                "runtime-tts" -> "actual_Pleos_TTS_and_Room_with_synthetic_test_story"
                "runtime-tts-stop" -> "actual_Pleos_TTS_stopped_callback_and_Room_interrupted_commit"
                else -> "actual_Pleos_SDK_probe_not_product_acceptance"
            })
        })
    }

    private fun mark(name: String) {
        observed += name
        sendStatus(0, Bundle().apply { putString("event", name) })
    }

    private fun next(deadline: Long): Any {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw ProbeFailure("BLOCKED", "callback_timeout")
        return events.poll(remaining, TimeUnit.MILLISECONDS)
            ?: throw ProbeFailure("BLOCKED", "callback_timeout")
    }

    private fun deadline(seconds: Long) = SystemClock.elapsedRealtime() + seconds * 1_000

    private fun awaitHostAudit(phase: String, kind: String = "stt") {
        val token = SystemClock.elapsedRealtimeNanos()
        val acknowledgment = File(targetContext.cacheDir, "$kind-$phase-$token.ack")
        try {
            mark("${kind}_audit_${phase}_pid_${Process.myPid()}_token_$token")
            val limit = deadline(15)
            while (!acknowledgment.exists()) {
                if (SystemClock.elapsedRealtime() >= limit) throw ProbeFailure("FAIL", "host_audit_timeout_$phase")
                SystemClock.sleep(25)
            }
        } finally {
            acknowledgment.delete()
        }
    }

    private fun location() {
        lateinit var source: PleosLocationSource
        runOnMainSync {
            source = PleosLocationSource(targetContext)
            source.start { events.offer(it) }
        }
        try {
            val limit = deadline(35)
            var firstFixture = false
            while (true) {
                when (val event = next(limit) as LocationEvent) {
                    is LocationEvent.Availability -> {
                        mark("location_supported_${event.supported}")
                        if (!event.supported) throw ProbeFailure("BLOCKED", "location_service_missing")
                    }
                    LocationEvent.PermissionDenied -> throw ProbeFailure("BLOCKED", "location_permission_denied")
                    LocationEvent.InitializationRequested -> mark("location_initialization_requested")
                    LocationEvent.SubscriptionRequested -> mark("location_subscribed")
                    is LocationEvent.NoLocation -> mark("location_null_${event.kind}")
                    is LocationEvent.Failure -> throw ProbeFailure("BLOCKED", "location_${event.operation}_${event.errorType}")
                    is LocationEvent.SampleReceived -> {
                        if (event.kind != LocationReadingKind.UPDATE) {
                            mark("location_last_known_received")
                            continue
                        }
                        // Compare only the two public synthetic fixtures; never log real coordinates.
                        val sample = event.sample
                        if (!firstFixture && abs(sample.latitude - 37.4979) < 0.00001 &&
                            abs(sample.longitude - 127.0276) < 0.00001) {
                            firstFixture = true
                            mark("location_fixture_a")
                        } else if (firstFixture && abs(sample.latitude - 37.4989) < 0.00001 &&
                            abs(sample.longitude - 127.0286) < 0.00001) {
                            mark("location_fixture_b")
                            return
                        }
                    }
                }
            }
        } finally {
            runOnMainSync { source.close() }
            val teardownFailure = events.filterIsInstance<LocationEvent.Failure>().firstOrNull {
                it.operation == LocationOperation.UNREGISTER_UPDATES || it.operation == LocationOperation.RELEASE
            }
            if (teardownFailure != null) throw ProbeFailure("FAIL", "location_teardown_${teardownFailure.errorType}")
        }
    }

    private fun foreground() {
        startActivitySync(Intent(targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun speechOutput() {
        foreground()
        lateinit var output: PleosSpeechOutput
        runOnMainSync {
            output = PleosSpeechOutput(targetContext) { events.offer(it) }
            output.connect()
        }
        try {
            val limit = deadline(30)
            var stopRequested = false
            var startedSeen = false
            while (true) {
                when (val event = next(limit) as SpeechOutputEvent) {
                    SpeechOutputEvent.Connected -> {
                        mark("tts_connected")
                        if (probe == "tts-connect") return
                        val text = if (probe == "tts-close") {
                            "페타소스 음성 해제 시험입니다. 재생 중 연결을 해제하는 동작을 확인합니다. " +
                                "이 문장은 서비스 연결 상태를 확인할 시간을 확보하기 위한 합성 문장입니다. " +
                                "출력이 끝나기 전에 앱의 해제 요청이 실행되어야 합니다."
                        } else if (probe == "tts-stop") {
                            "페타소스 음성 중지 시험입니다. 이 문장은 중지 요청을 확인하기 위한 합성 문장입니다."
                        } else {
                            "페타소스 음성 출력 연결을 확인합니다."
                        }
                        runOnMainSync { output.speak(text) }
                        mark("tts_speak_requested")
                    }
                    SpeechOutputEvent.Ready -> mark("tts_ready")
                    SpeechOutputEvent.Started -> {
                        startedSeen = true
                        mark("tts_started")
                        if (probe == "tts-stop") {
                            stopRequested = true
                            runOnMainSync { output.stop() }
                            mark("tts_stop_requested")
                        } else if (probe == "tts-close") {
                            // Keep this process alive so unbinding cannot be attributed to its exit.
                            awaitHostAudit("ready", "tts")
                            var endedBeforeClose = false
                            runOnMainSync {
                                endedBeforeClose = events.any {
                                    it is SpeechOutputEvent.Completed || it is SpeechOutputEvent.Stopped ||
                                        it is SpeechOutputEvent.Failed || it is SpeechOutputEvent.FocusLost
                                }
                                if (!endedBeforeClose) output.close()
                            }
                            if (endedBeforeClose) throw ProbeFailure("BLOCKED", "tts_ended_before_close")
                            mark("tts_close_requested")
                            awaitHostAudit("closed", "tts")
                            return
                        }
                    }
                    SpeechOutputEvent.Completed -> {
                        mark("tts_completed")
                        if (stopRequested) throw ProbeFailure("FAIL", "completed_after_stop_request")
                        if (probe != "tts-speak") throw ProbeFailure("FAIL", "completed_without_start_stop")
                        if (!startedSeen) throw ProbeFailure("FAIL", "completed_before_started")
                        return
                    }
                    SpeechOutputEvent.Stopped -> {
                        mark("tts_stopped")
                        if (probe != "tts-stop" || !stopRequested) throw ProbeFailure("FAIL", "unexpected_stop")
                        return
                    }
                    is SpeechOutputEvent.FocusLost -> throw ProbeFailure("BLOCKED", "audio_focus_lost")
                    is SpeechOutputEvent.Failed -> throw ProbeFailure("BLOCKED", "tts_${event.failure.code}_${event.failure.vendorCode ?: "none"}")
                }
            }
        } finally {
            runOnMainSync { output.close() }
            // Do not let instrumentation process exit perform asynchronous SDK cleanup for us.
            SystemClock.sleep(1_100)
        }
    }

    private fun speechInput() {
        foreground()
        lateinit var input: PleosSpeechInput
        runOnMainSync {
            input = PleosSpeechInput(targetContext) { events.offer(it) }
            input.connect()
        }
        try {
            var limit = deadline(12)
            var stopRequested = false
            var finalSeen = false
            var ready = false
            while (true) {
                when (val event = next(limit) as SpeechInputEvent) {
                    SpeechInputEvent.Connected -> {
                        mark("stt_connected")
                        if (probe == "stt-connect") return
                        runOnMainSync { input.request() }
                        limit = deadline(3)
                        mark("stt_request_sent")
                    }
                    SpeechInputEvent.Ready -> {
                        ready = true
                        mark("stt_ready")
                        if (probe == "stt-cancel" || probe == "stt-close") {
                            awaitHostAudit("ready")
                            if (probe == "stt-cancel") {
                                runOnMainSync { input.stop() }
                                mark("stt_stop_requested")
                                val observationEnd = deadline(2)
                                var endedObserved = false
                                while (SystemClock.elapsedRealtime() < observationEnd) {
                                    when (events.poll(100, TimeUnit.MILLISECONDS)) {
                                        SpeechInputEvent.Ended -> endedObserved = true
                                        is SpeechInputEvent.Transcript -> throw ProbeFailure("FAIL", "transcript_after_cancel")
                                        is SpeechInputEvent.Failed -> throw ProbeFailure("FAIL", "cancel_error")
                                    }
                                }
                                mark(if (endedObserved) "stt_cancel_ended_observed" else "stt_cancel_no_ended_callback")
                            }
                            runOnMainSync { input.close() }
                            awaitHostAudit("closed")
                            if (events.any { it is SpeechInputEvent.Transcript }) {
                                throw ProbeFailure("FAIL", "transcript_after_close")
                            }
                            mark("stt_host_audit_required")
                            return
                        }
                        // Diagnostic stop observation window, not a product-policy timer.
                        limit = deadline(if (probe == "stt-ready") 10 else 15)
                        if (probe == "stt-ready") {
                            stopRequested = true
                            runOnMainSync { input.stop() }
                            mark("stt_stop_requested")
                        }
                    }
                    SpeechInputEvent.Started -> mark("stt_started")
                    is SpeechInputEvent.Transcript -> {
                        // No transcript text/length or original audio is persisted by this harness.
                        if (event.isFinal && event.text.isNotBlank()) {
                            finalSeen = true
                            mark("stt_nonempty_final_received")
                        }
                    }
                    SpeechInputEvent.Ended -> {
                        mark("stt_ended")
                        if (probe == "stt-ready" && ready && stopRequested) return
                        if (probe == "stt-listen" && finalSeen) return
                        if (probe == "stt-listen") {
                            // A final result may follow the end callback; preserve the documented grace window.
                            val grace = SystemClock.elapsedRealtime() + 500
                            while (SystemClock.elapsedRealtime() < grace) {
                                val late = events.poll((grace - SystemClock.elapsedRealtime()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
                                if (late is SpeechInputEvent.Transcript && late.isFinal && late.text.isNotBlank()) {
                                    mark("stt_nonempty_final_after_end")
                                    return
                                }
                                if (late is SpeechInputEvent.Failed) {
                                    throw ProbeFailure("BLOCKED", "stt_${late.failure.code}_${late.failure.vendorCode ?: "none"}")
                                }
                            }
                            throw ProbeFailure("BLOCKED", "no_final_speech_result")
                        }
                        throw ProbeFailure("FAIL", "ended_before_ready")
                    }
                    is SpeechInputEvent.Failed -> throw ProbeFailure("BLOCKED", "stt_${event.failure.code}_${event.failure.vendorCode ?: "none"}")
                }
            }
        } finally {
            runOnMainSync { input.close() }
            // Instrumentation finish kills its process. Let the asynchronous SDK teardown run first.
            if (probe != "stt-cancel" && probe != "stt-close") SystemClock.sleep(1_100)
        }
    }

    private class ProbeFailure(val outcome: String, val code: String) : RuntimeException()
}
