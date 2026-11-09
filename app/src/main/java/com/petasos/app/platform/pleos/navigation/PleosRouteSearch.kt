package com.petasos.app.platform.pleos.navigation

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One-shot, logging-only destination request. Outcome (success, failure, or no permission) never
 * blocks or delays the companion flow that triggers it -- this only kicks routing off and logs it.
 */
object PleosRouteSearch {
    private const val TAG = "PleosRouteSearch"
    const val PERMISSION = "pleos.car.permission.NAVI_ROUTE_SEARCH"
    private const val RELEASE_TIMEOUT_MS = 20_000L
    private const val RELEASE_GRACE_MS = 2_000L

    @SuppressLint("MissingPermission") // Checked immediately before this one SDK command.
    fun request(context: Context, poiName: String, latitude: Double, longitude: Double) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        if (app.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "requestRoute skipped: $PERMISSION not granted")
            return
        }
        val sdk = try { NaviHelper(app) } catch (error: Exception) {
            Log.w(TAG, "NaviHelper construction failed", error); return
        }
        var released = false
        val release = Runnable {
            if (released) return@Runnable
            released = true
            try { sdk.release() } catch (_: Exception) { /* No private SDK error crosses this boundary. */ }
        }
        main.postDelayed(release, RELEASE_TIMEOUT_MS)
        val listener = object : NaviHelperEventListener {
            override fun onRouteStarted(info: RouteStartInfo) {
                Log.i(TAG, "onRouteStarted: distance=${info.distance} duration=${info.duration}")
            }
            override fun onRoutingCompleted() {
                Log.i(TAG, "onRoutingCompleted")
                main.postDelayed(release, RELEASE_GRACE_MS)
            }
            override fun onError(code: NaviErrorCode) {
                Log.w(TAG, "onError: $code")
                main.postDelayed(release, RELEASE_GRACE_MS)
            }
        }
        try {
            sdk.addListener(listener)
            sdk.initialize(onConnected = {
                main.post {
                    try {
                        sdk.requestRoute(RouteInfo(
                            latitude = latitude, longitude = longitude, poiName = poiName,
                            poiId = "", address = "", poiSubId = "",
                        ))
                    } catch (error: Exception) {
                        Log.w(TAG, "requestRoute threw", error)
                        main.post(release)
                    }
                }
            }, onFailed = {
                main.post {
                    Log.w(TAG, "NaviHelper connect failed for route request")
                    release.run()
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "requestRoute setup threw", error)
            main.post(release)
        }
    }
}
