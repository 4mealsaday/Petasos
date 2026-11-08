package com.petasos.app.diagnostics

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Metadata only: no recognition, audio source, synthesis, model download, Activity or product DB. */
object AndroidSpeechCapabilityProbe {
    class Failure(val code: String) : IllegalStateException(code)

    fun run(instrumentation: Instrumentation, mark: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) {
            mark("android_speech_capability_api33_available_false")
            return
        }
        mark("android_speech_capability_api33_available_true")
        inspect(instrumentation, mark)
    }

    @android.annotation.TargetApi(33)
    private fun inspect(instrumentation: Instrumentation, mark: (String) -> Unit) {
        // Match the instrumented process's attribution. The test APK declares service queries;
        // query counts describe this caller's visibility, not the device's full service inventory.
        val context = instrumentation.targetContext
        val manager = context.packageManager
        val flags = PackageManager.ResolveInfoFlags.of(0)
        val recognitionServices = manager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), flags)
        val ttsServices = manager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), flags)
        mark("android_speech_visible_recognizer_count_${recognitionServices.size}")
        mark("android_speech_visible_known_ambient_${recognitionServices.any {
            it.serviceInfo?.packageName == AMBIENT_PACKAGE && it.serviceInfo?.name == AMBIENT_SERVICE }}")
        mark("android_speech_visible_tts_count_${ttsServices.size}")
        val hasPleosTts = ttsServices.any { it.serviceInfo?.packageName == PLEOS_PACKAGE && it.serviceInfo?.name == PLEOS_SERVICE }
        mark("android_speech_visible_known_pleos_tts_$hasPleosTts")
        mark("android_speech_service_visibility_is_not_engine_or_privacy_acceptance")

        var recognitionAvailable = false
        var onDeviceAvailable = false
        instrumentation.runOnMainSync {
            recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context)
            onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }
        mark("android_speech_recognition_available_$recognitionAvailable")
        mark("android_speech_on_device_available_$onDeviceAvailable")
        var complete = true
        if (onDeviceAvailable) complete = inspectRecognition(instrumentation, context, mark) && complete
        else mark("android_speech_support_query_skipped_no_on_device_service")
        if (hasPleosTts) complete = inspectTts(instrumentation, context, mark) && complete
        else mark("android_speech_tts_query_skipped_no_known_visible_service")
        mark("android_speech_no_recognition_audio_pcm_synthesis_download_or_product_database_calls")
        mark("android_speech_capability_metadata_complete_$complete")
        if (!complete) throw Failure("android_speech_capability_metadata_incomplete")
    }

    @android.annotation.TargetApi(33)
    private fun inspectRecognition(instrumentation: Instrumentation, context: Context, mark: (String) -> Unit): Boolean {
        val response = ArrayBlockingQueue<SupportResult>(1)
        val active = AtomicBoolean(true)
        val responses = AtomicInteger()
        val unexpected = AtomicInteger()
        var recognizer: SpeechRecognizer? = null
        var created = false
        var cleaned = false
        var requested = false
        var result: SupportResult? = null
        try {
            instrumentation.runOnMainSync {
                try {
                    val service = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    recognizer = service
                    created = true
                    service.setRecognitionListener(NoRecognitionListener(unexpected, active))
                    service.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                    }, context.mainExecutor, object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (!active.get()) return
                            responses.incrementAndGet()
                            response.offer(SupportResult(support.installedOnDeviceLanguages.toList(),
                                support.pendingOnDeviceLanguages.toList(), support.supportedOnDeviceLanguages.toList()))
                        }
                        override fun onError(error: Int) {
                            if (!active.get()) return
                            responses.incrementAndGet()
                            response.offer(SupportResult(error = if (error in 1..15) error else 0))
                        }
                    })
                    requested = true
                } catch (_: Exception) { response.offer(SupportResult(error = -1)) }
            }
            if (requested) result = response.poll(CALLBACK_SECONDS, TimeUnit.SECONDS)
            else result = response.poll()
        } finally {
            active.set(false)
            instrumentation.runOnMainSync {
                // destroy cancels this object's pending work. No listening session was ever started,
                // so stopListening/cancel are neither capture evidence nor needed for this query.
                try { recognizer?.destroy(); cleaned = true } catch (_: Exception) { cleaned = false }
                recognizer = null
            }
            mark("android_speech_on_device_object_created_$created")
            mark("android_speech_support_requested_$requested")
            mark("android_speech_support_response_count_${responses.get()}")
            mark("android_speech_unrequested_recognition_callback_count_${unexpected.get()}")
            mark("android_speech_on_device_cleanup_complete_$cleaned")
        }
        val observed = result
        if (observed == null) mark("android_speech_support_callback_timeout")
        else if (observed.error != null) mark("android_speech_support_error_code_${observed.error}")
        else {
            languages("installed", observed.installed, mark)
            languages("pending", observed.pending, mark)
            languages("supported", observed.supported, mark)
        }
        // A supported intent does not prove AUDIO_SOURCE support, actual decoding, or absence of logs.
        return requested && observed != null && cleaned && responses.get() == 1 && unexpected.get() == 0
    }

    private fun inspectTts(instrumentation: Instrumentation, context: Context, mark: (String) -> Unit): Boolean {
        val initialized = ArrayBlockingQueue<Int>(1)
        val active = AtomicBoolean(true)
        val responses = AtomicInteger()
        var engine: TextToSpeech? = null
        var status: Int? = null
        var voicesRead = false
        var voiceCount = 0
        var koreanCount = 0
        var offlineKoreanCount = 0
        var installedOfflineKoreanCount = 0
        var cleaned = false
        try {
            instrumentation.runOnMainSync {
                try {
                    engine = TextToSpeech(context, { code ->
                        if (active.get()) { responses.incrementAndGet(); initialized.offer(code) }
                    }, PLEOS_PACKAGE)
                } catch (_: Exception) { initialized.offer(TextToSpeech.ERROR) }
            }
            status = initialized.poll(CALLBACK_SECONDS, TimeUnit.SECONDS)
            if (status == TextToSpeech.SUCCESS) instrumentation.runOnMainSync {
                try {
                    val voices = engine?.voices
                    if (voices != null) {
                        voiceCount = voices.size
                        val korean = voices.filter { it.locale.language == "ko" }
                        koreanCount = korean.size
                        val offline = korean.filterNot { it.isNetworkConnectionRequired }
                        offlineKoreanCount = offline.size
                        installedOfflineKoreanCount = offline.count { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                        voicesRead = true
                    }
                } catch (_: Exception) { voicesRead = false }
            }
        } finally {
            active.set(false)
            instrumentation.runOnMainSync {
                try { engine?.shutdown(); cleaned = true } catch (_: Exception) { cleaned = false }
                engine = null
            }
            mark("android_speech_tts_requested_known_pleos_engine")
            // The public constructor may fall back. A requested package is not binding identity proof.
            mark("android_speech_tts_bound_engine_identity_unverified")
            mark("android_speech_tts_init_response_count_${responses.get()}")
            mark("android_speech_tts_init_success_${status == TextToSpeech.SUCCESS}")
            mark("android_speech_tts_init_timeout_${status == null}")
            mark("android_speech_tts_voices_read_$voicesRead")
            mark("android_speech_tts_voice_count_$voiceCount")
            mark("android_speech_tts_korean_voice_count_$koreanCount")
            mark("android_speech_tts_offline_korean_voice_count_$offlineKoreanCount")
            mark("android_speech_tts_installed_offline_korean_voice_count_$installedOfflineKoreanCount")
            mark("android_speech_tts_cleanup_complete_$cleaned")
        }
        return status != null && responses.get() == 1 && cleaned && (status != TextToSpeech.SUCCESS || voicesRead)
    }

    private fun languages(kind: String, values: List<String>, mark: (String) -> Unit) {
        mark("android_speech_${kind}_language_count_${values.size}")
        mark("android_speech_${kind}_korean_count_${values.count { Locale.forLanguageTag(it).language == "ko" }}")
        mark("android_speech_${kind}_ko_kr_${values.any { it.equals("ko-KR", ignoreCase = true) }}")
    }

    private data class SupportResult(
        val installed: List<String> = emptyList(), val pending: List<String> = emptyList(),
        val supported: List<String> = emptyList(), val error: Int? = null,
    ) { override fun toString() = "SupportResult([redacted])" }

    private class NoRecognitionListener(private val unexpected: AtomicInteger, private val active: AtomicBoolean) : RecognitionListener {
        private fun received() { if (active.get()) unexpected.incrementAndGet() }
        override fun onReadyForSpeech(params: Bundle?) = received()
        override fun onBeginningOfSpeech() = received()
        override fun onRmsChanged(rmsdB: Float) = received()
        override fun onBufferReceived(buffer: ByteArray?) = received()
        override fun onEndOfSpeech() = received()
        override fun onError(error: Int) = received()
        override fun onResults(results: Bundle?) = received()
        override fun onPartialResults(partialResults: Bundle?) = received()
        override fun onEvent(eventType: Int, params: Bundle?) = received()
    }

    private const val CALLBACK_SECONDS = 12L
    private const val AMBIENT_PACKAGE = "ai.umos.ambientai"
    private const val AMBIENT_SERVICE = "ai.umos.ambientai.service.AmbRecognitionService"
    private const val PLEOS_PACKAGE = "ai.pleos.playground.caas"
    private const val PLEOS_SERVICE = "ai.pleos.playground.caas.service.speech.SpeechService"
}
