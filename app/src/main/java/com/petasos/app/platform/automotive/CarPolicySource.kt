package com.petasos.app.platform.automotive

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.power.CarPowerManager
import android.car.hardware.power.CarPowerPolicyFilter
import android.car.hardware.power.PowerComponent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * Main-thread, single-owner subscription to public AAOS UX and component-power policy APIs.
 * Initialization, disconnection, read/registration failure and close all publish unavailable.
 * No hidden power-state listener, speed inference, automatic playback or microphone start exists.
 */
class CarPolicySource(
    context: Context,
    private val onUx: (VehicleUxPolicy) -> Unit,
    private val onPower: (VehiclePowerPolicy) -> Unit,
) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { runnable -> main.post(runnable) }
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var powerManager: CarPowerManager? = null
    private var powerListener: CarPowerManager.CarPowerPolicyListener? = null
    private var generation = 0L
    private var started = false
    private var closed = false

    fun start() {
        requireMain()
        if (closed || started) return
        started = true
        publishUnavailable()
        // The verified Pleos image is API 34. Earlier images remain explicitly unavailable;
        // never silently drop the power-policy guard if those public APIs are missing.
        if (Build.VERSION.SDK_INT < 33 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
        try {
            val connection = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                if (closed) return@createCar
                if (ready) attach(connectedCar) else {
                    detachManagers()
                    publishUnavailable()
                }
            }
            if (closed) connection?.disconnect() else car = connection
            if (connection == null) publishUnavailable()
        } catch (_: Exception) {
            detachManagers()
            publishUnavailable()
        } catch (_: LinkageError) {
            detachManagers()
            publishUnavailable()
        }
    }

    private fun attach(connection: Car) {
        requireMain()
        detachManagers()
        publishUnavailable()
        val epoch = generation
        try {
            val manager = connection.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager = manager
            if (manager != null) {
                // Apply the initial restriction before registering; re-read after registration to
                // close the read/subscribe gap. Notifications also re-read current authoritative state.
                refreshUx(manager)
                manager.registerListener {
                    if (!closed && generation == epoch && uxManager === manager) refreshUx(manager)
                }
                refreshUx(manager)
            }
        } catch (_: Exception) {
            val failed = uxManager
            uxManager = null
            try { failed?.unregisterListener() } catch (_: Exception) { /* Ignore stale callbacks. */ }
            onUx(VehicleUxPolicy.unavailable())
        } catch (_: LinkageError) {
            uxManager = null
            onUx(VehicleUxPolicy.unavailable())
        }
        try {
            if (context.checkSelfPermission(Car.PERMISSION_READ_CAR_POWER_POLICY) != PackageManager.PERMISSION_GRANTED) {
                onPower(VehiclePowerPolicy.unavailable())
                return
            }
            val manager = connection.getCarManager(Car.POWER_SERVICE) as? CarPowerManager
            powerManager = manager
            if (manager != null) {
                val listener = CarPowerManager.CarPowerPolicyListener {
                    // The callback policy may describe a partial change. currentPowerPolicy is
                    // the documented accumulated state of every power component.
                    if (!closed && generation == epoch && powerManager === manager) refreshPower(manager)
                }
                powerListener = listener
                refreshPower(manager)
                manager.addPowerPolicyListener(
                    executor,
                    CarPowerPolicyFilter.Builder().setComponents(PowerComponent.AUDIO, PowerComponent.MICROPHONE).build(),
                    listener,
                )
                refreshPower(manager)
            }
        } catch (_: Exception) {
            val failed = powerManager
            val listener = powerListener
            powerManager = null
            powerListener = null
            if (listener != null) {
                try { failed?.removePowerPolicyListener(listener) } catch (_: Exception) { /* Ignore stale callbacks. */ }
            }
            onPower(VehiclePowerPolicy.unavailable())
        } catch (_: LinkageError) {
            powerManager = null
            powerListener = null
            onPower(VehiclePowerPolicy.unavailable())
        }
    }

    private fun refreshUx(manager: CarUxRestrictionsManager) {
        val value = try {
            manager.currentCarUxRestrictions?.let {
                VehicleUxPolicy(
                    true, it.isRequiresDistractionOptimization, it.activeRestrictions,
                    it.maxRestrictedStringLength.coerceAtLeast(0),
                    it.maxCumulativeContentItems.coerceAtLeast(0), it.maxContentDepth.coerceAtLeast(0),
                )
            } ?: VehicleUxPolicy.unavailable()
        } catch (_: Exception) { VehicleUxPolicy.unavailable() }
        catch (_: LinkageError) { VehicleUxPolicy.unavailable() }
        onUx(value)
    }

    private fun refreshPower(manager: CarPowerManager) {
        val value = try {
            manager.currentPowerPolicy?.let {
                VehiclePowerPolicy(true, it.isComponentEnabled(PowerComponent.AUDIO), it.isComponentEnabled(PowerComponent.MICROPHONE))
            } ?: VehiclePowerPolicy.unavailable()
        } catch (_: Exception) { VehiclePowerPolicy.unavailable() }
        catch (_: LinkageError) { VehiclePowerPolicy.unavailable() }
        onPower(value)
    }

    private fun detachManagers() {
        generation++
        val oldUx = uxManager
        val oldPower = powerManager
        val oldListener = powerListener
        uxManager = null
        powerManager = null
        powerListener = null
        try { oldUx?.unregisterListener() } catch (_: Exception) { /* Already unavailable. */ }
        if (oldListener != null) {
            try { oldPower?.removePowerPolicyListener(oldListener) } catch (_: Exception) { /* Already unavailable. */ }
        }
    }

    override fun close() {
        requireMain()
        if (closed) return
        closed = true
        detachManagers()
        val connection = car
        car = null
        try { connection?.disconnect() } catch (_: Exception) { /* Fail closed regardless. */ }
        publishUnavailable()
    }

    private fun publishUnavailable() {
        onUx(VehicleUxPolicy.unavailable())
        onPower(VehiclePowerPolicy.unavailable())
    }
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
