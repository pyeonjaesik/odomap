package com.odomap.core.explore

import com.odomap.core.geo.LatLng
import com.odomap.core.geo.jsRound
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Port of clientApp src/services/run/explore/exploreGraph.ts (rules v5). Pure computation.
//
// The walked-road net: nodes (points) joined by edges (straight lines ≤ maxEdgeM). A new walk (on-foot parts only) is resampled every stepM
// metres and each point either snaps onto a known edge (perpendicular distance, snap radius from the position error), is held while it drifts
// in a band beside a known line, rides back on a line this walk just made (out-and-back), or creates a new node joined to the previous one
// (a first-time road — its walked distance is "pioneered"). Edges count passes per walk up to passCap (line thickness).
// See the long header comment in the TS original for the reasoning behind every rule.
//
// Parity notes: iteration order follows JS Map/Set insertion order (LinkedHashMap/LinkedHashSet where order is observable), every tie is broken
// by node number exactly like the TS, all arithmetic is Double in the same operation order, Math.round is jsRound, cos is StrictMath.cos.

/**
 * Net rules (exploreGraph.ts EXPLORE_RULES — values unchanged). Injectable through [ExploreGraph]; the car rule set is
 * [com.odomap.core.car.CarProfile.graphRules]. The car-only fields (bridge*, weakSkipMaxM) default to off. Bump [version] when changing values.
 */
data class ExploreRules(
    val version: Int = 5,
    /** rule-set family: "g" walking (phone), "c" car — [label] = family + version is the stored-net rebuild key */
    val family: String = "g",
    /** resample the path every stepM metres */
    val stepM: Double = 8.0,
    /** snap distance to a known edge: position error × snapAccuracyFactor clamped to [snapMinM, snapMaxM] (unknown error → snapMinM) */
    val snapMinM: Double = 12.0,
    val snapMaxM: Double = 24.0,
    val snapAccuracyFactor: Double = 1.2,
    /** drift band beyond the snap distance while riding a known line */
    val driftBandM: Double = 10.0,
    /** band while riding a line this walk made (turned-back out-and-back) */
    val ownBandM: Double = 20.0,
    /** band when a free (new) walk comes in parallel to a known line */
    val freeBandM: Double = 4.0,
    /** following the band longer than this → it is the known line */
    val driftHoldM: Double = 64.0,
    /** leaving the band for this long in a row → a new road */
    val exitConfirmM: Double = 32.0,
    /** max angle (deg) between walking direction and a line to count as parallel */
    val parallelDeg: Double = 45.0,
    /** snap to a line made by this walk only if distance ≤ ownRatio × (walked since it was made) */
    val ownRatio: Double = 0.5,
    /** hold steps beside the tip of a just-made line up to this length */
    val ownHoldM: Double = 40.0,
    /** decide a U-turn by the median distance of this many returning steps */
    val uTurnPoints: Int = 4,
    /** new edges longer than this get intermediate nodes */
    val maxEdgeM: Double = 12.0,
    /** routing along the net to a snap target: max(straight × routeFactor, straight + routeSlackM), up to routeMaxM */
    val routeFactor: Double = 2.0,
    val routeSlackM: Double = 16.0,
    val routeMaxM: Double = 80.0,
    /** passes one walk may add to one edge */
    val passCap: Int = 3,
    /** a re-pass counts only after moving this far from where the retrace started */
    val repassM: Double = 16.0,
    /** … or this far when steps confirmed walking (paced) */
    val repassPacedM: Double = 4.0,
    /** do not join points farther apart than this (m) */
    val linkMaxM: Double = 60.0,
    /** … or longer apart than this (s) */
    val linkMaxS: Double = 90.0,
    /**
     * Car: a position gap (tunnel, dropped fixes, filter restart) that would break the line is joined with a straight line counted as driven
     * when at most bridgeMaxM long, bridgeMaxS apart and no faster than bridgeMaxSpeedMps on average (faster = teleport, not joined).
     * Never across a user pause. 0 = off (walking).
     */
    val bridgeMaxM: Double = 0.0,
    val bridgeMaxS: Double = 0.0,
    val bridgeMaxSpeedMps: Double = 0.0,
    /** car: a weak step that would make a node is skipped (the line goes on from the last node) up to this length in a row; 0 = it breaks the line */
    val weakSkipMaxM: Double = 0.0,
) {
    /** version string, e.g. "g5" / "c1" */
    val label: String get() = "$family$version"

    /** cos of [parallelDeg] (exploreGraph.ts PARALLEL_COS) */
    val parallelCos: Double = StrictMath.cos(parallelDeg * EXPLORE_RAD)

    companion object {
        /** The phone app's rule set (walking). */
        val DEFAULT = ExploreRules()
    }
}

/** exploreGraph.ts / onFoot.ts / exploreTile.ts: RAD = Math.PI / 180 */
const val EXPLORE_RAD = Math.PI / 180

/** exploreGraph.ts METERS_PER_DEGREE = (6371008.8 * Math.PI) / 180 — differs in the last bit from geo.ts's EARTH_RADIUS_M * RAD */
const val EXPLORE_METERS_PER_DEGREE = (6371008.8 * Math.PI) / 180

/** node-search grid cell size (m) */
private const val CELL_M = 25.0

/** edge key = smaller id × EDGE_KEY_BASE + larger id (ids < 2^26) */
const val EDGE_KEY_BASE = 67108864L

data class ExploreNode(
    val id: Int,
    override val lat: Double,
    override val lng: Double,
    /** walks that passed */
    var visits: Int,
    /** day created (Seoul calendar day number, 1970-01-01 = 0) */
    val day: Int,
) : LatLng

data class ExploreEdge(
    /** smaller node id */
    val a: Int,
    /** larger node id */
    val b: Int,
    /** passes (v4 — up to passCap per walk) */
    var visits: Int,
    val day: Int,
    val aLat: Double,
    val aLng: Double,
    val bLat: Double,
    val bLng: Double,
)

/** One point of a walk (a smoothed position). onFoot: walked/ran (decided by the on-foot classifier). */
data class ExplorePoint(
    /** ms epoch */
    val t: Double,
    override val lat: Double,
    override val lng: Double,
    val onFoot: Boolean,
    /** weak fix — never creates nodes */
    val weak: Boolean = false,
    /** do not join to the previous point (pause / filter restart) */
    val restart: Boolean = false,
    /** position error (m) of the raw fix, null when unknown */
    val acc: Double? = null,
    /** walking confirmed by the step counter */
    val paced: Boolean = false,
    /** first point after a user pause — a gap bridge ([ExploreRules.bridgeMaxM]) never joins across it */
    val paused: Boolean = false,
) : LatLng

/** [a, b] with a < b */
data class EdgePair(val a: Int, val b: Int)

/** [a, b, passes] */
data class EdgePass(val a: Int, val b: Int, val passes: Int)

data class ExploreWalkResult(
    /** first-time road walked by this walk (m) — never more than onFootM */
    val pioneeredM: Double,
    /** on-foot length (sum of resampled step lengths, m) */
    val onFootM: Double,
    /** nodes created by this walk */
    val newNodes: Int,
    /** edges created by this walk */
    val newEdges: List<EdgePair>,
    /** edges this walk passed (new ones included), in first-pass order */
    val walkEdges: List<EdgePair>,
    /** edges passed two or more times by this walk (walkEdges order) */
    val walkPasses: List<EdgePass>,
    /** order walked over the net: 0 then a node = new stroke; positive = counted step to that node; negative = uncounted step */
    val walkPath: List<Int>,
)

/** A snap candidate on the net (closest point of an edge). */
data class ExploreNear(
    /** edge ends (smaller, larger) — an isolated node has a == b */
    val a: Int,
    val b: Int,
    /** where along a → b (0..1) */
    val t: Double,
    /** distance from the point (m) */
    val d: Double,
    /** the point lies beyond a dead-end tip, straight ahead (within 45°) */
    val tip: Boolean,
    /** edge direction a → b (east/north unit vector), 0 for an isolated node */
    val ux: Double,
    val uy: Double,
)

/** Distance between two points (m, equirectangular). */
fun exploreDistanceM(a: LatLng, b: LatLng): Double = exploreDistanceM(a.lat, a.lng, b.lat, b.lng)

fun exploreDistanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val kx = EXPLORE_METERS_PER_DEGREE * StrictMath.cos(((aLat + bLat) / 2) * EXPLORE_RAD)
    val dx = (bLng - aLng) * kx
    val dy = (bLat - aLat) * EXPLORE_METERS_PER_DEGREE
    return sqrt(dx * dx + dy * dy)
}

/** Seoul calendar day number (1970-01-01 = 0); the day boundary is KST midnight. */
fun exploreDayOf(ms: Double): Int = floor((ms + 9 * 3600 * 1000) / 86400000).toInt()

/** position error (m) → snap distance to a known line (m) */
fun exploreSnapRadiusM(acc: Double?, rules: ExploreRules = ExploreRules.DEFAULT): Double {
    if (acc == null || !acc.isFinite() || acc <= 0) return rules.snapMinM
    return min(rules.snapMaxM, max(rules.snapMinM, acc * rules.snapAccuracyFactor))
}

private fun round6(value: Double): Double = jsRound(value * 1e6) / 1e6

fun exploreEdgeKey(a: Int, b: Int): Long = if (a < b) a * EDGE_KEY_BASE + b else b * EDGE_KEY_BASE + a

private fun cellRow(lat: Double): Long = floor((lat * EXPLORE_METERS_PER_DEGREE) / CELL_M).toLong()

/** longitude cell from the row's middle latitude — cells in one row are at least CELL_M wide east-west */
private fun cellCol(row: Long, lng: Double): Long {
    val rowLat = ((row + 0.5) * CELL_M) / EXPLORE_METERS_PER_DEGREE
    return floor((lng * EXPLORE_METERS_PER_DEGREE * StrictMath.cos(rowLat * EXPLORE_RAD)) / CELL_M).toLong()
}

private fun cellKey(row: Long, col: Long): Long = (row + 1048576) * 8388608 + (col + 4194304)

/** closer one wins; ties go to the smaller edge (same answer whatever order the net was loaded in) */
private fun closer(x: ExploreNear, best: ExploreNear?): Boolean {
    if (best == null || x.d < best.d) return true
    if (x.d > best.d) return false
    return x.a < best.a || (x.a == best.a && x.b < best.b)
}

/**
 * One person's walked-road net. The server fills it from map tiles, adds a new walk and writes back the changed nodes/edges; the app keeps a copy
 * of the tiles around the walk to estimate live.
 */
class ExploreGraph(nextId: Int = 1, val rules: ExploreRules = ExploreRules.DEFAULT) {
    private val nodeMap = LinkedHashMap<Int, ExploreNode>()
    private val edgeMap = LinkedHashMap<Long, ExploreEdge>()
    private val grid = HashMap<Long, MutableList<Int>>()
    private val neighbors = HashMap<Int, MutableList<Int>>()

    /** id of the next node to create */
    var nextId: Int = max(1, nextId)
        private set

    /** changed (new or visits increased) nodes / edge keys — to be saved */
    val dirtyNodes = LinkedHashSet<Int>()
    val dirtyEdges = LinkedHashSet<Long>()

    val nodeCount: Int get() = nodeMap.size
    val edgeCount: Int get() = edgeMap.size

    fun node(id: Int): ExploreNode? = nodeMap[id]

    fun edge(a: Int, b: Int): ExploreEdge? = edgeMap[exploreEdgeKey(a, b)]

    /** nodes in insertion order */
    fun nodes(): Collection<ExploreNode> = nodeMap.values

    /** edges in insertion order */
    fun edges(): Collection<ExploreEdge> = edgeMap.values

    /** edges attached to a node (loaded ones only) */
    fun degree(id: Int): Int = neighbors[id]?.size ?: 0

    /** Loads a saved node (same id → replaced) and moves nextId past it. */
    fun loadNode(node: ExploreNode) {
        val existing = nodeMap[node.id]
        if (existing != null) unindex(existing)
        val copy = ExploreNode(node.id, node.lat, node.lng, node.visits, node.day)
        nodeMap[copy.id] = copy
        index(copy)
        if (copy.id >= nextId) nextId = copy.id + 1
    }

    /** Loads a saved edge, even if its end nodes are not loaded (tile border). */
    fun loadEdge(edge: ExploreEdge) {
        if (edge.a == edge.b) return
        val swap = edge.a > edge.b
        val key = exploreEdgeKey(edge.a, edge.b)
        if (!edgeMap.containsKey(key)) link(edge.a, edge.b)
        edgeMap[key] =
            if (swap) {
                ExploreEdge(edge.b, edge.a, edge.visits, edge.day, edge.bLat, edge.bLng, edge.aLat, edge.aLng)
            } else {
                ExploreEdge(edge.a, edge.b, edge.visits, edge.day, edge.aLat, edge.aLng, edge.bLat, edge.bLng)
            }
    }

    /** Clears the dirty marks (after saving). */
    fun clearDirty() {
        dirtyNodes.clear()
        dirtyEdges.clear()
    }

    /** Nearest node within radius (ties → smaller id). Nodes for which skip returns true are skipped. */
    fun nearest(point: LatLng, radiusM: Double, skip: ((Int) -> Boolean)? = null): ExploreNode? {
        var best: ExploreNode? = null
        var bestD = Double.POSITIVE_INFINITY
        forNodesAround(point, radiusM) { id ->
            if (skip != null && skip(id)) return@forNodesAround
            val node = nodeMap[id] ?: return@forNodesAround
            val d = exploreDistanceM(point, node)
            if (d > radiusM) return@forNodesAround
            val current = best
            if (d < bestD || (d == bestD && current != null && id < current.id)) {
                best = node
                bestD = d
            }
        }
        return best
    }

    /**
     * Edges within the radius (perpendicular distance to the segment) and isolated nodes. Each edge once. Edges are ≤ maxEdgeM so one end lies within
     * radiusM + maxEdgeM/2 — that many cells are scanned.
     */
    fun near(point: LatLng, radiusM: Double): List<ExploreNear> {
        val out = ArrayList<ExploreNear>()
        val kx = EXPLORE_METERS_PER_DEGREE * StrictMath.cos(point.lat * EXPLORE_RAD)
        val seen = HashSet<Long>()
        forNodesAround(point, radiusM + rules.maxEdgeM / 2) { id ->
            val node = nodeMap[id] ?: return@forNodesAround
            val links = neighbors[id]
            if (links == null || links.isEmpty()) {
                val dx = (node.lng - point.lng) * kx
                val dy = (node.lat - point.lat) * EXPLORE_METERS_PER_DEGREE
                val d = sqrt(dx * dx + dy * dy)
                if (d <= radiusM) out.add(ExploreNear(id, id, 0.0, d, false, 0.0, 0.0))
                return@forNodesAround
            }
            for (other in links) {
                val key = exploreEdgeKey(id, other)
                if (seen.contains(key)) continue
                seen.add(key)
                val edge = edgeMap[key] ?: continue
                // east/north metres with the point at 0
                val ax = (edge.aLng - point.lng) * kx
                val ay = (edge.aLat - point.lat) * EXPLORE_METERS_PER_DEGREE
                val vx = (edge.bLng - point.lng) * kx - ax
                val vy = (edge.bLat - point.lat) * EXPLORE_METERS_PER_DEGREE - ay
                val len2 = vx * vx + vy * vy
                val raw = if (len2 > 0) -(ax * vx + ay * vy) / len2 else 0.0
                val t = if (raw < 0) 0.0 else if (raw > 1) 1.0 else raw
                val fx = ax + vx * t
                val fy = ay + vy * t
                val d = sqrt(fx * fx + fy * fy)
                if (d > radiusM) continue
                val len = sqrt(len2)
                // beyond the tip by more than it is off to the side (within 45° of the line) → a step straight on
                val beyond = if (raw < 0) -raw * len else if (raw > 1) (raw - 1) * len else 0.0
                val aside = if (len > 0) abs(ax * vy - ay * vx) / len else d
                val tip = beyond > 0 && beyond >= aside && degree(if (raw < 0) edge.a else edge.b) == 1
                out.add(ExploreNear(edge.a, edge.b, t, d, tip, if (len > 0) vx / len else 0.0, if (len > 0) vy / len else 0.0))
            }
        }
        return out
    }

    /**
     * Shortest way along the net from → to (node ids, both ends included), or null when longer than maxM. Ties go through the smaller node
     * (same answer whatever order the net was loaded in).
     */
    fun route(from: Int, to: Int, maxM: Double): List<Int>? {
        if (from == to) return listOf(from)
        val cost = HashMap<Int, Double>()
        cost[from] = 0.0
        val prev = HashMap<Int, Int>()
        val done = HashSet<Int>()
        val open = ArrayList<Int>()
        open.add(from)
        while (open.isNotEmpty()) {
            var pick = 0
            for (i in 1 until open.size) {
                val ci = cost[open[i]]!!
                val cp = cost[open[pick]]!!
                if (ci < cp || (ci == cp && open[i] < open[pick])) pick = i
            }
            val at = open[pick]
            open[pick] = open[open.size - 1]
            open.removeAt(open.size - 1)
            done.add(at)
            if (at == to) break
            val base = cost[at]!!
            for (next in neighbors[at] ?: emptyList<Int>()) {
                if (done.contains(next)) continue
                val edge = edgeMap[exploreEdgeKey(at, next)] ?: continue
                val c = base + exploreDistanceM(edge.aLat, edge.aLng, edge.bLat, edge.bLng)
                if (c > maxM) continue
                val old = cost[next]
                if (old == null) {
                    open.add(next)
                    cost[next] = c
                    prev[next] = at
                } else if (c < old || (c == old && at < prev[next]!!)) {
                    cost[next] = c
                    prev[next] = at
                }
            }
        }
        if (!done.contains(to)) return null
        val path = ArrayList<Int>()
        path.add(to)
        var at = to
        while (at != from) {
            at = prev[at]!!
            path.add(at)
        }
        path.reverse()
        return path
    }

    /** Starts a new walk: push points in time order, then end(). */
    fun beginWalk(day: Int): ExploreWalk = ExploreWalk(this, day)

    /** Adds one whole walk (beginWalk → push → end). */
    fun addWalk(points: Iterable<ExplorePoint>, day: Int): ExploreWalkResult {
        val walk = beginWalk(day)
        for (point in points) walk.push(point)
        return walk.end()
    }

    // ── used by ExploreWalk only

    internal fun createNode(lat: Double, lng: Double, day: Int): ExploreNode {
        val node = ExploreNode(nextId, round6(lat), round6(lng), 0, day)
        nextId += 1
        nodeMap[node.id] = node
        index(node)
        dirtyNodes.add(node.id)
        return node
    }

    internal fun bumpNode(node: ExploreNode) {
        node.visits += 1
        dirtyNodes.add(node.id)
    }

    /** Makes the edge if missing. */
    internal fun ensureEdge(a: Int, b: Int, day: Int): Pair<ExploreEdge, Boolean> {
        val key = exploreEdgeKey(a, b)
        val existing = edgeMap[key]
        if (existing != null) return existing to false
        val na = nodeMap[min(a, b)]
        val nb = nodeMap[max(a, b)]
        if (na == null || nb == null) throw IllegalStateException("explore: edge between unknown nodes")
        val edge = ExploreEdge(na.id, nb.id, 0, day, na.lat, na.lng, nb.lat, nb.lng)
        edgeMap[key] = edge
        link(edge.a, edge.b)
        dirtyEdges.add(key)
        return edge to true
    }

    internal fun bumpEdge(edge: ExploreEdge) {
        edge.visits += 1
        dirtyEdges.add(exploreEdgeKey(edge.a, edge.b))
    }

    /** node ids of the cells covering reachM around the point */
    private inline fun forNodesAround(point: LatLng, reachM: Double, visit: (Int) -> Unit) {
        val span = max(1.0, ceil(reachM / CELL_M)).toLong()
        val row = cellRow(point.lat)
        var r = row - span
        while (r <= row + span) {
            val col = cellCol(r, point.lng)
            var c = col - span
            while (c <= col + span) {
                val ids = grid[cellKey(r, c)]
                if (ids != null) for (id in ids) visit(id)
                c += 1
            }
            r += 1
        }
    }

    private fun link(a: Int, b: Int) {
        val na = neighbors[a]
        if (na != null) {
            if (!na.contains(b)) na.add(b)
        } else {
            neighbors[a] = mutableListOf(b)
        }
        val nb = neighbors[b]
        if (nb != null) {
            if (!nb.contains(a)) nb.add(a)
        } else {
            neighbors[b] = mutableListOf(a)
        }
    }

    private fun index(node: ExploreNode) {
        val row = cellRow(node.lat)
        val key = cellKey(row, cellCol(row, node.lng))
        val ids = grid[key]
        if (ids != null) ids.add(node.id) else grid[key] = mutableListOf(node.id)
    }

    private fun unindex(node: ExploreNode) {
        val row = cellRow(node.lat)
        val key = cellKey(row, cellCol(row, node.lng))
        val ids = grid[key] ?: return
        val at = ids.indexOf(node.id)
        if (at >= 0) ids.removeAt(at)
        if (ids.isEmpty()) grid.remove(key)
    }

    companion object {
        /** changed edge key → (smaller id, larger id) */
        fun edgeEnds(key: Long): EdgePair {
            val a = key / EDGE_KEY_BASE
            return EdgePair(a.toInt(), (key - a * EDGE_KEY_BASE).toInt())
        }
    }
}

/** a resampled point (every stepM along the path); t interpolated */
private class StepPoint(
    val t: Double,
    override val lat: Double,
    override val lng: Double,
    val weak: Boolean,
    val restart: Boolean,
    val acc: Double?,
    val paced: Boolean,
) : LatLng

private enum class HeldKind { DRIFT, OUT, OWN, BACK }

private enum class HeldGroup { DRIFT, OWN }

private enum class StepKind { SNAP, DRIFT, BACK, OWN, FREE }

private enum class Settled { NONE, MOVED, CHANGED, TAKEN }

private enum class Release { RIDE, NEW, SPLIT }

/** a held step: drift = in the band beside a known line, out = left the band (may still be noise), own = beside the tip of a just-made line, back = coming back parallel to it */
private class Held(
    val point: StepPoint,
    val walked: Double,
    val along: Double,
    var near: ExploreNear?,
    var kind: HeldKind,
    var bandM: Double,
)

/** retracing known edges: start node, edges not yet counted ([key, walkPath index]), counted edges, whether repassM was reached */
private class Retrace(val start: Int, var pending: MutableList<Pair<Long, Int>>, val counted: HashSet<Long>, var confirmed: Boolean)

private class Found(val valid: ExploreNear?, val own: ExploreNear?)

/**
 * One walk being added to the net. Push points in time order and end() (marks the last half step, settles held steps).
 * Live screens only push and read result() (missing the last half step and held steps — a prefix of the final computation).
 */
class ExploreWalk internal constructor(private val graph: ExploreGraph, private val day: Int) {
    private val rules = graph.rules

    /** nodes whose visits this walk raised */
    private val visitedNodes = HashSet<Int>()

    /** edges this walk passed → counted passes (≤ passCap) */
    private val passes = HashMap<Long, Int>()

    /** nodes this walk created → walked length at that time */
    private val createdAlong = HashMap<Int, Double>()
    private var outPioneeredM = 0.0
    private var outOnFootM = 0.0
    private var outNewNodes = 0
    private val outNewEdges = ArrayList<EdgePair>()
    private val outWalkEdges = ArrayList<EdgePair>()

    // resampling
    private var last: ExplorePoint? = null

    /** length left from the last resampled spot to the current point */
    private var carryM = 0.0

    /** previous resampled point */
    private var lastStep: StepPoint? = null

    /** walked length (resampled) */
    private var along = 0.0

    /** last resampled points of this run (walking direction — up to three) */
    private var trail = ArrayList<StepPoint>()

    // snapping
    private var prevNode: Int? = null

    /** the previous step created a node (a step into a known line is pioneered too) */
    private var lastFree = false

    /** length of weak steps skipped since the last node (car — [ExploreRules.weakSkipMaxM]) */
    private var skippedM = 0.0

    /** last node of the run before a break — resuming next to it joins there (v2) */
    private var resumeNode: Int? = null
    private var held = ArrayList<Held>()
    private var heldKind: HeldGroup? = null
    private var retrace: Retrace? = null

    /** the step being decided was confirmed by steps (repassPacedM) */
    private var paced = false
    private val path = ArrayList<Int>()
    private var pathAt: Int? = null
    private var settledT = Double.NEGATIVE_INFINITY
    private var revisionCount = 0
    private var ended = false

    /** One point. A point that is not on foot breaks the line there. */
    fun push(point: ExplorePoint) {
        if (ended) return
        if (!point.lat.isFinite() || !point.lng.isFinite() || !point.t.isFinite()) return
        val last = this.last
        if (last != null && point.t <= last.t) return
        this.last = point
        if (!point.onFoot) {
            finishRun(last)
            return
        }
        val linked =
            last != null &&
                last.onFoot &&
                !point.restart &&
                (point.t - last.t) / 1000 <= rules.linkMaxS &&
                exploreDistanceM(last, point) <= rules.linkMaxM
        val bridged = !linked && bridges(last, point)
        if (!linked && !bridged) {
            // end the previous run; the first point of a new run is used as is
            finishRun(last)
            step(StepPoint(point.t, point.lat, point.lng, point.weak, true, accOf(point), point.paced))
            carryM = 0.0
            return
        }
        last!!
        // resample the last → point segment every stepM (a bridged gap is taken as driven: its steps are not weak)
        val segment = exploreDistanceM(last, point)
        if (segment <= 0) return
        val acc = largerAcc(accOf(last), accOf(point))
        val weak = point.weak && !bridged
        var at = rules.stepM - carryM
        while (at <= segment + 1e-9) {
            val f = at / segment
            step(
                StepPoint(
                    last.t + (point.t - last.t) * f,
                    last.lat + (point.lat - last.lat) * f,
                    last.lng + (point.lng - last.lng) * f,
                    weak,
                    false,
                    acc,
                    point.paced,
                ),
            )
            at += rules.stepM
        }
        carryM = segment - (at - rules.stepM)
    }

    /** car: join last → point straight across a plausible position gap */
    private fun bridges(last: ExplorePoint?, point: ExplorePoint): Boolean {
        if (!(rules.bridgeMaxM > 0) || last == null || !last.onFoot || point.paused) return false
        val seconds = (point.t - last.t) / 1000
        val meters = exploreDistanceM(last, point)
        return seconds > 0 && seconds <= rules.bridgeMaxS && meters <= rules.bridgeMaxM && meters / seconds <= rules.bridgeMaxSpeedMps
    }

    /** So far (without the last half step and held steps). */
    fun result(): ExploreWalkResult {
        val walkPasses = ArrayList<EdgePass>()
        for ((a, b) in outWalkEdges) {
            val n = passes[a * EDGE_KEY_BASE + b] ?: 1
            if (n >= 2) walkPasses.add(EdgePass(a, b, n))
        }
        return ExploreWalkResult(
            outPioneeredM,
            outOnFootM,
            outNewNodes,
            ArrayList(outNewEdges),
            ArrayList(outWalkEdges),
            walkPasses,
            ArrayList(path),
        )
    }

    /** Ends the walk: marks the last point if it is over half a step past the last mark, settles held steps. */
    fun end(): ExploreWalkResult {
        if (!ended) {
            stepTail(last)
            releaseAtBreak()
            retrace = null
            ended = true
        }
        return result()
    }

    /** changes so far (live drawing redraws when it changes) */
    val revision: Int get() = revisionCount

    /** time of the last settled step (ms — -Infinity if none) and the node it is at */
    fun settled(): Pair<Double, Int?> = settledT to prevNode

    /** passes this walk added to the edge (0 if not passed) */
    fun passesOf(a: Int, b: Int): Int = passes[exploreEdgeKey(a, b)] ?: 0

    private fun stepTail(last: ExplorePoint?) {
        if (last != null && last.onFoot && lastStep != null && carryM >= rules.stepM / 2) {
            step(StepPoint(last.t, last.lat, last.lng, last.weak, false, accOf(last), last.paced))
        }
    }

    private fun finishRun(last: ExplorePoint?) {
        stepTail(last)
        breakRun()
    }

    private fun breakRun() {
        releaseAtBreak()
        retrace = null
        if (prevNode != null) resumeNode = prevNode
        lastStep = null
        prevNode = null
        pathAt = null
        lastFree = false
        skippedM = 0.0
        trail = ArrayList()
        carryM = 0.0
    }

    private fun releaseAtBreak() {
        if (heldKind != HeldGroup.OWN) {
            release(Release.RIDE)
            return
        }
        release(uTurnVerdict() ?: Release.NEW)
    }

    /** median distance of the held returning steps within the band → RIDE (turned back), else NEW; null without returning steps */
    private fun uTurnVerdict(): Release? {
        val back = held.filter { it.kind == HeldKind.BACK && it.near != null }
        if (back.isEmpty()) return null
        val ds = back.map { it.near!!.d }.sorted()
        val half = ds.size / 2
        val mid = if (ds.size % 2 == 1) ds[half] else (ds[half - 1] + ds[half]) / 2
        val snapM = exploreSnapRadiusM(back[back.size - 1].point.acc, rules)
        return if (mid <= snapM + rules.driftBandM) Release.RIDE else Release.NEW
    }

    private fun step(point: StepPoint) {
        val previous = lastStep
        paced = point.paced
        var walked = 0.0
        if (previous != null && !point.restart) {
            walked = exploreDistanceM(previous, point)
            along += walked
            outOnFootM += walked
        }
        lastStep = point
        if (point.restart) trail = ArrayList()
        trail.add(point)
        if (trail.size > 3) trail.removeAt(0)
        val along = this.along
        val snapM = exploreSnapRadiusM(point.acc, rules)
        val entry = Held(point, walked, along, null, HeldKind.DRIFT, snapM + rules.driftBandM)

        // first point after a pause/break: next to the last node before it → join there
        val resume = resumeNode
        if (point.restart && resume != null) {
            val node = graph.node(resume)
            val d = if (node != null) exploreDistanceM(point, node) else Double.POSITIVE_INFINITY
            if (d <= snapM) {
                prevNode = resume
                lastFree = false
                visitNode(resume)
                traceStart(resume)
                settle(point.t)
                return
            }
            if (d <= snapM + rules.driftBandM) {
                prevNode = resume
                lastFree = false
                createFree(entry)
                return
            }
        }

        val searchM = snapM + rules.ownBandM + rules.driftBandM
        var found = candidates(point, along, searchM)
        var bandM = bandFor(found.valid, snapM)
        var kind = kindOf(point, found, snapM, bandM)
        if (held.isNotEmpty()) {
            val settled = settleHeld(kind, found.valid, entry, bandM)
            if (settled == Settled.TAKEN) return
            if (settled == Settled.CHANGED) {
                found = candidates(point, along, searchM)
                bandM = bandFor(found.valid, snapM)
                kind = kindOf(point, found, snapM, bandM)
            }
        }
        val valid = found.valid
        if (kind == StepKind.SNAP && valid != null) {
            moveTo(valid, walked, along)
            settle(point.t)
            return
        }
        if (kind == StepKind.DRIFT && valid != null) {
            entry.near = valid
            entry.bandM = bandM
            hold(entry, HeldGroup.DRIFT)
            if (heldLength(HeldKind.DRIFT) >= rules.driftHoldM) release(Release.RIDE)
            return
        }
        if (kind == StepKind.BACK && valid != null) {
            entry.near = valid
            entry.kind = HeldKind.BACK
            hold(entry, HeldGroup.OWN)
            if (held.count { it.kind == HeldKind.BACK } >= rules.uTurnPoints) release(uTurnVerdict() ?: Release.NEW)
            return
        }
        val own = found.own
        if (kind == StepKind.OWN && own != null) {
            entry.near = own
            entry.kind = HeldKind.OWN
            hold(entry, HeldGroup.OWN)
            if (heldLength(HeldKind.OWN) >= rules.ownHoldM) release(Release.NEW)
            return
        }
        createFree(entry)
    }

    private fun candidates(point: StepPoint, along: Double, radiusM: Double): Found {
        var valid: ExploreNear? = null
        var own: ExploreNear? = null
        for (near in graph.near(point, radiusM)) {
            if (counts(near, along)) {
                if (closer(near, valid)) valid = near
            } else if (closer(near, own)) {
                own = near
            }
        }
        return Found(valid, own)
    }

    private fun bandFor(near: ExploreNear?, snapM: Double): Double {
        val riding = prevNode != null && !lastFree
        val ownLine = near != null && createdAt(near) != null
        if (riding || heldKind == HeldGroup.DRIFT) return snapM + (if (ownLine) rules.ownBandM else rules.driftBandM)
        if (heldKind == HeldGroup.OWN) return snapM
        return snapM + rules.freeBandM
    }

    private fun kindOf(point: StepPoint, found: Found, snapM: Double, bandM: Double): StepKind {
        val valid = found.valid
        val own = found.own
        if (valid != null && valid.d <= snapM) return StepKind.SNAP
        val riding = prevNode != null && !lastFree
        val first = prevNode == null && trail.size < 2
        if (valid != null && valid.d <= bandM && !valid.tip && (heldKind == HeldGroup.DRIFT || riding || first || parallel(valid))) {
            return StepKind.DRIFT
        }
        if (
            heldKind == HeldGroup.OWN &&
            valid != null &&
            valid.d <= snapM + rules.ownBandM &&
            !valid.tip &&
            createdAt(valid) != null &&
            parallel(valid)
        ) {
            return StepKind.BACK
        }
        if (own != null && own.d <= snapM + rules.driftBandM && !own.tip && !point.weak && heldKind != HeldGroup.DRIFT) return StepKind.OWN
        return StepKind.FREE
    }

    private fun settleHeld(kind: StepKind, valid: ExploreNear?, entry: Held, bandM: Double): Settled {
        if (heldKind == HeldGroup.OWN) {
            if (kind == StepKind.OWN || kind == StepKind.BACK) return Settled.NONE
            if ((kind == StepKind.SNAP || kind == StepKind.DRIFT) && valid != null && isOwnTarget(valid)) {
                release(Release.RIDE)
                return Settled.MOVED
            }
            release(Release.NEW)
            return Settled.CHANGED
        }
        if (kind == StepKind.DRIFT) return Settled.NONE
        if (kind == StepKind.SNAP) {
            release(Release.RIDE)
            return Settled.MOVED
        }
        val out = valid != null && valid.d <= bandM + rules.driftBandM
        if (out) {
            entry.near = valid
            entry.kind = HeldKind.OUT
            entry.bandM = bandM
            held.add(entry)
            if (trailingOutM() < rules.exitConfirmM) return Settled.TAKEN
        }
        release(Release.SPLIT)
        return if (out) Settled.TAKEN else Settled.CHANGED
    }

    private fun trailingOutM(): Double {
        var sum = 0.0
        var i = held.size - 1
        while (i >= 0 && held[i].kind == HeldKind.OUT) {
            sum += held[i].walked
            i -= 1
        }
        return sum
    }

    private fun counts(near: ExploreNear, along: Double): Boolean {
        val created = createdAt(near) ?: return true
        return near.d <= rules.ownRatio * (along - created)
    }

    private fun createdAt(near: ExploreNear): Double? {
        val ca = createdAlong[near.a]
        val cb = if (near.a == near.b) ca else createdAlong[near.b]
        if (ca != null && cb != null) return ca + (cb - ca) * near.t
        return if (near.t <= 0.5) ca else cb
    }

    private fun parallel(near: ExploreNear): Boolean {
        val trail = this.trail
        if (trail.size < 2 || (near.ux == 0.0 && near.uy == 0.0)) return false
        val from = trail[0]
        val to = trail[trail.size - 1]
        val dx = (to.lng - from.lng) * EXPLORE_METERS_PER_DEGREE * StrictMath.cos(to.lat * EXPLORE_RAD)
        val dy = (to.lat - from.lat) * EXPLORE_METERS_PER_DEGREE
        val len = sqrt(dx * dx + dy * dy)
        if (len < rules.stepM / 2) return false
        return abs((dx * near.ux + dy * near.uy) / len) >= rules.parallelCos
    }

    private fun targetOf(near: ExploreNear): Int? {
        val first = if (near.t <= 0.5) near.a else near.b
        if (graph.node(first) != null) return first
        val second = if (first == near.a) near.b else near.a
        return if (graph.node(second) != null) second else null
    }

    private fun isOwnTarget(near: ExploreNear): Boolean {
        val target = targetOf(near)
        return target != null && createdAlong.containsKey(target)
    }

    private fun hold(entry: Held, kind: HeldGroup) {
        held.add(entry)
        heldKind = kind
    }

    private fun heldLength(kind: HeldKind?): Double {
        var sum = 0.0
        for (entry in held) if (kind == null || entry.kind == kind) sum += entry.walked
        return sum
    }

    private fun release(mode: Release) {
        val held = this.held
        if (held.isEmpty()) return
        this.held = ArrayList()
        heldKind = null
        var firstNew = if (mode == Release.RIDE) held.size else if (mode == Release.NEW) 0 else held.size
        if (mode == Release.SPLIT) {
            while (firstNew > 0) {
                val entry = held[firstNew - 1]
                val snapM = exploreSnapRadiusM(entry.point.acc, rules)
                val near = entry.near
                if (near == null || near.d <= (snapM + entry.bandM) / 2) break
                firstNew -= 1
            }
        }
        val pacedNow = paced
        for (i in held.indices) {
            val entry = held[i]
            paced = entry.point.paced
            val near = entry.near
            if (i < firstNew && near != null) {
                moveTo(near, entry.walked, entry.along)
                settle(entry.point.t)
            } else {
                createFree(entry)
            }
        }
        paced = pacedNow
    }

    private fun moveTo(near: ExploreNear, walked: Double, along: Double) {
        val target = targetOf(near) ?: return
        val from = prevNode
        if (from == null) {
            visitNode(target)
            prevNode = target
            traceStart(target)
            lastFree = false
            revisionCount += 1
            return
        }
        skippedM = 0.0
        if (from == target) return
        val a = graph.node(from)
        val b = graph.node(target)
        val straight = if (a != null && b != null) exploreDistanceM(a, b) else 0.0
        val path = graph.route(from, target, min(rules.routeMaxM, max(straight * rules.routeFactor, straight + rules.routeSlackM)))
        if (path != null) {
            for (i in 1 until path.size) {
                visitEdge(path[i - 1], path[i])
                visitNode(path[i])
            }
        } else {
            connect(from, target, walked, along, lastFree)
        }
        prevNode = target
        lastFree = false
        revisionCount += 1
    }

    private fun createFree(entry: Held) {
        val point = entry.point
        settle(point.t)
        if (point.weak) {
            if (prevNode != null && skippedM + entry.walked <= rules.weakSkipMaxM) {
                skippedM += entry.walked
                return
            }
            skippedM = 0.0
            prevNode = null
            pathAt = null
            lastFree = false
            retrace = null
            return
        }
        val node = graph.createNode(point.lat, point.lng, day)
        createdAlong[node.id] = entry.along
        outNewNodes += 1
        visitNode(node.id)
        val prev = prevNode
        if (prev != null) connect(prev, node.id, entry.walked + skippedM, entry.along, true) else traceStart(node.id)
        skippedM = 0.0
        prevNode = node.id
        lastFree = true
        revisionCount += 1
    }

    private fun connect(a: Int, b: Int, walked: Double, along: Double, pioneer: Boolean) {
        val na = graph.node(a) ?: return
        val nb = graph.node(b) ?: return
        val pieces = max(1.0, ceil(exploreDistanceM(na, nb) / rules.maxEdgeM - 1e-9)).toInt()
        var from = a
        var created = false
        for (i in 1 until pieces) {
            val f = i.toDouble() / pieces
            val mid = graph.createNode(na.lat + (nb.lat - na.lat) * f, na.lng + (nb.lng - na.lng) * f, day)
            createdAlong[mid.id] = along
            outNewNodes += 1
            created = visitEdge(from, mid.id) || created
            visitNode(mid.id)
            from = mid.id
        }
        created = visitEdge(from, b) || created
        if (pioneer && created) outPioneeredM += walked
    }

    private fun visitNode(id: Int) {
        if (visitedNodes.contains(id)) return
        val node = graph.node(id) ?: return
        visitedNodes.add(id)
        graph.bumpNode(node)
    }

    private fun visitEdge(from: Int, to: Int): Boolean {
        val (edge, created) = graph.ensureEdge(from, to, day)
        if (created) outNewEdges.add(EdgePair(edge.a, edge.b))
        val key = edge.a * EDGE_KEY_BASE + edge.b
        val at = trace(from, to)
        val count = passes[key]
        if (count == null) {
            passes[key] = 1
            outWalkEdges.add(EdgePair(edge.a, edge.b))
            graph.bumpEdge(edge)
            retrace = null
            countStep(at)
            return created
        }
        // this retrace came back over an edge it already passed → turned again, a new retrace
        var retrace = this.retrace
        if (retrace != null && retrace.counted.contains(key)) retrace = null
        if (retrace == null) {
            retrace = Retrace(from, ArrayList(), HashSet(), false)
            this.retrace = retrace
        }
        retrace.counted.add(key)
        if (retrace.confirmed) {
            if (addPass(key)) countStep(at)
        } else {
            retrace.pending.add(key to at)
        }
        if (!retrace.confirmed) {
            val start = graph.node(retrace.start)
            val here = graph.node(to)
            val repassM = if (paced) rules.repassPacedM else rules.repassM
            if (start != null && here != null && exploreDistanceM(start, here) >= repassM) {
                retrace.confirmed = true
                for ((pending, index) in retrace.pending) if (addPass(pending)) countStep(index)
                retrace.pending = ArrayList()
            }
        }
        return created
    }

    private fun addPass(key: Long): Boolean {
        val count = passes[key] ?: 0
        if (count >= rules.passCap) return false
        val (a, b) = ExploreGraph.edgeEnds(key)
        val edge = graph.edge(a, b) ?: return false
        passes[key] = count + 1
        graph.bumpEdge(edge)
        revisionCount += 1
        return true
    }

    private fun traceStart(node: Int) {
        if (pathAt == node) return
        path.add(0)
        path.add(node)
        pathAt = node
    }

    private fun trace(from: Int, to: Int): Int {
        if (pathAt != from) {
            path.add(0)
            path.add(from)
        }
        path.add(-to)
        pathAt = to
        return path.size - 1
    }

    private fun countStep(at: Int) {
        path[at] = abs(path[at])
    }

    private fun settle(t: Double) {
        if (t > settledT) settledT = t
    }
}

private fun accOf(point: ExplorePoint): Double? {
    val acc = point.acc
    return if (acc != null && acc.isFinite() && acc > 0) acc else null
}

private fun largerAcc(a: Double?, b: Double?): Double? {
    if (a == null) return b
    if (b == null) return a
    return if (a > b) a else b
}
