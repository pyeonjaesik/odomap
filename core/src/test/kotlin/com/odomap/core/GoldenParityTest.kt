package com.odomap.core

import com.odomap.core.explore.ActivityFix
import com.odomap.core.explore.ExploreEdge
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreLine
import com.odomap.core.explore.ExploreLineMove
import com.odomap.core.explore.ExploreLinesOptions
import com.odomap.core.explore.ExplorePoint
import com.odomap.core.explore.ExploreRecorder
import com.odomap.core.explore.ExploreTileData
import com.odomap.core.explore.ExploreTileXY
import com.odomap.core.explore.ExploreWalkResult
import com.odomap.core.explore.ExploreWalkSummary
import com.odomap.core.explore.MotionActivity
import com.odomap.core.explore.OnFootMotion
import com.odomap.core.explore.OnFootSample
import com.odomap.core.explore.StepCount
import com.odomap.core.explore.TileBounds
import com.odomap.core.explore.TileEdge
import com.odomap.core.explore.TileNode
import com.odomap.core.explore.computeExploreWalk
import com.odomap.core.explore.exploreLineMoves
import com.odomap.core.explore.exploreLines
import com.odomap.core.explore.exploreTileBounds
import com.odomap.core.explore.exploreTileOf
import com.odomap.core.explore.exploreTilesAround
import com.odomap.core.explore.exploreTilesOfGraph
import com.odomap.core.explore.loadExploreTile
import com.odomap.core.explore.smoothExploreCoords
import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.LatLng
import com.odomap.core.run.RawPoint
import com.odomap.core.run.RunPause
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs

// Golden parity: each fixture in src/test/resources/golden was produced by the ORIGINAL TypeScript (tools/gen-golden.ts). The Kotlin port
// replays the same inputs and every output is compared value by value: integers / booleans / ids / orders exactly, doubles exactly or within
// 1e-9 absolute (and the number of non-bit-identical doubles is reported).

private const val TOLERANCE = 1e-9

class Diff(val name: String) {
    var numbers = 0
    var inexact = 0
    var maxDev = 0.0
    val errors = ArrayList<String>()

    fun error(path: String, message: String) {
        if (errors.size < 25) errors.add("$path: $message")
        else if (errors.size == 25) errors.add("…")
    }

    fun cmp(path: String, e: JsonElement, a: JsonElement) {
        when (e) {
            is JsonNull -> if (!(a is JsonNull || (a is JsonPrimitive && !a.isString && a.doubleOrNull?.isFinite() == false))) error(path, "expected null, got $a")
            is JsonArray -> {
                if (a !is JsonArray) return error(path, "expected array, got ${a.toString().take(80)}")
                if (a.size != e.size) error(path, "array size ${a.size} != expected ${e.size}")
                for (i in 0 until minOf(a.size, e.size)) cmp("$path[$i]", e[i], a[i])
            }
            is JsonObject -> {
                if (a !is JsonObject) return error(path, "expected object, got ${a.toString().take(80)}")
                if (a.keys != e.keys) error(path, "keys ${a.keys} != expected ${e.keys}")
                for (k in e.keys) if (a.containsKey(k)) cmp("$path.$k", e[k]!!, a[k]!!)
            }
            is JsonPrimitive -> {
                if (a !is JsonPrimitive || a is JsonNull) return error(path, "expected $e, got $a")
                if (e.isString) {
                    if (!a.isString || a.content != e.content) error(path, "expected \"${e.content}\", got $a")
                    return
                }
                val eb = e.booleanOrNull
                if (eb != null) {
                    if (a.isString || a.booleanOrNull != eb) error(path, "expected $eb, got $a")
                    return
                }
                val ed = e.double
                val ad = a.doubleOrNull
                if (ad == null || a.isString) return error(path, "expected number $ed, got $a")
                numbers += 1
                if (ed == ad) return
                val dev = abs(ed - ad)
                if (dev > maxDev) maxDev = dev
                if (dev <= TOLERANCE) inexact += 1 else error(path, "expected $ed, got $ad (Δ $dev)")
            }
        }
    }
}

// ── Kotlin → JSON in the same shape as the TS dumps

fun j(x: Any?): JsonElement =
    when (x) {
        null -> JsonNull
        is JsonElement -> x
        is Boolean -> JsonPrimitive(x)
        is Double -> if (x.isFinite()) JsonPrimitive(x) else JsonNull
        is Number -> JsonPrimitive(x)
        is String -> JsonPrimitive(x)
        is List<*> -> JsonArray(x.map { j(it) })
        is Map<*, *> -> JsonObject(x.entries.associate { (k, v) -> k.toString() to j(v) })
        else -> error("cannot convert ${x::class}")
    }

fun resultJson(r: ExploreWalkResult): JsonElement =
    j(
        mapOf(
            "pioneeredM" to r.pioneeredM,
            "onFootM" to r.onFootM,
            "newNodes" to r.newNodes,
            "newEdges" to r.newEdges.map { listOf(it.a, it.b) },
            "walkEdges" to r.walkEdges.map { listOf(it.a, it.b) },
            "walkPasses" to r.walkPasses.map { listOf(it.a, it.b, it.passes) },
            "walkPath" to r.walkPath,
        ),
    )

fun summaryJson(s: ExploreWalkSummary): JsonElement =
    j(
        mapOf(
            "pioneeredM" to s.pioneeredM,
            "onFootM" to s.onFootM,
            "newNodes" to s.newNodes,
            "newEdges" to s.newEdges.map { listOf(it.a, it.b) },
            "walkEdges" to s.walkEdges.map { listOf(it.a, it.b) },
            "walkPasses" to s.walkPasses.map { listOf(it.a, it.b, it.passes) },
            "walkPath" to s.walkPath,
            "walkS" to s.walkS,
            "runS" to s.runS,
            "vehicleS" to s.vehicleS,
            "walkM" to s.walkM,
            "runM" to s.runM,
            "vehicleM" to s.vehicleM,
        ),
    )

fun graphJson(g: ExploreGraph): JsonElement =
    j(
        mapOf(
            "nextId" to g.nextId,
            "nodes" to g.nodes().map { listOf(it.id, it.lat, it.lng, it.visits, it.day) },
            "edges" to g.edges().map { listOf(it.a, it.b, it.visits, it.day, it.aLat, it.aLng, it.bLat, it.bLng) },
            "dirtyNodes" to g.dirtyNodes.toList(),
            "dirtyEdges" to g.dirtyEdges.toList(),
        ),
    )

fun tilesJson(tiles: Map<String, ExploreTileData>): JsonElement =
    j(
        tiles.entries.map { (key, data) ->
            listOf(
                key,
                mapOf(
                    "n" to data.n.map { listOf(it.id, it.latE6, it.lngE6, it.visits, it.day) },
                    "e" to data.e.map { listOf(it.a, it.b, it.visits, it.day, it.aLatE6, it.aLngE6, it.bLatE6, it.bLngE6) },
                ),
            )
        },
    )

fun coordsJson(coords: List<GeoPoint>): List<List<Double>> = coords.map { listOf(it.lat, it.lng) }

fun linesJson(lines: List<ExploreLine>): JsonElement =
    j(
        lines.map { l ->
            val m = linkedMapOf<String, Any?>("level" to l.level, "coords" to coordsJson(l.coords))
            if (l.nodes != null) {
                m["nodes"] = l.nodes
                m["stops"] = l.stops
            }
            m
        },
    )

fun movesJson(moves: List<ExploreLineMove?>): JsonElement = j(moves.map { m -> m?.let { listOf(it.line, it.from, it.to) } })

fun sampleJson(s: OnFootSample): List<Any?> = listOf(s.t, s.lat, s.lng, s.speed, s.weak, s.restart, s.acc)

fun pointJson(p: ExplorePoint): List<Any?> = listOf(p.t, p.lat, p.lng, p.onFoot, p.weak, p.restart, p.acc, p.paced)

fun tileXYJson(t: ExploreTileXY) = mapOf("x" to t.x, "y" to t.y)

fun boundsJson(b: TileBounds) = mapOf("minLat" to b.minLat, "minLng" to b.minLng, "maxLat" to b.maxLat, "maxLng" to b.maxLng)

// ── JSON → Kotlin inputs

private fun JsonElement?.numOrNull(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

private fun JsonElement?.numOrNaN(): Double = numOrNull() ?: Double.NaN

fun rawPoint(e: JsonElement): RawPoint {
    val o = e.jsonObject
    return RawPoint(
        lat = o["lat"].numOrNaN(),
        lng = o["lng"].numOrNaN(),
        t = o["t"].numOrNaN(),
        acc = o["acc"].numOrNull(),
        alt = o["alt"].numOrNull(),
        speed = o["speed"].numOrNull(),
        course = o["course"].numOrNull(),
        sacc = o["sacc"].numOrNull(),
        vacc = o["vacc"].numOrNull(),
    )
}

fun explorePoint(e: JsonElement): ExplorePoint {
    val o = e.jsonObject
    return ExplorePoint(
        t = o["t"].numOrNaN(),
        lat = o["lat"].numOrNaN(),
        lng = o["lng"].numOrNaN(),
        onFoot = o["onFoot"]?.jsonPrimitive?.booleanOrNull == true,
        weak = o["weak"]?.jsonPrimitive?.booleanOrNull == true,
        restart = o["restart"]?.jsonPrimitive?.booleanOrNull == true,
        acc = o["acc"].numOrNull(),
        paced = o["paced"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

fun motionOf(e: JsonElement?): OnFootMotion? {
    if (e == null || e is JsonNull) return null
    val o = e.jsonObject
    val steps = o["steps"]?.jsonArray?.map { StepCount(it.jsonArray[0].numOrNaN(), it.jsonArray[1].numOrNaN()) }
    val activity = o["activity"]?.jsonArray?.map {
        ActivityFix(it.jsonArray[0].numOrNaN(), MotionActivity.of(it.jsonArray[1].jsonPrimitive.content), it.jsonArray[2].numOrNaN())
    }
    return OnFootMotion(steps, activity)
}

class WalkInput(val startedAt: Double, val points: List<RawPoint>, val pauses: List<RunPause>, val motion: OnFootMotion?)

fun walkInput(e: JsonElement): WalkInput {
    val o = e.jsonObject
    return WalkInput(
        o["startedAt"].numOrNaN(),
        o["points"]!!.jsonArray.map(::rawPoint),
        o["pauses"]?.jsonArray?.map { RunPause(it.jsonObject["start"].numOrNaN(), it.jsonObject["end"].numOrNull()) } ?: emptyList(),
        motionOf(o["motion"]),
    )
}

fun tilesOf(e: JsonElement): LinkedHashMap<String, ExploreTileData> {
    val out = LinkedHashMap<String, ExploreTileData>()
    for (entry in e.jsonArray) {
        val key = entry.jsonArray[0].jsonPrimitive.content
        val data = entry.jsonArray[1].jsonObject
        val n = data["n"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double.toLong() } }.map { TileNode(it[0].toInt(), it[1], it[2], it[3].toInt(), it[4].toInt()) }
        val ed = data["e"]!!.jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double.toLong() } }
            .map { TileEdge(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4], it[5], it[6], it[7]) }
        out[key] = ExploreTileData(n.toMutableList(), ed.toMutableList())
    }
    return out
}

fun coordsOf(e: JsonElement): List<GeoPoint> = e.jsonArray.map { GeoPoint(it.jsonArray[0].numOrNaN(), it.jsonArray[1].numOrNaN()) }

object Golden {
    fun read(name: String): JsonObject {
        val text = Golden::class.java.getResource("/golden/$name.json")?.readText() ?: error("missing fixture $name")
        return Json.parseToJsonElement(text).jsonObject
    }

    val index: List<Pair<String, String>> by lazy {
        val text = Golden::class.java.getResource("/golden/index.json")!!.readText()
        Json.parseToJsonElement(text).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["kind"]!!.jsonPrimitive.content }
    }
}

@RunWith(Parameterized::class)
class GoldenParityTest(private val name: String, private val kind: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<Any>> = Golden.index.map { arrayOf<Any>(it.first, it.second) }

        private val diffs = ArrayList<Diff>()

        @JvmStatic
        @AfterClass
        fun report() {
            val numbers = diffs.sumOf { it.numbers }
            val inexact = diffs.sumOf { it.inexact }
            val maxDev = diffs.maxOfOrNull { it.maxDev } ?: 0.0
            val failed = diffs.count { it.errors.isNotEmpty() }
            println("[golden] ${diffs.size} fixtures, ${diffs.size - failed} passed, $numbers numbers compared, $inexact not bit-identical (max |Δ| = $maxDev)")
            for (d in diffs) if (d.inexact > 0) println("[golden]   ${d.name}: ${d.inexact} inexact, max |Δ| ${d.maxDev}")
        }
    }

    @Test
    fun parity() {
        val f = Golden.read(name)
        val diff = Diff(name)
        when (kind) {
            "pipeline" -> pipeline(f, diff)
            "graph" -> graphOnly(f, diff)
            "live" -> live(f, diff)
            "liveMotion" -> liveMotion(f, diff)
            "tilesRoundtrip" -> tilesRoundtrip(f, diff)
            "tilesPartial" -> tilesPartial(f, diff)
            "tileMath" -> tileMath(f, diff)
            else -> error("unknown kind $kind")
        }
        synchronized(diffs) { diffs.add(diff) }
        assertTrue("$name parity mismatches:\n" + diff.errors.joinToString("\n"), diff.errors.isEmpty())
    }

    private fun drawing(f: JsonObject, graph: ExploreGraph, last: ExploreWalkResult?, diff: Diff) {
        if (f.containsKey("tiles")) {
            diff.cmp("tiles", f["tiles"]!!, tilesJson(exploreTilesOfGraph(graph)))
            diff.cmp("linesRaw", f["linesRaw"]!!, linesJson(exploreLines(graph.edges())))
            diff.cmp("linesSmooth", f["linesSmooth"]!!, linesJson(exploreLines(graph.edges(), ExploreLinesOptions(smooth = true, nodes = true))))
            diff.cmp("linesSimplified", f["linesSimplified"]!!, linesJson(exploreLines(graph.edges(), ExploreLinesOptions(simplifyM = 5.0, minLengthM = 20.0))))
        }
        if (f.containsKey("walkLines") && last != null) {
            val edges = last.walkEdges.map { (a, b) -> graph.edge(a, b)!!.copy(visits = 1) }
            val lines = exploreLines(edges, ExploreLinesOptions(smooth = true, nodes = true))
            diff.cmp("walkLines", f["walkLines"]!!, linesJson(lines))
            diff.cmp("walkMoves", f["walkMoves"]!!, movesJson(exploreLineMoves(lines, last.walkPath)))
        }
        val probes = f["probes"]?.jsonObject ?: return
        val near = probes["near"]!!.jsonArray.map { p ->
            val o = p.jsonObject
            val point = GeoPoint(o["lat"].numOrNaN(), o["lng"].numOrNaN())
            mapOf(
                "lat" to point.lat,
                "lng" to point.lng,
                "near" to graph.near(point, 30.0).map { listOf(it.a, it.b, it.t, it.d, it.tip, it.ux, it.uy) },
                "nearest" to graph.nearest(point, 30.0)?.id,
            )
        }
        diff.cmp("probes.near", probes["near"]!!, j(near))
        val routes = probes["routes"]!!.jsonArray.map { r ->
            val a = r.jsonArray
            val from = a[0].jsonPrimitive.int
            val to = a[1].jsonPrimitive.int
            val max = a[2].jsonPrimitive.double
            listOf(from, to, max, graph.route(from, to, max))
        }
        diff.cmp("probes.routes", probes["routes"]!!, j(routes))
    }

    private fun pipeline(f: JsonObject, diff: Diff) {
        val graph = ExploreGraph()
        var last: ExploreWalkSummary? = null
        f["walks"]!!.jsonArray.forEachIndexed { i, w ->
            val o = w.jsonObject
            val input = walkInput(o["input"]!!)
            val samples = ArrayList<List<Any?>>()
            val decisions = ArrayList<List<Any?>>()
            graph.clearDirty()
            val summary = computeExploreWalk(graph, input.startedAt, input.points, input.pauses, input.motion,
                onSample = { samples.add(sampleJson(it)) }, onPoint = { decisions.add(pointJson(it)) })
            diff.cmp("walks[$i].samples", o["samples"]!!, j(samples))
            diff.cmp("walks[$i].decisions", o["decisions"]!!, j(decisions))
            diff.cmp("walks[$i].summary", o["summary"]!!, summaryJson(summary))
            last = summary
        }
        diff.cmp("graph", f["graph"]!!, graphJson(graph))
        val l = last
        drawing(f, graph, l?.let { ExploreWalkResult(it.pioneeredM, it.onFootM, it.newNodes, it.newEdges, it.walkEdges, it.walkPasses, it.walkPath) }, diff)
    }

    private fun graphOnly(f: JsonObject, diff: Diff) {
        val graph = ExploreGraph()
        var last: ExploreWalkResult? = null
        f["walks"]!!.jsonArray.forEachIndexed { i, w ->
            val o = w.jsonObject
            val input = o["input"]!!.jsonObject
            graph.clearDirty()
            val result = graph.addWalk(input["points"]!!.jsonArray.map(::explorePoint), input["day"]!!.jsonPrimitive.int)
            diff.cmp("walks[$i].result", o["result"]!!, resultJson(result))
            last = result
        }
        diff.cmp("graph", f["graph"]!!, graphJson(graph))
        drawing(f, graph, last, diff)
    }

    private fun live(f: JsonObject, diff: Diff) {
        val input = walkInput(f["input"]!!)
        val graph = ExploreGraph()
        val samples = ArrayList<List<Any?>>()
        val decisions = ArrayList<List<Any?>>()
        val live = ExploreRecorder(graph, input.startedAt, input.motion, onSample = { samples.add(sampleJson(it)) }, onPoint = { decisions.add(pointJson(it)) })
        val snapshots = ArrayList<Any?>()
        for (op in f["ops"]!!.jsonArray) {
            val a = op.jsonArray
            when (a[0].jsonPrimitive.content) {
                "pause" -> live.pause(a[1].jsonPrimitive.double)
                "resume" -> live.resume(a[1].jsonPrimitive.double)
                "push" -> {
                    live.push(input.points.subList(a[1].jsonPrimitive.int, a[2].jsonPrimitive.int))
                    val (t, revision) = live.settled()
                    snapshots.add(mapOf("summary" to summaryJson(live.summary()), "settledT" to t, "revision" to revision))
                }
            }
        }
        diff.cmp("snapshots", f["snapshots"]!!, j(snapshots))
        val finished = live.finish()
        diff.cmp("finished", f["finished"]!!, summaryJson(finished))
        diff.cmp("samples", f["samples"]!!, j(samples))
        diff.cmp("decisions", f["decisions"]!!, j(decisions))
        diff.cmp("graph", f["graph"]!!, graphJson(graph))
        // finishing a live recording = computing at the end (TS asserts the same)
        val atEnd = computeExploreWalk(ExploreGraph(), input.startedAt, input.points, input.pauses, input.motion)
        diff.cmp("atEndEqualsFinished", f["atEndEqualsFinished"]!!, j(atEnd == finished))
    }

    private fun liveMotion(f: JsonObject, diff: Diff) {
        val input = walkInput(f["input"]!!)
        val steps = ArrayList<StepCount>()
        val activity = ArrayList<ActivityFix>()
        val allSteps = input.motion!!.steps!!
        val allActivity = input.motion.activity!!
        val live = ExploreRecorder(ExploreGraph(), input.startedAt, OnFootMotion(steps, activity))
        val lens = f["motionLens"]!!.jsonArray
        val pioneered = ArrayList<Double>()
        input.points.forEachIndexed { i, point ->
            val (ns, na) = lens[i].jsonArray.map { it.jsonPrimitive.int }
            while (steps.size < ns) steps.add(allSteps[steps.size])
            while (activity.size < na) activity.add(allActivity[activity.size])
            live.push(point)
            pioneered.add(live.summary().pioneeredM)
        }
        diff.cmp("pioneered", f["pioneered"]!!, j(pioneered))
        diff.cmp("finished", f["finished"]!!, summaryJson(live.finish()))
    }

    private fun tilesRoundtrip(f: JsonObject, diff: Diff) {
        val first = walkInput(f["first"]!!)
        val second = walkInput(f["second"]!!)
        // the Kotlin fake-path generator reproduces the TS inputs bit for bit
        diff.cmp("first.points", f["first"]!!.jsonObject["points"]!!, pointsJson(pathPoints(via(0 to 0, 3000 to 0, 3000 to 1500), 1.5)))
        val graph = ExploreGraph()
        computeExploreWalk(graph, first.startedAt, first.points)
        val tiles = exploreTilesOfGraph(graph)
        diff.cmp("tilesAfterFirst", f["tilesAfterFirst"]!!, tilesJson(tiles))
        val copy = ExploreGraph()
        for (data in tilesOf(f["tilesAfterFirst"]!!).values) loadExploreTile(copy, data)
        diff.cmp("copyNextId", f["copyNextId"]!!, j(copy.nextId))
        val a = computeExploreWalk(graph, second.startedAt, second.points)
        val b = computeExploreWalk(copy, second.startedAt, second.points)
        diff.cmp("resultFull", f["resultFull"]!!, summaryJson(a))
        diff.cmp("resultCopy", f["resultCopy"]!!, summaryJson(b))
        diff.cmp("graphFull", f["graphFull"]!!, graphJson(graph))
        diff.cmp("graphCopy", f["graphCopy"]!!, graphJson(copy))
        diff.cmp("tilesFinal", f["tilesFinal"]!!, tilesJson(exploreTilesOfGraph(graph)))
    }

    private fun tilesPartial(f: JsonObject, diff: Diff) {
        val first = walkInput(f["first"]!!)
        val next = walkInput(f["next"]!!)
        val full = ExploreGraph()
        computeExploreWalk(full, first.startedAt, first.points)
        diff.cmp("tilesAfterFirst", f["tilesAfterFirst"]!!, tilesJson(exploreTilesOfGraph(full)))
        val aroundList = exploreTilesAround(next.points)
        diff.cmp("around", f["around"]!!, j(aroundList.map(::tileXYJson)))
        val around = aroundList.map { it.key }.toSet()
        val partial = ExploreGraph(full.nextId)
        for ((key, data) in exploreTilesOfGraph(full)) if (key in around) loadExploreTile(partial, data)
        diff.cmp("partialNodeCount", f["partialNodeCount"]!!, j(partial.nodeCount))
        val a = computeExploreWalk(full, next.startedAt, next.points)
        val b = computeExploreWalk(partial, next.startedAt, next.points)
        diff.cmp("resultFull", f["resultFull"]!!, summaryJson(a))
        diff.cmp("resultPartial", f["resultPartial"]!!, summaryJson(b))
        diff.cmp("changedPartial", f["changedPartial"]!!, tilesJson(exploreTilesOfGraph(partial, around)))
        diff.cmp("changedFull", f["changedFull"]!!, tilesJson(exploreTilesOfGraph(full, around)))
        diff.cmp("linesOverview", f["linesOverview"]!!, linesJson(exploreLines(full.edges(), ExploreLinesOptions(simplifyM = 25.0, minLengthM = 50.0, smooth = true))))
    }

    private fun tileMath(f: JsonObject, diff: Diff) {
        val tileOf = f["tileOf"]!!.jsonArray.map { r ->
            val lat = r.jsonArray[0].numOrNaN()
            val lng = r.jsonArray[1].numOrNaN()
            listOf(lat, lng, tileXYJson(exploreTileOf(lat, lng)), tileXYJson(exploreTileOf(lat, lng, 11)))
        }
        diff.cmp("tileOf", f["tileOf"]!!, j(tileOf))
        val bounds = f["tileOf"]!!.jsonArray.map { r ->
            val lat = r.jsonArray[0].numOrNaN()
            val lng = r.jsonArray[1].numOrNaN()
            val tile = exploreTileOf(lat, lng)
            listOf(tileXYJson(tile), boundsJson(exploreTileBounds(tile)), boundsJson(exploreTileBounds(exploreTileOf(lat, lng, 11), 11)))
        }
        diff.cmp("bounds", f["bounds"]!!, j(bounds))
        val pts = f["tileOf"]!!.jsonArray.map { r -> GeoPoint(r.jsonArray[0].numOrNaN(), r.jsonArray[1].numOrNaN()) }
        val around = listOf(
            exploreTilesAround(pts.subList(0, 4), 60.0),
            exploreTilesAround(pathPoints(via(0 to 0, 2500 to 0, 2500 to 2500), 25.0), 120.0),
            exploreTilesAround(listOf<LatLng>(GeoPoint(Double.NaN, 1.0), GeoPoint(37.5, 127.0)), 2000.0),
        ).map { list -> list.map(::tileXYJson) }
        diff.cmp("around", f["around"]!!, j(around))
        val shapes = f["shapes"]!!.jsonArray.map(::coordsOf)
        // the Kotlin plane projection matches the TS one
        diff.cmp("shapes[0]", f["shapes"]!!.jsonArray[0], j(coordsJson((0 until 12).map { i -> plane.toLatLng(i * 8.0, if (i % 2 == 0) -4.0 else 4.0) })))
        val smooth = shapes.map { s -> listOf(smoothExploreCoords(s), smoothExploreCoords(s, 1), smoothExploreCoords(s, 0), smoothExploreCoords(s, 5)).map(::coordsJson) }
        diff.cmp("smooth", f["smooth"]!!, j(smooth))
    }
}

fun pointsJson(points: List<RawPoint>): JsonElement =
    j(points.map { mapOf("lat" to it.lat, "lng" to it.lng, "t" to it.t, "acc" to it.acc, "alt" to it.alt, "speed" to it.speed, "course" to it.course) })
