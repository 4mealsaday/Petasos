package com.petasos.app.capability.navigation

/** These commands open Pleos-owned screens. None sets, cancels or substitutes a route. */
sealed class NavigationScreenCommand {
    data object Foreground : NavigationScreenCommand()
    data object CurrentRoute : NavigationScreenCommand()
    class Search(val keyword: String) : NavigationScreenCommand() {
        init { require(keyword.isNotBlank() && keyword.length <= 100) }
        override fun toString() = "NavigationScreenCommand.Search([redacted])"
    }
}

enum class NavigationScreenCommandState { IDLE, CONNECTING, REQUESTED, UNAVAILABLE, PERMISSION_DENIED }

interface NavigationScreenGateway : AutoCloseable {
    fun connect(onReady: () -> Unit, onFailed: () -> Unit)
    fun send(command: NavigationScreenCommand)
    override fun close()
}

/**
 * A click owns one connection and one request. Hiding/replacing the UI cancels that ownership;
 * a late SDK connection must never open Navigation on behalf of a hidden or newer screen.
 * REQUESTED means the SDK call returned, not that Navigation displayed or changed its route.
 * All calls and gateway callbacks run on the same owner thread.
 */
class NavigationScreenCommands(
    private val permissionGranted: () -> Boolean,
    private val createGateway: () -> NavigationScreenGateway,
    private val onState: (NavigationScreenCommandState) -> Unit,
) : AutoCloseable {
    private class Request(val gateway: NavigationScreenGateway, var command: NavigationScreenCommand?)
    private var request: Request? = null
    private var available = false
    private var closed = false
    var state = NavigationScreenCommandState.IDLE
        private set

    fun setAvailable(value: Boolean) {
        if (closed || available == value) return
        available = value
        if (!value) cancel()
        publish(NavigationScreenCommandState.IDLE)
    }

    fun request(command: NavigationScreenCommand): Boolean {
        if (!available || closed) return false
        cancel()
        if (!hasPermission()) { publish(NavigationScreenCommandState.PERMISSION_DENIED); return false }
        val gateway = try { createGateway() } catch (_: Exception) {
            publish(NavigationScreenCommandState.UNAVAILABLE); return false
        }
        val owned = Request(gateway, command)
        request = owned
        publish(NavigationScreenCommandState.CONNECTING)
        if (request !== owned) return false
        try { gateway.connect(onReady = { connected(owned) }, onFailed = { failed(owned) }) }
        catch (_: Exception) { failed(owned) }
        return request === owned
    }

    private fun connected(owned: Request) {
        if (request !== owned || !available || closed) return
        if (!hasPermission()) {
            cancel(); publish(NavigationScreenCommandState.PERMISSION_DENIED); return
        }
        val command = owned.command ?: return // Repeated connected callbacks cannot repeat the click.
        owned.command = null
        try {
            owned.gateway.send(command)
            if (request === owned && available && !closed) publish(NavigationScreenCommandState.REQUESTED)
        } catch (_: Exception) { failed(owned) }
    }

    private fun failed(owned: Request) {
        if (request !== owned || closed) return
        cancel()
        publish(if (hasPermission()) NavigationScreenCommandState.UNAVAILABLE else NavigationScreenCommandState.PERMISSION_DENIED)
    }

    private fun hasPermission() = try { permissionGranted() } catch (_: Exception) { false }
    private fun cancel() {
        val old = request ?: return
        request = null
        old.command = null
        try { old.gateway.close() } catch (_: Exception) { /* No private SDK error crosses this boundary. */ }
    }
    private fun publish(value: NavigationScreenCommandState) { state = value; onState(value) }
    override fun close() {
        if (closed) return
        closed = true; available = false; cancel()
    }
}
