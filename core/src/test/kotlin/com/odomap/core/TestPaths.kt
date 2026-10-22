package com.odomap.core

import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExplorePoint
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.jsHypot
import com.odomap.core.geo.localProjection
import com.odomap.core.run.RawPoint
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

// Port of clientApp jest/testing/exploreTestPaths.ts (fake paths for the explore tests) — bit-identical to the TS (checked against the golden
// fixture inputs in GoldenParityTest), plus the graph-only walk() helper of exploreGraph.test.ts.

val ORIGIN = GeoPoint(37.5283, 126.9326)
val plane = localProjection(ORIGIN)

/** KST 2026-10-07 07:00 (Date.UTC(2026, 9, 6, 22, 0)) */
val T0: Double = Instant.parse("2026-10-06T22:00:00Z").toEpochMilli().toDouble()
const val DAY_MS = 86400e3

/** mulberry32 — same bits as the TS rng (Math.imul / >>> 0 on Int) */
fun rng(seed: Int): () -> Double {
    var a = seed
    return {
        a += 0x6d2b79f5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}

private fun gaussian(random: () -> Double): Double {
    var u = 0.0
    while (u == 0.0) u = random()
    return sqrt(-2 * StrictMath.log(u)) * StrictMath.cos(2 * Math.PI * random())
}

typealias Via = List<Pair<Double, Double>>

fun via(vararg xy: Pair<Number, Number>): Via = xy.map { it.first.toDouble() to it.second.toDouble() }

/** points every second along the polyline at constant speed, with Doppler speed/course (pathPoints) */
fun pathPoints(via: Via, speed: Double, t0: Double = T0, acc: Double = 5.0, noiseM: Double = 0.0, seed: Int = 1): List<RawPoint> {
    val random = rng(seed)
    val rho = 0.95
    val step = noiseM * sqrt(1 - rho * rho)
    var nx = if (noiseM > 0) gaussian(random) * noiseM else 0.0
    var ny = if (noiseM > 0) gaussian(random) * noiseM else 0.0
    val out = ArrayList<RawPoint>()
    var t = t0
    fun push(x: Double, y: Double, course: Double) {
        if (noiseM > 0) {
            nx = rho * nx + gaussian(random) * step
            ny = rho * ny + gaussian(random) * step
        }
        val p = plane.toLatLng(x + nx, y + ny)
        out.add(RawPoint(lat = p.lat, lng = p.lng, t = t, acc = acc, alt = null, speed = speed, course = course))
        t += 1000
    }
    var carry = 0.0
    for (i in 1 until via.size) {
        val (x0, y0) = via[i - 1]
        val (x1, y1) = via[i]
        val length = jsHypot(x1 - x0, y1 - y0)
        val course = ((StrictMath.atan2(x1 - x0, y1 - y0) * 180) / Math.PI + 360) % 360
        var d = carry
        if (i == 1) push(x0, y0, course)
        while (d + speed <= length + 1e-9) {
            d += speed
            push(x0 + ((x1 - x0) * d) / length, y0 + ((y1 - y0) * d) / length, course)
        }
        carry = d - length
    }
    return out
}

/** re-time points to start at t0 */
fun shiftTo(points: List<RawPoint>, t0: Double): List<RawPoint> {
    if (points.isEmpty()) return points
    val d = t0 - points[0].t
    return points.map { it.copy(t = it.t + d) }
}

/** total edge length (m) — two lines on one street double it */
fun graphLengthM(graph: ExploreGraph): Double {
    var sum = 0.0
    for (edge in graph.edges()) sum += exploreDistanceM(edge.aLat, edge.aLng, edge.bLat, edge.bLng)
    return sum
}

/** edge pairs that share no node but run parallel within 8 m, 2 m or more aside (a street left as two lines) */
fun parallelDuplicates(graph: ExploreGraph): Int {
    class E(val a: Int, val b: Int, val mx: Double, val my: Double, val ux: Double, val uy: Double)
    val list = graph.edges().map { edge ->
        val a = plane.toXY(edge.aLat, edge.aLng)
        val b = plane.toXY(edge.bLat, edge.bLng)
        val len = jsHypot(b.x - a.x, b.y - a.y).let { if (it == 0.0) 1.0 else it }
        E(edge.a, edge.b, (a.x + b.x) / 2, (a.y + b.y) / 2, (b.x - a.x) / len, (b.y - a.y) / len)
    }
    var count = 0
    for (i in list.indices) {
        for (j in i + 1 until list.size) {
            val p = list[i]
            val q = list[j]
            if (p.a == q.a || p.a == q.b || p.b == q.a || p.b == q.b) continue
            if (jsHypot(p.mx - q.mx, p.my - q.my) > 8) continue
            val aside = abs((q.mx - p.mx) * p.uy - (q.my - p.my) * p.ux)
            if (aside >= 2 && abs(p.ux * q.ux + p.uy * q.uy) > 0.9) count += 1
        }
    }
    return count
}

/** exploreGraph.test walk(): ExplorePoints straight into the net (1 s apart) */
fun graphWalk(
    via: Via,
    speed: Double = 1.4,
    t0: Double = T0,
    onFoot: Boolean = true,
    weak: Boolean = false,
    acc: Double? = null,
    paced: Boolean = false,
): List<ExplorePoint> {
    val out = ArrayList<ExplorePoint>()
    var t = t0
    var carry = 0.0
    for (i in 1 until via.size) {
        val (x0, y0) = via[i - 1]
        val (x1, y1) = via[i]
        val length = jsHypot(x1 - x0, y1 - y0)
        var d = carry
        if (i == 1) {
            val p = plane.toLatLng(x0, y0)
            out.add(ExplorePoint(t, p.lat, p.lng, onFoot, weak, false, acc, paced))
            t += 1000
        }
        while (d + speed <= length + 1e-9) {
            d += speed
            val p = plane.toLatLng(x0 + ((x1 - x0) * d) / length, y0 + ((y1 - y0) * d) / length)
            out.add(ExplorePoint(t, p.lat, p.lng, onFoot, weak, false, acc, paced))
            t += 1000
        }
        carry = d - length
    }
    return out
}

/** connected components of edges */
fun componentsOf(edges: List<Pair<Int, Int>>): Int {
    val parent = HashMap<Int, Int>()
    fun find(id: Int): Int {
        var root = id
        while (parent.containsKey(root) && parent[root] != root) root = parent[root]!!
        return root
    }
    for ((a, b) in edges) {
        if (!parent.containsKey(a)) parent[a] = a
        if (!parent.containsKey(b)) parent[b] = b
        parent[find(a)] = find(b)
    }
    return parent.keys.map { find(it) }.toSet().size
}
