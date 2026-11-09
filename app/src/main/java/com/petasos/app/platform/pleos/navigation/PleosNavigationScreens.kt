package com.petasos.app.platform.pleos.navigation

import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.petasos.app.capability.navigation.NavigationScreenCommand
import com.petasos.app.capability.navigation.NavigationScreenGateway

/** Official NaviHelper screen requests only, with a separate lifetime from navigation read input. */
class PleosNavigationScreens(context: Context) : NavigationScreenGateway {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sdk = NaviHelper(app)
    private var initialized = false
    private var closed = false
    private var connected = false
    private var timeout: Runnable? = null

    override fun connect(onReady: () -> Unit, onFailed: () -> Unit) {
        requireMain()
        check(!closed && !initialized)
        initialized = true
        val fail = {
            if (!closed && !connected) { clearTimeout(); onFailed() }
        }
        timeout = Runnable { fail() }.also { main.postDelayed(it, 8_000) }
        try {
            sdk.initialize(onConnected = { main.post {
                if (!closed && !connected) {
                    clearTimeout(); connected = true; onReady()
                }
            } }, onFailed = { main.post { fail() } })
        } catch (_: Exception) { fail() }
    }

    @SuppressLint("MissingPermission") // Checked immediately before this public SDK command.
    override fun send(command: NavigationScreenCommand) {
        requireMain()
        check(!closed && connected)
        check(app.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED)
        val request = when (command) {
            NavigationScreenCommand.Foreground -> NavigationScreenRequest.Foreground
            NavigationScreenCommand.CurrentRoute -> NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo())
            is NavigationScreenCommand.Search -> NavigationScreenRequest.SearchPlace(keyword = command.keyword)
        }
        sdk.requestNavigationScreen(request)
    }

    override fun close() {
        requireMain()
        if (closed) return
        closed = true; connected = false; clearTimeout()
        if (initialized) try { sdk.release() } catch (_: Exception) { /* No SDK text is logged. */ }
    }
    private fun clearTimeout() { timeout?.let(main::removeCallbacks); timeout = null }
    private fun requireMain() { check(Looper.myLooper() === Looper.getMainLooper()) }
    companion object { const val PERMISSION = "pleos.car.permission.NAVI_CUSTOM_MAP" }
}
