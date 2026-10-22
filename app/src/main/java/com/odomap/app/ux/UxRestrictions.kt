package com.odomap.app.ux

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.util.Log
import com.odomap.app.drive.hasCarApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Whether the system requires distraction-optimized UI right now (AAOS CarUxRestrictionsManager). Emits nothing on devices without the car
 * API — the UI then uses our own drive state instead.
 * UNVERIFIED on a Pleos head unit (no AAOS image available while building phase 1).
 */
object UxRestrictions {
    fun flow(context: Context): Flow<Boolean> {
        if (!hasCarApi(context)) return emptyFlow()
        return try {
            carFlow(context)
        } catch (t: Throwable) {
            Log.w("UxRestrictions", "car ux restrictions unavailable", t)
            emptyFlow()
        }
    }

    private fun carFlow(context: Context): Flow<Boolean> =
        callbackFlow {
            val car = Car.createCar(context.applicationContext)
            val mgr = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            if (mgr == null) {
                car?.disconnect()
                close()
                return@callbackFlow
            }
            mgr.currentCarUxRestrictions?.let { trySend(it.isRequiresDistractionOptimization) }
            mgr.registerListener { r -> trySend(r.isRequiresDistractionOptimization) }
            awaitClose {
                runCatching { mgr.unregisterListener() }
                runCatching { car.disconnect() }
            }
        }
}
