package com.odomap.app.drive

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import com.odomap.app.location.DriveStateSource
import com.odomap.app.model.DriveStatus
import com.odomap.app.model.Gear
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "DriveState"

/** AAOS car API present (automotive device + android.car shared library loadable). */
fun hasCarApi(context: Context): Boolean {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return false
    return try {
        Class.forName("android.car.Car")
        true
    } catch (t: Throwable) {
        false
    }
}

/** android.car VehicleGear values (same as Pleos VehicleGearValue) */
internal fun gearOfCarValue(v: Int): Gear =
    when (v) {
        4 -> Gear.PARK
        8 -> Gear.DRIVE
        2 -> Gear.REVERSE
        1 -> Gear.NEUTRAL
        0 -> Gear.UNKNOWN
        else -> if (v >= 16) Gear.DRIVE else Gear.UNKNOWN // GEAR_1..GEAR_9 (manual)
    }

internal fun drivingOf(gear: Gear) = gear != Gear.PARK && gear != Gear.UNKNOWN

/**
 * AAOS CarPropertyManager: GEAR_SELECTION (on change). Needs android.car.permission.CAR_POWERTRAIN. Only call when [hasCarApi].
 * PERF_VEHICLE_SPEED is read too for the log (CAR_SPEED, a dangerous permission) when granted.
 * UNVERIFIED on a Pleos head unit (no AAOS emulator image was available while building phase 1).
 */
class CarPropertyDriveStateSource(private val context: Context) : DriveStateSource {
    override val name: String = "car-property"

    override fun states(): Flow<DriveStatus> =
        callbackFlow {
            val car = Car.createCar(context.applicationContext) ?: throw IllegalStateException("Car.createCar returned null")
            val mgr = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            var last: Gear? = null
            val callback =
                object : CarPropertyManager.CarPropertyEventCallback {
                    override fun onChangeEvent(value: CarPropertyValue<*>) {
                        if (value.propertyId != VehiclePropertyIds.GEAR_SELECTION) return
                        val gear = gearOfCarValue((value.value as? Int) ?: return)
                        if (gear == last) return
                        last = gear
                        trySend(DriveStatus(System.currentTimeMillis(), gear, drivingOf(gear), name))
                    }

                    override fun onErrorEvent(propId: Int, zone: Int) {
                        Log.w(TAG, "car property error $propId/$zone")
                    }
                }
            @Suppress("DEPRECATION")
            mgr.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            awaitClose {
                @Suppress("DEPRECATION")
                runCatching { mgr.unregisterCallback(callback) }
                runCatching { car.disconnect() }
            }
        }
}

/**
 * Pleos Vehicle SDK (ai.pleos.playground:Vehicle 2.3.2): Vehicle(context).initialize(), getGear().registerGearSelection(GearSelectionListener)
 * (onSelected(VehicleGear) / onFailed(Exception)) — signatures checked against the AAR. Errors close the flow so the caller can fall back.
 * UNVERIFIED on hardware: whether registering right after initialize() works before the service is bound.
 */
class PleosVehicleDriveStateSource(private val context: Context) : DriveStateSource {
    override val name: String = "pleos-vehicle"

    override fun states(): Flow<DriveStatus> =
        callbackFlow {
            val vehicle = Vehicle(context.applicationContext)
            vehicle.initialize()
            var last: Gear? = null
            fun send(g: VehicleGear?) {
                val gear = if (g == null) Gear.UNKNOWN else gearOfPleos(g)
                if (gear == last) return
                last = gear
                trySend(DriveStatus(System.currentTimeMillis(), gear, drivingOf(gear), name))
            }
            val listener =
                object : GearSelectionListener {
                    override fun onSelected(gear: VehicleGear?) = send(gear)

                    override fun onFailed(e: Exception) {
                        Log.w(TAG, "pleos gear failed", e)
                        close(e)
                    }
                }
            vehicle.getGear().getCurrentGearSelectionState({ send(it) }, { Log.w(TAG, "pleos gear read failed", it) })
            vehicle.getGear().registerGearSelection(listener)
            awaitClose {
                runCatching { vehicle.getGear().unregisterGearSelection(listener) }
                runCatching { vehicle.release() }
            }
        }

    companion object {
        /** the Pleos vehicle service is installed (the SDK's <queries> action resolves) */
        fun isAvailable(context: Context): Boolean =
            try {
                context.packageManager.queryIntentServices(Intent("ai.pleos.playground.vehicle.VEHICLE_START"), 0).isNotEmpty()
            } catch (t: Throwable) {
                false
            }

        fun gearOfPleos(g: VehicleGear): Gear =
            when (g) {
                VehicleGear.GEAR_PARK -> Gear.PARK
                VehicleGear.GEAR_DRIVE -> Gear.DRIVE
                VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                else -> Gear.DRIVE
            }
    }
}
