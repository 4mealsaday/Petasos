package com.petasos.app.platform.pleos.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Read-only capability facts; inspection never requests a permission or changes navigation. */
object PleosCompanionDisplaySupport {
    data class Report(
        val overlayPermissionGranted: Boolean,
        val overlaySettingsAvailable: Boolean,
        val navigationSurfaceAvailable: Boolean = false,
    )

    fun inspect(context: Context): Report {
        val appContext = context.applicationContext
        val permissionGranted = try {
            Settings.canDrawOverlays(appContext)
        } catch (_: Exception) {
            false
        }
        val settingsAvailable = try {
            appContext.packageManager.resolveActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                PackageManager.MATCH_DEFAULT_ONLY,
            ) != null
        } catch (_: Exception) {
            false
        }
        return Report(
            overlayPermissionGranted = permissionGranted,
            overlaySettingsAvailable = settingsAvailable,
            // NaviHelper has no reviewed public contract for the actual navigation window's
            // visibility/bounds. A global Android overlay permission cannot supply that contract.
            navigationSurfaceAvailable = false,
        )
    }
}
