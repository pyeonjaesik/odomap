package com.odomap.core.geo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

// Port of clientApp src/services/run/geo.ts (only what the explore pipeline needs) plus JS-number helpers.
// Numeric parity with the TypeScript original (V8):
//  - trig/log functions go through StrictMath (fdlibm — V8's Math.sin/cos/asin/atan/log/tan/sinh are fdlibm ports too,
//    while java.lang.Math may use CPU intrinsics that differ in the last bit),
//  - Math.hypot and Math.round use V8's exact algorithms (jsHypot / jsRound),
//  - constants keep the exact expression order of each TS file (e.g. geo.ts EARTH_RADIUS_M * (PI / 180) is NOT the same double as
//    exploreGraph.ts (6371008.8 * PI) / 180).

/** A latitude/longitude pair. */
interface LatLng {
    val lat: Double
    val lng: Double
}

data class GeoPoint(override val lat: Double, override val lng: Double) : LatLng

/** Mean earth radius (m, IUGG). */
const val EARTH_RADIUS_M = 6371008.8
const val RAD = Math.PI / 180

/** Length of one degree of latitude (m) — geo.ts: EARTH_RADIUS_M * RAD. */
const val METERS_PER_DEGREE = EARTH_RADIUS_M * RAD

/** Great-circle distance (m). */
fun haversineM(a: LatLng, b: LatLng): Double = haversineM(a.lat, a.lng, b.lat, b.lng)

fun haversineM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val sinLat = StrictMath.sin(((bLat - aLat) * RAD) / 2)
    val sinLng = StrictMath.sin(((bLng - aLng) * RAD) / 2)
    val h = sinLat * sinLat + StrictMath.cos(aLat * RAD) * StrictMath.cos(bLat * RAD) * sinLng * sinLng
    return 2 * EARTH_RADIUS_M * StrictMath.asin(min(1.0, sqrt(h)))
}

/** Length of one degree of longitude at that latitude (m). */
fun metersPerDegreeLng(lat: Double): Double = METERS_PER_DEGREE * StrictMath.cos(lat * RAD)

/** East (x) / north (y) metres from an origin. */
data class LocalXY(val x: Double, val y: Double)

/** Equirectangular projection around [origin] (geo.ts localProjection). */
class LocalProjection(private val originLat: Double, private val originLng: Double, refLat: Double = originLat) {
    private val kx = metersPerDegreeLng(refLat)
    private val ky = METERS_PER_DEGREE

    fun toXY(lat: Double, lng: Double): LocalXY = LocalXY((lng - originLng) * kx, (lat - originLat) * ky)

    fun toXY(p: LatLng): LocalXY = toXY(p.lat, p.lng)

    fun toLatLng(x: Double, y: Double): GeoPoint = GeoPoint(originLat + y / ky, originLng + (if (kx == 0.0) 0.0 else x / kx))
}

fun localProjection(origin: LatLng, refLat: Double = origin.lat): LocalProjection = LocalProjection(origin.lat, origin.lng, refLat)

/** JS Math.hypot(...values) exactly as V8 computes it (normalise by the largest, Kahan sum, sqrt × largest). */
fun jsHypot(vararg values: Double): Double {
    if (values.isEmpty()) return 0.0
    var max = 0.0
    var nan = false
    for (v in values) {
        if (v.isNaN()) nan = true else if (abs(v) > max) max = abs(v)
    }
    if (max == Double.POSITIVE_INFINITY) return Double.POSITIVE_INFINITY
    if (nan) return Double.NaN
    if (max == 0.0) return 0.0
    var sum = 0.0
    var compensation = 0.0
    for (v in values) {
        val n = abs(v) / max
        val summand = n * n - compensation
        val preliminary = sum + summand
        compensation = (preliminary - sum) - summand
        sum = preliminary
    }
    return sqrt(sum) * max
}

/** JS Math.round(x): halves go toward +Infinity (-2.5 → -2), 0.49999999999999994 → 0. */
fun jsRound(x: Double): Double {
    if (!x.isFinite()) return x
    val up = ceil(x)
    return if (up - 0.5 > x) up - 1.0 else up
}
