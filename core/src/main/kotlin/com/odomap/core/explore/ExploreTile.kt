package com.odomap.core.explore

import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.LatLng
import com.odomap.core.geo.jsHypot
import com.odomap.core.geo.jsRound
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Port of clientApp src/services/run/explore/exploreTile.ts. Pure computation.
// The net is split into web-mercator z14 tiles (~2 km in Seoul) for storage / transfer / drawing. A node lives in the tile of its position, an edge
// in the tile of its midpoint (edges carry both end positions so one tile can be drawn alone). Lat/lng are stored as 1e-6-degree integers.
// Drawing lines (exploreLines) group same-thickness edges between junctions; smooth = [1,2,1]/4 twice (drawing only — the stored net never changes).

const val EXPLORE_TILE_Z = 14

/** overview zoom the server groups tiles at (z11 = 8 × 8 z14 tiles) */
const val EXPLORE_OVERVIEW_Z = 11

/** tiles within this many metres of a new walk are loaded */
const val EXPLORE_TILE_MARGIN_M = 120.0

/** smoothing passes for drawn lines */
const val EXPLORE_LINE_SMOOTH_PASSES = 2

private const val MAX_MERCATOR_LAT = 85.0511287798

data class ExploreTileXY(val x: Int, val y: Int) {
    /** "x/y" (exploreTileKey) */
    val key: String get() = "$x/$y"
}

/** tile node row [id, lat e6, lng e6, visits, day] */
data class TileNode(val id: Int, val latE6: Long, val lngE6: Long, val visits: Int, val day: Int)

/** tile edge row [a, b, visits, day, a lat e6, a lng e6, b lat e6, b lng e6] */
data class TileEdge(val a: Int, val b: Int, val visits: Int, val day: Int, val aLatE6: Long, val aLngE6: Long, val bLatE6: Long, val bLngE6: Long)

/** one tile's content (storage and transfer shape) */
data class ExploreTileData(val n: MutableList<TileNode> = ArrayList(), val e: MutableList<TileEdge> = ArrayList())

/** a drawn line: edges of one thickness level; coords lat/lng; nodes/stops when asked (node ids along the line and their length share 0..1) */
data class ExploreLine(val level: Int, val coords: List<GeoPoint>, val nodes: List<Int>? = null, val stops: List<Double>? = null)

/** a move along a line: line index (uncounted step → -1 - index), start share, end share (end < start = backwards). null in a list = lift the brush */
data class ExploreLineMove(val line: Int, val from: Double, var to: Double)

data class TileBounds(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double)

private fun pow2(z: Int): Double = StrictMath.pow(2.0, z.toDouble())

fun exploreTileOf(lat: Double, lng: Double, z: Int = EXPLORE_TILE_Z): ExploreTileXY {
    val n = pow2(z)
    val clampedLat = max(-MAX_MERCATOR_LAT, min(MAX_MERCATOR_LAT, lat))
    val latRad = clampedLat * EXPLORE_RAD
    val x = floor(((lng + 180) / 360) * n)
    val y = floor(((1 - StrictMath.log(StrictMath.tan(latRad) + 1 / StrictMath.cos(latRad)) / Math.PI) / 2) * n)
    return ExploreTileXY(max(0.0, min(n - 1, x)).toInt(), max(0.0, min(n - 1, y)).toInt())
}

/** lat/lng bounds of a tile */
fun exploreTileBounds(tile: ExploreTileXY, z: Int = EXPLORE_TILE_Z): TileBounds {
    val n = pow2(z)
    fun lngOf(x: Int): Double = (x / n) * 360 - 180
    fun latOf(y: Int): Double = StrictMath.atan(StrictMath.sinh(Math.PI * (1 - (2 * y) / n))) / EXPLORE_RAD
    return TileBounds(minLat = latOf(tile.y + 1), minLng = lngOf(tile.x), maxLat = latOf(tile.y), maxLng = lngOf(tile.x + 1))
}

fun exploreTileKey(tile: ExploreTileXY): String = tile.key

/** tiles covering a lat/lng box (zoom z) */
fun exploreTilesInBounds(bounds: TileBounds, z: Int = EXPLORE_TILE_Z): List<ExploreTileXY> {
    val a = exploreTileOf(bounds.maxLat, bounds.minLng, z)
    val b = exploreTileOf(bounds.minLat, bounds.maxLng, z)
    val out = ArrayList<ExploreTileXY>()
    for (x in a.x..b.x) for (y in a.y..b.y) out.add(ExploreTileXY(x, y))
    return out
}

/** z14 tiles covering marginM around the points (unique, sorted by x then y) */
fun exploreTilesAround(points: Iterable<LatLng>, marginM: Double = EXPLORE_TILE_MARGIN_M): List<ExploreTileXY> {
    val seen = LinkedHashMap<String, ExploreTileXY>()
    for (point in points) {
        if (!point.lat.isFinite() || !point.lng.isFinite()) continue
        val dLat = marginM / EXPLORE_METERS_PER_DEGREE
        val dLng = marginM / max(1.0, EXPLORE_METERS_PER_DEGREE * StrictMath.cos(point.lat * EXPLORE_RAD))
        for (tile in exploreTilesInBounds(TileBounds(minLat = point.lat - dLat, minLng = point.lng - dLng, maxLat = point.lat + dLat, maxLng = point.lng + dLng))) {
            val key = tile.key
            if (!seen.containsKey(key)) seen[key] = tile
        }
    }
    return seen.values.sortedWith(compareBy<ExploreTileXY> { it.x }.thenBy { it.y })
}

private fun e6(value: Double): Long = jsRound(value * 1e6).toLong()

/** Loads a tile's content into the net. */
fun loadExploreTile(graph: ExploreGraph, data: ExploreTileData) {
    for (n in data.n) graph.loadNode(ExploreNode(n.id, n.latE6 / 1e6, n.lngE6 / 1e6, n.visits, n.day))
    for (e in data.e) graph.loadEdge(ExploreEdge(e.a, e.b, e.visits, e.day, e.aLatE6 / 1e6, e.aLngE6 / 1e6, e.bLatE6 / 1e6, e.bLngE6 / 1e6))
}

/** Splits the net into tiles (key → content, in first-seen order). With only, just those tiles. Rows sorted by id. */
fun exploreTilesOfGraph(graph: ExploreGraph, only: Set<String>? = null): LinkedHashMap<String, ExploreTileData> {
    val out = LinkedHashMap<String, ExploreTileData>()
    fun slot(key: String): ExploreTileData = out.getOrPut(key) { ExploreTileData() }
    for (node in graph.nodes()) {
        val key = exploreTileOf(node.lat, node.lng).key
        if (only != null && !only.contains(key)) continue
        slot(key).n.add(TileNode(node.id, e6(node.lat), e6(node.lng), node.visits, node.day))
    }
    for (edge in graph.edges()) {
        val key = exploreTileOf((edge.aLat + edge.bLat) / 2, (edge.aLng + edge.bLng) / 2).key
        if (only != null && !only.contains(key)) continue
        slot(key).e.add(TileEdge(edge.a, edge.b, edge.visits, edge.day, e6(edge.aLat), e6(edge.aLng), e6(edge.bLat), e6(edge.bLng)))
    }
    for (data in out.values) {
        data.n.sortWith(compareBy { it.id })
        data.e.sortWith(compareBy<TileEdge> { it.a }.thenBy { it.b })
    }
    return out
}

/** visits → thickness level 1..5 */
fun exploreLineLevel(visits: Int): Int =
    when {
        visits >= 9 -> 5
        visits >= 5 -> 4
        visits >= 3 -> 3
        visits >= 2 -> 2
        else -> 1
    }

/** [1,2,1]/4 smoothing of inner vertices, `passes` times; ends unchanged; inner vertices rounded to 1e-6 degrees. */
fun smoothExploreCoords(coords: List<GeoPoint>, passes: Int = EXPLORE_LINE_SMOOTH_PASSES): List<GeoPoint> {
    val n = coords.size
    if (n <= 2 || passes <= 0) return coords.map { GeoPoint(it.lat, it.lng) }
    var lat = DoubleArray(n) { coords[it].lat }
    var lng = DoubleArray(n) { coords[it].lng }
    repeat(passes) {
        val nextLat = lat.copyOf()
        val nextLng = lng.copyOf()
        for (i in 1 until n - 1) {
            nextLat[i] = (lat[i - 1] + 2 * lat[i] + lat[i + 1]) / 4
            nextLng[i] = (lng[i - 1] + 2 * lng[i] + lng[i + 1]) / 4
        }
        lat = nextLat
        lng = nextLng
    }
    fun round6(value: Double) = jsRound(value * 1e6) / 1e6
    return List(n) { i -> if (i == 0 || i == n - 1) GeoPoint(lat[i], lng[i]) else GeoPoint(round6(lat[i]), round6(lng[i])) }
}

private fun simplifyCoords(coords: List<GeoPoint>, toleranceM: Double): List<GeoPoint> {
    if (!(toleranceM > 0) || coords.size <= 2) return coords
    val keep = BooleanArray(coords.size)
    keep[0] = true
    keep[coords.size - 1] = true
    val stack = ArrayDeque<IntArray>()
    stack.addLast(intArrayOf(0, coords.size - 1))
    while (stack.isNotEmpty()) {
        val (from, to) = stack.removeLast()
        val a = coords[from]
        val b = coords[to]
        val kx = EXPLORE_METERS_PER_DEGREE * StrictMath.cos(((a.lat + b.lat) / 2) * EXPLORE_RAD)
        val bx = (b.lng - a.lng) * kx
        val by = (b.lat - a.lat) * EXPLORE_METERS_PER_DEGREE
        val len2 = bx * bx + by * by
        var worst = -1
        var worstD = toleranceM
        for (i in from + 1 until to) {
            val px = (coords[i].lng - a.lng) * kx
            val py = (coords[i].lat - a.lat) * EXPLORE_METERS_PER_DEGREE
            val d: Double
            if (len2 == 0.0) {
                d = sqrt(px * px + py * py)
            } else {
                val f = max(0.0, min(1.0, (px * bx + py * by) / len2))
                d = jsHypot(px - f * bx, py - f * by)
            }
            if (d > worstD) {
                worst = i
                worstD = d
            }
        }
        if (worst >= 0) {
            keep[worst] = true
            stack.addLast(intArrayOf(from, worst))
            stack.addLast(intArrayOf(worst, to))
        }
    }
    return coords.filterIndexed { i, _ -> keep[i] }
}

/** length share of each vertex from the line start (0..1, 4 decimals) */
private fun lineStops(coords: List<GeoPoint>): List<Double> {
    val cum = ArrayList<Double>()
    cum.add(0.0)
    for (i in 1 until coords.size) cum.add(cum[i - 1] + exploreDistanceM(coords[i - 1], coords[i]))
    val total = cum[cum.size - 1]
    return cum.mapIndexed { i, value -> if (i == cum.size - 1 && total > 0) 1.0 else if (total > 0) jsRound((value / total) * 1e4) / 1e4 else 0.0 }
}

data class ExploreLinesOptions(val simplifyM: Double = 0.0, val minLengthM: Double = 0.0, val smooth: Boolean = false, val nodes: Boolean = false)

/**
 * Groups edges into drawable lines: same thickness level, junction (node with ≠ 2 neighbours) to junction. smooth → smoothExploreCoords,
 * simplifyM > 0 → Douglas–Peucker, lines shorter than minLengthM dropped, nodes → node ids and stops per line.
 */
fun exploreLines(edges: Iterable<ExploreEdge>, options: ExploreLinesOptions = ExploreLinesOptions()): List<ExploreLine> {
    class Half(val to: Int, val key: Int)
    class Group(val adjacency: LinkedHashMap<Int, MutableList<Half>> = LinkedHashMap(), val coord: HashMap<Int, GeoPoint> = HashMap(), var edges: Int = 0)
    val byLevel = LinkedHashMap<Int, Group>()
    var key = 0
    for (edge in edges) {
        if (edge.a == edge.b) continue
        val level = exploreLineLevel(edge.visits)
        val group = byLevel.getOrPut(level) { Group() }
        group.coord[edge.a] = GeoPoint(edge.aLat, edge.aLng)
        group.coord[edge.b] = GeoPoint(edge.bLat, edge.bLng)
        val k = key
        key += 1
        group.edges += 1
        group.adjacency.getOrPut(edge.a) { ArrayList() }.add(Half(edge.b, k))
        group.adjacency.getOrPut(edge.b) { ArrayList() }.add(Half(edge.a, k))
    }
    val out = ArrayList<ExploreLine>()
    for (level in byLevel.keys.sorted()) {
        val group = byLevel[level]!!
        val used = HashSet<Int>()
        fun walkFrom(start: Int, first: Half): Pair<List<GeoPoint>, List<Int>> {
            val coords = arrayListOf(group.coord[start]!!)
            val ids = arrayListOf(start)
            var half: Half? = first
            while (half != null && !used.contains(half.key)) {
                used.add(half.key)
                val at = half.to
                coords.add(group.coord[at]!!)
                ids.add(at)
                val next = group.adjacency[at] ?: emptyList()
                if (next.size != 2) break
                half = next.firstOrNull { !used.contains(it.key) }
            }
            return coords to ids
        }
        val ids = group.adjacency.keys.sorted()
        // lines from junctions/ends first, loops last
        for (pass in 0..1) {
            for (id in ids) {
                val halves = group.adjacency[id]!!
                if (pass == 0 && halves.size == 2) continue
                for (half in halves) {
                    if (used.contains(half.key)) continue
                    val (coords, nodeIds) = walkFrom(id, half)
                    if (coords.size < 2) continue
                    var length = 0.0
                    for (i in 1 until coords.size) length += exploreDistanceM(coords[i - 1], coords[i])
                    if (length < options.minLengthM) continue
                    val drawn = if (options.smooth) smoothExploreCoords(coords) else coords
                    out.add(
                        ExploreLine(
                            level,
                            simplifyCoords(drawn, options.simplifyM),
                            if (options.nodes) nodeIds else null,
                            if (options.nodes) lineStops(drawn) else null,
                        ),
                    )
                }
            }
        }
    }
    return out
}

/**
 * walkPath (node order walked) → moves along lines that have nodes/stops. Consecutive steps along one line in one direction merge (counted and
 * uncounted separately). An edge not in the lines lifts the brush; a new stroke (0) lifts it unless it restarts at the same node.
 */
fun exploreLineMoves(lines: List<ExploreLine>, path: List<Int>): List<ExploreLineMove?> {
    val where = HashMap<Long, Pair<Int, Int>>()
    lines.forEachIndexed { index, line ->
        val nodes = line.nodes
        val stops = line.stops
        if (nodes == null || stops == null || nodes.size != stops.size) return@forEachIndexed
        for (i in 1 until nodes.size) {
            val key = exploreEdgeKey(nodes[i - 1], nodes[i])
            if (!where.containsKey(key)) where[key] = index to (i - 1)
        }
    }
    val out = ArrayList<ExploreLineMove?>()
    var at: Int? = null
    var lifted = false
    var last: ExploreLineMove? = null
    var k = 0
    while (k < path.size) {
        val token = path[k]
        if (token == 0) {
            val start = if (k + 1 < path.size) path[k + 1] else null
            k += 1
            if (start == null || start != at) lifted = true
            at = start
            k += 1
            continue
        }
        val to = kotlin.math.abs(token)
        val from0 = at
        val found = if (from0 == null) null else where[exploreEdgeKey(from0, to)]
        at = to
        if (found == null) {
            lifted = true
            k += 1
            continue
        }
        val (index, i) = found
        val nodes = lines[index].nodes!!
        val stops = lines[index].stops!!
        val forward = nodes[i + 1] == to
        val from = if (forward) stops[i] else stops[i + 1]
        val end = if (forward) stops[i + 1] else stops[i]
        val id = if (token > 0) index else -1 - index
        if (lifted) {
            if (out.isNotEmpty()) out.add(null)
            last = null
            lifted = false
        }
        val prev = last
        if (prev != null && prev.line == id && prev.to == from && (prev.to > prev.from) == (end > from)) {
            prev.to = end
        } else {
            val move = ExploreLineMove(id, from, end)
            last = move
            out.add(move)
        }
        k += 1
    }
    return out
}
