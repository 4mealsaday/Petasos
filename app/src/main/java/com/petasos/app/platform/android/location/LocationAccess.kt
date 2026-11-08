package com.petasos.app.platform.android.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.petasos.app.platform.pleos.location.PleosLocationSource

/** Permission checks only; saved consent, foreground visibility and Pleos blocking are separate gates. */
object LocationAccess {
    fun hasPleosPermission(context: Context): Boolean =
        context.checkSelfPermission(PleosLocationSource.PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun hasAndroidLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasRequiredPermissions(context: Context): Boolean =
        hasPleosPermission(context) && hasAndroidLocationPermission(context)

    /** Each stage needs its own user gesture. Android may grant approximate access only. */
    fun requestPermissions(context: Context): Array<String> = when {
        !hasPleosPermission(context) -> arrayOf(PleosLocationSource.PERMISSION)
        !hasAndroidLocationPermission(context) -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        else -> emptyArray()
    }
}
