package com.odomap.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.odomap.app.model.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Fallback: Android LocationManager (fused provider on API 31+, else GPS), 1 s. Needs ACCESS_FINE_LOCATION (checked by the caller). */
class AndroidLocationSource(private val context: Context) : LocationSource {
    override val name: String = "android"

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<Fix> =
        callbackFlow {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider =
                when {
                    Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    lm.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> LocationManager.NETWORK_PROVIDER
                }
            val listener = LocationListener { loc: Location -> trySend(loc.toFix()) }
            lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
            awaitClose { lm.removeUpdates(listener) }
        }
}

fun Location.toFix(): Fix =
    Fix(
        t = time,
        lat = latitude,
        lng = longitude,
        acc = if (hasAccuracy()) accuracy else null,
        speed = if (hasSpeed()) speed else null,
        bearing = if (hasBearing()) bearing else null,
    )
