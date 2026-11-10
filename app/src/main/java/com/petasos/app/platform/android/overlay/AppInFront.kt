package com.petasos.app.platform.android.overlay

import android.os.Looper

/**
 * Main-thread flag: Petasos' own screen is in front. The overlay over navigation hides meanwhile,
 * since Home already shows the region and what is playing. Display only; playback is unaffected.
 */
object AppInFront {
    private val listeners = mutableSetOf<() -> Unit>()
    var value = false
        set(next) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (field == next) return
            field = next
            listeners.toList().forEach { it() }
        }

    fun add(listener: () -> Unit) { listeners += listener }
    fun remove(listener: () -> Unit) { listeners -= listener }
}
