package com.petasos.app.platform.pleos.speech

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import com.petasos.app.capability.speech.SpeechFailureCode

internal const val SPEECH_SERVICE_PACKAGE = "ai.pleos.playground.caas"
internal const val INITIALIZATION_TIMEOUT_MS = 10_000L

internal fun requireSpeechMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
        "Speech adapter operations must run on the main thread."
    }
}

/** The service package must also be visible through the manifest queries declaration. */
internal fun speechEnvironmentFailure(context: Context, permission: String): SpeechFailureCode? {
    val service = try {
        context.packageManager.getApplicationInfo(SPEECH_SERVICE_PACKAGE, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        return SpeechFailureCode.MISSING_SERVICE
    }
    if (!service.enabled) return SpeechFailureCode.SERVICE_DISABLED
    if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        return SpeechFailureCode.PERMISSION_DENIED
    }
    return null
}
