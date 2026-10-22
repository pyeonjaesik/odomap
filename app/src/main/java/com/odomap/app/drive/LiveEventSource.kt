package com.odomap.app.drive

import android.content.Context
import android.util.Log
import com.odomap.app.location.AndroidLocationSource
import com.odomap.app.location.DriveStateSource
import com.odomap.app.location.EventSource
import com.odomap.app.location.LocationSource
import com.odomap.app.location.PleosFusedLocationSource
import com.odomap.app.model.DriveStatus
import com.odomap.app.model.SourceEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/** Gear state from fixes when the car gives no gear signal (see [SpeedDriveDetector]). */
class SpeedDriveStateSource(private val fixes: LocationSource) : DriveStateSource {
    override val name: String = SpeedDriveDetector.SOURCE

    override fun states(): Flow<DriveStatus> {
        val detector = SpeedDriveDetector()
        return fixes.fixes().mapNotNull { detector.update(it) }
    }
}

/**
 * The real car: location from Pleos FusedLocation (else Android LocationManager), drive state from AAOS CarPropertyManager GEAR_SELECTION
 * (else the Pleos Vehicle gear listener, else the speed rule). A gear source that fails falls back to the next one.
 */
class LiveEventSource(private val context: Context) : EventSource {
    val location: LocationSource =
        if (PleosFusedLocationSource.isAvailable(context)) PleosFusedLocationSource(context) else AndroidLocationSource(context)

    override val name: String get() = "live:${location.name}"

    override fun events(): Flow<SourceEvent> =
        channelFlow {
            // one location subscription shared by the recorder and (fallback) the speed rule
            val fixes = MutableSharedFlow<com.odomap.app.model.Fix>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
            val shared =
                object : LocationSource {
                    override val name = location.name

                    override fun fixes() = fixes
                }
            val chain = ArrayList<DriveStateSource>()
            // Pleos marks its gear getter deprecated in favour of the AAOS GEAR_SELECTION property → CarPropertyManager first
            if (hasCarApi(context)) chain.add(CarPropertyDriveStateSource(context))
            if (PleosVehicleDriveStateSource.isAvailable(context)) chain.add(PleosVehicleDriveStateSource(context))
            chain.add(SpeedDriveStateSource(shared))
            launch {
                fallbackStates(chain, 0).collect { send(SourceEvent.State(it)) }
            }
            location.fixes().collect { fix ->
                send(SourceEvent.Loc(fix))
                fixes.emit(fix)
            }
        }

    private fun fallbackStates(chain: List<DriveStateSource>, i: Int): Flow<DriveStatus> {
        val source = chain[i]
        val flow = source.states()
        if (i == chain.size - 1) return flow
        return flow.catch { e ->
            Log.w("LiveEventSource", "${source.name} failed, falling back to ${chain[i + 1].name}", e)
            emitAll(fallbackStates(chain, i + 1))
        }
    }
}
