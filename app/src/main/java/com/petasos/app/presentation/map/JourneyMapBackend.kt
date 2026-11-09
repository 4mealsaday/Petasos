package com.petasos.app.presentation.map

import android.view.View
import com.petasos.app.presentation.records.MapCoordinate

/** Only a display adapter: it cannot collect locations, authorize stories, or select a route. */
internal interface JourneyMapBackend {
    val view: View
    fun onCreate()
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
    fun onSaveInstanceState()
    fun setPosition(point: MapCoordinate?)
}

enum class JourneyMapStatus { OFFLINE, LOADING, READY, UNAVAILABLE }
