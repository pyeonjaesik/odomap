package com.odomap.app.engine

import com.odomap.app.model.Bounds
import com.odomap.core.explore.ExploreEdge
import com.odomap.core.explore.ExploreLinesOptions
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.explore.exploreLines
import java.util.Locale

/** Drawn-line options: smoothed (drawing only), tiny stubs dropped. */
val MAP_LINE_OPTIONS = ExploreLinesOptions(smooth = true, simplifyM = 1.0)

const val EMPTY_FEATURES = """{"type":"FeatureCollection","features":[]}"""

/**
 * Edges → GeoJSON FeatureCollection, one MultiLineString feature per thickness level (property "level" 1..5).
 * Built by hand (no JSON library) — the net can hold tens of thousands of edges and this runs every second while driving.
 */
fun edgesToGeoJson(edges: Iterable<ExploreEdge>): String {
    val lines = exploreLines(edges, MAP_LINE_OPTIONS)
    if (lines.isEmpty()) return EMPTY_FEATURES
    val byLevel = lines.groupBy { it.level }
    val sb = StringBuilder(64 + lines.sumOf { it.coords.size } * 24)
    sb.append("""{"type":"FeatureCollection","features":[""")
    var firstFeature = true
    for (level in byLevel.keys.sorted()) {
        if (!firstFeature) sb.append(',')
        firstFeature = false
        sb.append("""{"type":"Feature","properties":{"level":""").append(level)
        sb.append("""},"geometry":{"type":"MultiLineString","coordinates":[""")
        var firstLine = true
        for (line in byLevel[level]!!) {
            if (line.coords.size < 2) continue
            if (!firstLine) sb.append(',')
            firstLine = false
            sb.append('[')
            line.coords.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append('[').append(fmt6(p.lng)).append(',').append(fmt6(p.lat)).append(']')
            }
            sb.append(']')
        }
        sb.append("]}}")
    }
    sb.append("]}")
    return sb.toString()
}

/** A point feature (car puck). */
fun pointGeoJson(lat: Double, lng: Double, bearing: Float?): String =
    """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"bearing":${bearing ?: 0f}},"geometry":{"type":"Point","coordinates":[${fmt6(lng)},${fmt6(lat)}]}}]}"""

/** fixed 6 decimals without String.format (hot path) */
internal fun fmt6(v: Double): String {
    if (!v.isFinite()) return "0"
    val scaled = Math.round(v * 1e6)
    val neg = scaled < 0
    val abs = if (neg) -scaled else scaled
    val frac = (abs % 1_000_000).toString()
    val sb = StringBuilder(16)
    if (neg) sb.append('-')
    sb.append(abs / 1_000_000).append('.')
    for (i in frac.length until 6) sb.append('0')
    sb.append(frac)
    return sb.toString()
}

fun formatKm(m: Double): String = String.format(Locale.US, "%.1f", m / 1000.0)

fun edgeLengthM(e: ExploreEdge): Double = exploreDistanceM(e.aLat, e.aLng, e.bLat, e.bLng)

fun boundsOfEdges(edges: Iterable<ExploreEdge>): Bounds? {
    var b: Bounds? = null
    for (e in edges) {
        b = (b ?: Bounds.of(e.aLat, e.aLng)).extend(e.aLat, e.aLng).extend(e.bLat, e.bLng)
    }
    return b
}
