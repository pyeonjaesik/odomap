package com.odomap.core.car

import com.odomap.core.explore.EXPLORE_METERS_PER_DEGREE
import com.odomap.core.explore.EXPLORE_RAD
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExplorePipelineConfig
import com.odomap.core.explore.ExploreWalkSummary
import com.odomap.core.explore.OnFootSample
import com.odomap.core.explore.computeExploreWalk
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.explore.exploreLineLevel
import com.odomap.core.geo.GeoPoint
import com.odomap.core.run.RawPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Simulated drives (tools/routes/gen_routes.py output, gzipped as-is under test resources drives/) and their noise-free OSRM geometry
// (drives/truth/) for the car tests.

/** drive id → truth geometry (the OSRM route the generator drove along) */
val DRIVE_TRUTH =
    linkedMapOf(
        "commute-1-am" to "commute-am-hannam", "commute-1-pm" to "commute-pm-hannam",
        "commute-2-am" to "commute-am-hannam", "commute-2-pm" to "commute-pm-hannam",
        "commute-3-am" to "commute-am-seongsu", "commute-3-pm" to "commute-pm-seongsu",
        "commute-4-am" to "commute-am-hannam", "commute-4-pm" to "commute-pm-hannam",
        "commute-5-am" to "commute-am-hannam", "commute-5-pm" to "commute-pm-hannam",
        "weekend-gapyeong" to "weekend-gapyeong", "weekend-gapyeong-back" to "weekend-gapyeong-back",
        "trip-gangneung" to "trip-gangneung", "trip-coast" to "trip-coast",
    )

val ALL_DRIVES: List<String> = DRIVE_TRUTH.keys.toList()

class Drive(val id: String, val startedAt: Double, val points: List<RawPoint>) {
    val truth: Polyline by lazy { truthOf(id) }
}

private fun gz(path: String): String {
    val stream = Drive::class.java.getResourceAsStream(path) ?: error("missing resource $path")
    return GZIPInputStream(stream).bufferedReader().use { it.readText() }
}

private val driveCache = HashMap<String, Drive>()

fun loadDrive(id: String): Drive =
    driveCache.getOrPut(id) {
        var startedAt: Double? = null
        val points = ArrayList<RawPoint>()
        for (line in gz("/drives/$id.jsonl.gz").lineSequence()) {
            if (line.isBlank()) continue
            val o: JsonObject = Json.parseToJsonElement(line).jsonObject
            fun num(key: String) = o[key]!!.jsonPrimitive.double
            when (o["k"]!!.jsonPrimitive.content) {
                "gear" -> if (o["g"]!!.jsonPrimitive.content == "D" && startedAt == null) startedAt = num("t")
                "loc" -> points.add(RawPoint(lat = num("lat"), lng = num("lng"), t = num("t"), acc = num("acc"), speed = num("spd"), course = num("brg")))
            }
        }
        Drive(id, startedAt ?: points.first().t, points)
    }

private val truthCache = HashMap<String, Polyline>()

fun truthOf(id: String): Polyline = truthNamed(DRIVE_TRUTH[id]!!)

fun truthNamed(name: String): Polyline =
    truthCache.getOrPut(name) {
        val coords = Json.parseToJsonElement(gz("/drives/truth/$name.json.gz")).jsonObject["coords"]!!.jsonArray
        Polyline(coords.map { val c = it.jsonArray; GeoPoint(c[1].jsonPrimitive.double, c[0].jsonPrimitive.double) })
    }

/** One drive through the pipeline into the net. */
fun runDrive(graph: ExploreGraph, drive: Drive, config: ExplorePipelineConfig = CarProfile.pipeline, onSample: ((OnFootSample) -> Unit)? = null): ExploreWalkSummary =
    computeExploreWalk(graph, drive.startedAt, drive.points, config = config, onSample = onSample)

/** Polylines with a coarse grid for "distance to the nearest segment" queries (local equirectangular at the query point). */
class Polyline(points: List<GeoPoint> = emptyList()) {
    private val segs = ArrayList<DoubleArray>() // aLat, aLng, bLat, bLng
    private val grid = HashMap<Long, MutableList<Int>>()
    val pieces = ArrayList<List<GeoPoint>>()

    init {
        if (points.size >= 2) add(points)
    }

    val lengthM: Double get() = segs.sumOf { exploreDistanceM(it[0], it[1], it[2], it[3]) }

    fun add(points: List<GeoPoint>) {
        pieces.add(points)
        for (i in 1 until points.size) addSegment(points[i - 1], points[i])
    }

    fun addSegment(a: GeoPoint, b: GeoPoint) {
        val index = segs.size
        segs.add(doubleArrayOf(a.lat, a.lng, b.lat, b.lng))
        val r0 = cell(min(a.lat, b.lat)) - 1
        val r1 = cell(max(a.lat, b.lat)) + 1
        val c0 = cell(min(a.lng, b.lng)) - 1
        val c1 = cell(max(a.lng, b.lng)) + 1
        for (r in r0..r1) for (c in c0..c1) grid.getOrPut(key(r, c)) { ArrayList() }.add(index)
    }

    /** distance (m) to the nearest segment, +∞ beyond ~CELL (≥ 100 m) */
    fun distanceM(lat: Double, lng: Double): Double {
        val kx = EXPLORE_METERS_PER_DEGREE * StrictMath.cos(lat * EXPLORE_RAD)
        var best = Double.POSITIVE_INFINITY
        val ids = grid[key(cell(lat), cell(lng))] ?: return best
        for (i in ids) {
            val s = segs[i]
            val ax = (s[1] - lng) * kx
            val ay = (s[0] - lat) * EXPLORE_METERS_PER_DEGREE
            val vx = (s[3] - lng) * kx - ax
            val vy = (s[2] - lat) * EXPLORE_METERS_PER_DEGREE - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 > 0) max(0.0, min(1.0, -(ax * vx + ay * vy) / len2)) else 0.0
            val fx = ax + vx * t
            val fy = ay + vy * t
            best = min(best, sqrt(fx * fx + fy * fy))
        }
        return best
    }

    /** points every stepM along each piece */
    fun resample(stepM: Double): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        for (piece in pieces) {
            var carry = 0.0
            out.add(piece[0])
            for (i in 1 until piece.size) {
                val a = piece[i - 1]
                val b = piece[i]
                val len = exploreDistanceM(a, b)
                var at = stepM - carry
                while (at <= len) {
                    val f = at / len
                    out.add(GeoPoint(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f))
                    at += stepM
                }
                carry = len - (at - stepM)
            }
        }
        return out
    }

    private fun cell(deg: Double): Long = floor(deg / CELL_DEG).toLong()

    private fun key(r: Long, c: Long): Long = r * 100_000_000L + c

    private companion object {
        /** ~110 m north-south, ~88 m east-west in Seoul — segments are registered with one cell of margin */
        const val CELL_DEG = 0.001
    }
}

/** length (m) of `route` lying farther than thresholdM from `known` (sampled every 5 m) */
fun lengthAwayM(route: Polyline, known: Polyline, thresholdM: Double): Double {
    val step = 5.0
    return route.resample(step).count { known.distanceM(it.lat, it.lng) > thresholdM } * step
}

/** length (m) of a route counting stretches that come back within thresholdM of an earlier part (≥ 300 m before) once */
fun uniqueRoadM(route: Polyline, thresholdM: Double = 30.0): Double {
    val step = 5.0
    val samples = route.resample(step)
    val earlier = Polyline()
    val lag = (300 / step).toInt()
    var unique = 0.0
    for (i in samples.indices) {
        if (i > lag) earlier.addSegment(samples[i - lag - 1], samples[i - lag])
        if (earlier.distanceM(samples[i].lat, samples[i].lng) > thresholdM) unique += step
    }
    return unique
}

/** the net as a polyline (each edge one piece) */
fun graphPolyline(graph: ExploreGraph): Polyline {
    val line = Polyline()
    for (e in graph.edges()) line.add(listOf(GeoPoint(e.aLat, e.aLng), GeoPoint(e.bLat, e.bLng)))
    return line
}

/** net length (m) per thickness level 1..5 over the given edges */
fun levelLengths(graph: ExploreGraph, edges: Iterable<com.odomap.core.explore.EdgePair>): DoubleArray {
    val out = DoubleArray(6)
    for ((a, b) in edges) {
        val e = graph.edge(a, b) ?: continue
        out[exploreLineLevel(e.visits)] += exploreDistanceM(e.aLat, e.aLng, e.bLat, e.bLng)
    }
    return out
}

/**
 * Synthetic drive in local metres ([com.odomap.core.plane]): 1 Hz at constant speed with Doppler speed/course and the generator's GPS noise
 * (gen_routes.py: AR(1) k = 0.85 per second, stationary sigma noiseM per axis).
 */
fun carDrive(via: com.odomap.core.Via, speed: Double = 15.0, t0: Double = com.odomap.core.T0, noiseM: Double = 4.5, seed: Int = 1, acc: Double = 5.0): List<RawPoint> {
    val random = java.util.Random(seed.toLong())
    val k = 0.85
    var nx = random.nextGaussian() * noiseM
    var ny = random.nextGaussian() * noiseM
    val out = ArrayList<RawPoint>()
    var t = t0
    fun push(x: Double, y: Double, course: Double) {
        nx = k * nx + sqrt(1 - k * k) * random.nextGaussian() * noiseM
        ny = k * ny + sqrt(1 - k * k) * random.nextGaussian() * noiseM
        val p = com.odomap.core.plane.toLatLng(x + nx, y + ny)
        out.add(RawPoint(lat = p.lat, lng = p.lng, t = t, acc = acc, speed = speed, course = course))
        t += 1000
    }
    var carry = 0.0
    for (i in 1 until via.size) {
        val (x0, y0) = via[i - 1]
        val (x1, y1) = via[i]
        val length = kotlin.math.hypot(x1 - x0, y1 - y0)
        val course = (Math.toDegrees(kotlin.math.atan2(x1 - x0, y1 - y0)) + 360) % 360
        var d = carry
        if (i == 1) push(x0, y0, course)
        while (d + speed <= length + 1e-9) {
            d += speed
            push(x0 + (x1 - x0) * d / length, y0 + (y1 - y0) * d / length, course)
        }
        carry = d - length
    }
    return out
}
