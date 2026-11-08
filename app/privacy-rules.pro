# Diagnostic build only: retain symbols while allowing SDK method-body optimization.
# Do not use -dontoptimize or proguard-android.txt; DEX inspection must confirm sink removal.
-dontshrink
-dontobfuscate
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# The separate test APK calls app APIs that production entry points may not reference.
# Keep the app state machine unchanged while isolating the SDK logging transformation.
-keep class com.petasos.app.** { *; }

# SpeechToText 3.3.1 Gson.fromJson targets and their nested lists/enums. These classes
# require field names, generic signatures, constructors, and serialization annotations.
# No allowoptimization: reflective models must not be constant-folded or merged.
-keep class ai.pleos.playground.stt.constant.ConversationInfo { *; }
-keep class ai.pleos.playground.stt.constant.SttFinalResult { *; }
-keep class ai.pleos.playground.stt.data.** { *; }

# TextToSpeech 3.3.1 serializes AudioInfo.zoneId from this separate CAAS package.
-keep class ai.pleos.playground.caas.service.speech.data.tts.AudioInfo { *; }
-keep class ai.pleos.playground.caas.service.speech.data.tts.AudioAttributesParcel { *; }
-keep class ai.pleos.playground.caas.service.speech.data.tts.AudioAttributesParcel$CREATOR { *; }

# -dontshrink retains Tink's unused KeysDownloader utility and its nested helpers.
# Its only references to these absent HTTP/Joda types are the optional downloader
# implementation; neither app nor cached runtime libraries call that utility.
# Do not widen these rules or use KeysDownloader without adding its real dependencies.
-dontwarn com.google.api.client.http.GenericUrl
-dontwarn com.google.api.client.http.HttpHeaders
-dontwarn com.google.api.client.http.HttpRequest
-dontwarn com.google.api.client.http.HttpRequestFactory
-dontwarn com.google.api.client.http.HttpResponse
-dontwarn com.google.api.client.http.HttpTransport
-dontwarn com.google.api.client.http.javanet.NetHttpTransport$Builder
-dontwarn com.google.api.client.http.javanet.NetHttpTransport
-dontwarn org.joda.time.Instant

# Both exact 3.3.1 AARs forward raw recognition/speak text to this DEBUG signature
# and discard its return value. Keep all SDK control/parsing/binder operations intact.
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
}

# SttViewModel.notifySttChanged logs raw recognized text at INFO; notifyError logs
# the opaque service error before mapping it to ErrorType. This exact SDK helper
# only adds a thread prefix and forwards to Log.i; it performs no SDK control work.
-assumenosideeffects class ai.pleos.playground.stt.utils.Log {
    public final void c(java.lang.String, java.lang.String);
    # OnDeviceStt bind-failure cleanup appends the opaque unbind Throwable.
    public final void d(java.lang.String, java.lang.String);
}

# WARN is helper d (not the DEBUG helper a). TTS binding cleanup appends an opaque
# Throwable, and TtsCallback appends an unrecognized service event type. Suppress
# only this pure logger helper; retain fixed ERROR and current TTS INFO diagnostics.
-assumenosideeffects class ai.pleos.playground.tts.utils.Log {
    public final void d(java.lang.String, java.lang.String);
}

# SttCallback.onResponse also prints a caught Throwable at bytecode offset 1228.
# This removes matching diagnostic output, not exception propagation or error callbacks.
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}
