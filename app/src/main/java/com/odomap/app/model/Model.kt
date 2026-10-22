package com.odomap.app.model

import com.odomap.core.run.RawPoint

/** One location fix from any source. t = ms epoch, acc = horizontal accuracy (m), speed m/s, bearing degrees clockwise from north. */
data class Fix(
    val t: Long,
    val lat: Double,
    val lng: Double,
    val acc: Float? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
) {
    fun toRawPoint(): RawPoint =
        RawPoint(lat = lat, lng = lng, t = t.toDouble(), acc = acc?.toDouble(), speed = speed?.toDouble(), course = bearing?.toDouble())
}

enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

/**
 * Drive state at time [t] (ms epoch, the time the state began). driving = a drive is in progress (gear out of P, or moving by the speed rule).
 * [source] names where it came from (for the developer panel).
 */
data class DriveStatus(val t: Long, val gear: Gear, val driving: Boolean, val source: String)

/** One event of the recorder's input stream. Location and state events of one source keep their order. */
sealed interface SourceEvent {
    data class Loc(val fix: Fix) : SourceEvent

    data class State(val status: DriveStatus) : SourceEvent

    /** a demo drive begins (replay only) — the next drive gets this id / title */
    data class DemoBegin(val demoId: String, val title: String) : SourceEvent
}

/** A finished drive (stored summary). Lengths in metres, times ms epoch. */
data class DriveRecord(
    val id: String,
    val title: String?,
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val pioneeredM: Double,
    /** edges passed / created by this drive */
    val edgeCount: Int,
    val newEdgeCount: Int,
    /** edges passed two or more times within the drive, and the most passes on one edge */
    val repassEdges: Int,
    val maxPasses: Int,
    val fixCount: Int,
    /** "replay:<demo id>" or "live:<location source>" */
    val source: String,
    val bounds: Bounds?,
)

data class Bounds(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double) {
    fun extend(lat: Double, lng: Double): Bounds = Bounds(minOf(minLat, lat), minOf(minLng, lng), maxOf(maxLat, lat), maxOf(maxLng, lng))

    companion object {
        fun of(lat: Double, lng: Double) = Bounds(lat, lng, lat, lng)
    }
}
