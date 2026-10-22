package com.odomap.app.location

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.odomap.app.model.Fix
import com.odomap.core.geo.haversineM
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pleos Playground FusedLocation SDK (ai.pleos.playground:FusedLocation 1.1.3).
 * Signatures checked against the AAR: FusedLocation(Context).isSupported()/initialize()/registerFusedLocationCallback(FusedLocationListener)/
 * unregisterFusedLocationCallback/release; LocationData(latitude, longitude, altitude, heading: Float, confidence: Float, timestampMillis).
 *
 * UNVERIFIED on hardware: the unit of `confidence` (taken as a horizontal accuracy radius in metres when > 1, else unknown), whether
 * initialize() must complete before registering (the SDK binds a service), and the callback rate. LocationData has no speed: it is derived
 * from consecutive fixes here.
 */
class PleosFusedLocationSource(private val context: Context) : LocationSource {
    override val name: String = "pleos-fused"

    override fun fixes(): Flow<Fix> =
        callbackFlow {
            val fused = FusedLocation(context.applicationContext)
            var prev: Fix? = null
            val listener =
                object : FusedLocationListener {
                    override fun onChanged(data: LocationData?) {
                        data ?: return
                        val fix = toFix(data, prev)
                        prev = fix
                        trySend(fix)
                    }

                    override fun onError(e: Exception) {
                        Log.w(TAG, "fused location error", e)
                    }
                }
            fused.initialize()
            fused.registerFusedLocationCallback(listener)
            awaitClose {
                runCatching { fused.unregisterFusedLocationCallback(listener) }
                runCatching { fused.release() }
            }
        }

    companion object {
        private const val TAG = "PleosFused"

        fun isAvailable(context: Context): Boolean =
            try {
                FusedLocation(context.applicationContext).isSupported()
            } catch (t: Throwable) {
                false
            }

        internal fun toFix(d: LocationData, prev: Fix?): Fix {
            val t = d.timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
            var speed: Float? = null
            var bearing: Float? = d.heading.takeIf { it.isFinite() && it >= 0f }
            if (prev != null && t > prev.t) {
                val m = haversineM(prev.lat, prev.lng, d.latitude, d.longitude)
                speed = (m / ((t - prev.t) / 1000.0)).toFloat()
                if (bearing == null && m > 2) bearing = bearingDeg(prev.lat, prev.lng, d.latitude, d.longitude)
            }
            val acc = d.confidence.takeIf { it.isFinite() && it > 1f }
            return Fix(t, d.latitude, d.longitude, acc, speed, bearing)
        }

        private fun bearingDeg(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
            val r = Math.PI / 180
            val y = sin((bLng - aLng) * r) * cos(bLat * r)
            val x = cos(aLat * r) * sin(bLat * r) - sin(aLat * r) * cos(bLat * r) * cos((bLng - aLng) * r)
            return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }
    }
}
