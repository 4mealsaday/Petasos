package com.petasos.app.presentation.map

import android.content.Context

/** The permissionless demo cannot load a native network map or contain map SDK credentials. */
internal object ProductMapBackendFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, onReady: () -> Unit, onFailure: () -> Unit): JourneyMapBackend? = null
}
