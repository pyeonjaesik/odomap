package com.odomap.core.run

import com.odomap.core.geo.LatLng

// Port of the parts of clientApp src/services/run/runTrack.ts the explore pipeline needs: RUN_FILTER, RawPoint, RunPause.
// All numbers are Double like JS numbers (same comparison / division order).

/**
 * Position-filter constants (runTrack.ts RUN_FILTER — values unchanged). Injectable: pass a copy to [PathSmoother] /
 * [com.odomap.core.explore.ExplorePipelineConfig] for a car-specific rule set later.
 */
data class RunFilter(
    val maxAccuracyM: Double = 25.0,
    val firstPointMaxAccuracyM: Double = 20.0,
    val maxSpeedRunMps: Double = 10.0,
    val maxSpeedWalkMps: Double = 5.0,
    val outlierRecoveryCount: Double = 5.0,
    val processNoise: Double = 0.1,
    val processNoiseSpeedK: Double = 0.15,
    val accuracyToSigma: Double = 0.67,
    val minPositionSigmaM: Double = 1.5,
    val maxPositionSigmaM: Double = 25.0,
    val defaultAccuracyM: Double = 10.0,
    val robustGate: Double = 3.0,
    val hardGate: Double = 5.0,
    val hardMinM: Double = 20.0,
    val dopplerAiding: Double = 1.0,
    val dopplerMinSpeedMps: Double = 0.5,
    val dopplerDefaultSigmaMps: Double = 0.5,
    val dopplerBodySigmaMps: Double = 0.3,
    val dopplerCourseSigmaDeg: Double = 5.0,
    val dopplerCheckWindowS: Double = 30.0,
    val dopplerCheckMinMoveM: Double = 25.0,
    val dopplerMismatchLimit: Double = 10.0,
    val stillSpeedMps: Double = 0.5,
    val stillMaxSpeedMps: Double = 0.9,
    val stillCount: Double = 3.0,
    val stillSigmaMps: Double = 0.1,
    val stillBreakM: Double = 30.0,
    val initialSpeedSigmaMps: Double = 10.0,
    val smoothingLag: Double = 10.0,
    val gapResetS: Double = 10.0,
    val weakAccuracyM: Double = 15.0,
    val weakGapS: Double = 5.0,
) {
    companion object {
        /** The phone app's values (runTrack.ts RUN_FILTER). */
        val DEFAULT = RunFilter()
    }
}

val RUN_FILTER = RunFilter.DEFAULT

/** One raw location fix (runTrack.ts RawPoint). t is ms epoch, a Double like in JS. */
data class RawPoint(
    override val lat: Double,
    override val lng: Double,
    val t: Double,
    /** horizontal accuracy radius (m) */
    val acc: Double? = null,
    val alt: Double? = null,
    /** device (GNSS Doppler) speed, m/s */
    val speed: Double? = null,
    /** heading, degrees clockwise from north */
    val course: Double? = null,
    /** speed accuracy, m/s */
    val sacc: Double? = null,
    val vacc: Double? = null,
) : LatLng

/** A user pause; end == null while still paused. */
data class RunPause(val start: Double, var end: Double?)

internal fun accuracyOf(acc: Double?): Double? = if (acc != null && acc.isFinite() && acc >= 0) acc else null

internal fun finite(value: Double?): Boolean = value != null && value.isFinite()
