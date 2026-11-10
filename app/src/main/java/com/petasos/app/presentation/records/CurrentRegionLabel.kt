package com.petasos.app.presentation.records

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.petasos.app.platform.android.runtime.PetasosSessionHost
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Display-only "서울 중구": names the Host's currently confirmed region from the public display shapes.
 * It reads nothing new (no location, no capture) and keeps the last name while no region is confirmed.
 */
class CurrentRegionLabel(
    private val context: Context,
    val host: PetasosSessionHost,
    private val onChange: (String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    @Volatile private var shapes = emptyList<DisplayRegionShape>()
    private var running = false
    var label: String? = null
        private set
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        val executor = Executors.newSingleThreadExecutor().also { worker = it }
        executor.execute {
            val loaded = try { RecordsGeometryCatalog.load(context) } catch (_: Exception) { emptyList() }
            handler.post { if (running) { shapes = loaded; poll() } }
        }
        tick.run()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        worker?.shutdownNow(); worker = null
    }

    private fun poll() {
        val id = host.displayRegionId() ?: return
        val shape = shapes.firstOrNull { it.regionId == id } ?: return
        val name = RegionLocator.displayName(shape)
        if (name != label) { label = name; onChange(name) }
    }

    private companion object { const val POLL_MS = 2_000L }
}
