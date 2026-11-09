package com.petasos.app

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.presentation.PlaybackControls
import com.petasos.app.presentation.TripControls

/** Thin parked-only screen; creating the Activity never starts playback, recording, or microphone. */
class MainActivity : Activity() {
    private lateinit var controls: PlaybackControls
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controls = PlaybackControls(this)
    }

    override fun onStart() { super.onStart(); controls.start() }
    override fun onResume() { super.onResume(); controls.setResumed(true) }
    override fun onPause() { controls.setResumed(false); super.onPause() }
    override fun onStop() { controls.stop(); super.onStop() }
    override fun onLowMemory() { controls.onLowMemory(); super.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { controls.saveTransientState(); super.onSaveInstanceState(outState) }
    override fun onDestroy() { controls.destroy(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A coarse-only grant is valid location access; callback array order is not authority.
        val granted = when (requestCode) {
            TripControls.LOCATION_REQUEST -> LocationAccess.hasRequiredPermissions(this)
            PlaybackControls.MICROPHONE_REQUEST ->
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
        controls.permissionResult(requestCode, granted)
    }
}
