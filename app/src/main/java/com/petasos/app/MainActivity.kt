package com.petasos.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.content.pm.PackageManager
import com.petasos.app.platform.android.location.LocationAccess
import com.petasos.app.presentation.CompanionControls
import com.petasos.app.presentation.PlaybackControls
import com.petasos.app.presentation.TripControls

/** Thin parked-only screen; creating the Activity never starts playback, recording, or microphone. */
class MainActivity : Activity() {
    private lateinit var controls: PlaybackControls

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.petasos.app.presentation.DesignScale.wrap(newBase, newBase.resources.configuration.screenWidthDp))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controls = PlaybackControls(this)
    }

    override fun onStart() { super.onStart(); controls.start() }
    override fun onResume() { super.onResume(); com.petasos.app.platform.android.overlay.AppInFront.value = true; controls.setResumed(true); com.petasos.app.generation.DemoServerSetup.start(this) }
    override fun onPause() { com.petasos.app.platform.android.overlay.AppInFront.value = false; controls.setResumed(false); super.onPause() }
    override fun onStop() { controls.stop(); super.onStop() }
    override fun onLowMemory() { controls.onLowMemory(); super.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { controls.saveTransientState(); super.onSaveInstanceState(outState) }
    override fun onDestroy() { controls.destroy(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A coarse-only grant is valid location access; callback array order is not authority.
        val granted = when (requestCode) {
            TripControls.LOCATION_REQUEST -> LocationAccess.hasRequiredPermissions(this)
            CompanionControls.PERMISSION_REQUEST, CompanionControls.READINESS_REQUEST ->
                permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
            else -> false
        }
        controls.permissionResult(requestCode, granted)
    }
}
