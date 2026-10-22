package com.odomap.core

import com.odomap.core.explore.EXPLORE_TILE_MARGIN_M
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreLinesOptions
import com.odomap.core.explore.ExploreRules
import com.odomap.core.explore.ExploreTileXY
import com.odomap.core.explore.computeExploreWalk
import com.odomap.core.explore.exploreLineLevel
import com.odomap.core.explore.exploreLineMoves
import com.odomap.core.explore.exploreLines
import com.odomap.core.explore.exploreTileBounds
import com.odomap.core.explore.exploreTileOf
import com.odomap.core.explore.exploreTilesAround
import com.odomap.core.explore.exploreTilesOfGraph
import com.odomap.core.explore.loadExploreTile
import com.odomap.core.explore.smoothExploreCoords
import com.odomap.core.geo.GeoPoint
import com.odomap.core.geo.jsHypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// Port of clientApp __tests__/run/explore/exploreTile.test.ts and parts of exploreThick.test.ts / exploreWalkPath.test.ts.
class ExploreTileTest {
    @Test
    fun `finds the z14 tile of a point and its bounds contain the point`() {
        val tile = exploreTileOf(37.5283, 126.9326)
        assertEquals(ExploreTileXY(13968, 6346), tile)
        val b = exploreTileBounds(tile)
        assertTrue(b.minLat < 37.5283 && b.maxLat > 37.5283 && b.minLng < 126.9326 && b.maxLng > 126.9326)
    }

    @Test
    fun `covers a margin around the path`() {
        val tile = exploreTileOf(37.5283, 126.9326)
        val b = exploreTileBounds(tile)
        val nearWest = GeoPoint((b.minLat + b.maxLat) / 2, b.minLng + 0.0001)
        val tiles = exploreTilesAround(listOf(nearWest), 60.0).map { it.key }
        assertTrue(tile.key in tiles)
        assertTrue(ExploreTileXY(tile.x - 1, tile.y).key in tiles)
    }

    @Test
    fun `splits a graph into tiles and loads it back to the same answers`() {
        val graph = ExploreGraph()
        computeExploreWalk(graph, T0, pathPoints(via(0 to 0, 3000 to 0, 3000 to 1500), 1.5))
        val tiles = exploreTilesOfGraph(graph)
        assertTrue(tiles.size > 1)
        assertEquals(graph.nodeCount, tiles.values.sumOf { it.n.size })
        assertEquals(graph.edgeCount, tiles.values.sumOf { it.e.size })
        val copy = ExploreGraph()
        for (data in tiles.values) loadExploreTile(copy, data)
        assertEquals(graph.nextId, copy.nextId)
        val second = pathPoints(via(3000 to 1500, 0 to 1500, 0 to 0), 1.5, t0 = T0 + DAY_MS)
        assertEquals(computeExploreWalk(graph, T0 + DAY_MS, second), computeExploreWalk(copy, T0 + DAY_MS, second))
    }

    @Test
    fun `loads only the tiles around a new walk and still gets the same answer`() {
        val full = ExploreGraph()
        computeExploreWalk(full, T0, pathPoints(via(0 to 0, 6000 to 0), 1.5))
        val tiles = exploreTilesOfGraph(full)
        val next = pathPoints(via(2000 to 5, 2600 to 5, 2600 to 300), 1.4, t0 = T0 + DAY_MS)
        val around = exploreTilesAround(next).map { it.key }.toSet()
        val partial = ExploreGraph(full.nextId)
        for ((key, data) in tiles) if (key in around) loadExploreTile(partial, data)
        assertTrue(partial.nodeCount < full.nodeCount)
        assertEquals(computeExploreWalk(full, T0 + DAY_MS, next), computeExploreWalk(partial, T0 + DAY_MS, next))
        assertEquals(exploreTilesOfGraph(full, around), exploreTilesOfGraph(partial, around))
    }

    @Test
    fun `draws one line per stretch with the same thickness`() {
        val graph = ExploreGraph()
        computeExploreWalk(graph, T0, pathPoints(via(0 to 0, 400 to 0), 1.4))
        computeExploreWalk(graph, T0 + DAY_MS, pathPoints(via(0 to 0, 200 to 0), 1.4, t0 = T0 + DAY_MS))
        val lines = exploreLines(graph.edges())
        assertEquals(listOf(1, 2), lines.map { it.level }.sorted())
        for (line in lines) assertTrue(line.coords.size > 5)
    }

    @Test
    fun `simplifies lines for the overview`() {
        val graph = ExploreGraph()
        computeExploreWalk(graph, T0, pathPoints(via(0 to 0, 1000 to 0), 1.4))
        val full = exploreLines(graph.edges())[0]
        val simple = exploreLines(graph.edges(), ExploreLinesOptions(simplifyM = 5.0))[0]
        assertTrue(simple.coords.size < full.coords.size)
        assertEquals(full.coords.first(), simple.coords.first())
        assertEquals(full.coords.last(), simple.coords.last())
    }

    @Test
    fun `reads tiles far enough around a walk`() {
        assertTrue(EXPLORE_TILE_MARGIN_M >= 120)
    }

    @Test
    fun `smooths drawn lines`() {
        fun toLatLng(xy: List<Pair<Double, Double>>) = xy.map { (x, y) -> plane.toLatLng(x, y) }
        fun toXY(c: List<GeoPoint>) = c.map { plane.toXY(it) }
        val zig = toLatLng((0 until 12).map { i -> i * 8.0 to if (i % 2 == 0) -4.0 else 4.0 })
        val smoothed = toXY(smoothExploreCoords(zig))
        assertEquals(12, smoothed.size)
        assertEquals(-4.0, smoothed[0].y, 0.05)
        assertEquals(4.0, smoothed[11].y, 0.05)
        for (p in smoothed.subList(2, 10)) assertTrue(abs(p.y) < 1)
        val straight = toLatLng((0 until 6).map { i -> i * 8.0 to 0.0 })
        toXY(smoothExploreCoords(straight)).forEachIndexed { i, p ->
            assertEquals(i * 8.0, p.x, 0.5)
            assertTrue(abs(p.y) < 0.2)
        }
        val turned = toXY(smoothExploreCoords(toLatLng(via(0 to 0, 8 to 0, 16 to 0, 24 to 0, 24 to 8, 24 to 16, 24 to 24))))
        assertTrue(jsHypot(turned[3].x - 24, turned[3].y) < 5)
        assertEquals(zig.subList(0, 2), smoothExploreCoords(zig.subList(0, 2)))
    }

    @Test
    fun `smooths only when asked — the stored net is never changed`() {
        val graph = ExploreGraph()
        computeExploreWalk(graph, T0, pathPoints(via(0 to 0, 300 to 0), 1.4, noiseM = 4.0, seed = 2))
        val before = graph.edges().map { it.copy() }
        val raw = exploreLines(graph.edges())[0]
        val smooth = exploreLines(graph.edges(), ExploreLinesOptions(smooth = true))[0]
        assertEquals(raw.coords.size, smooth.coords.size)
        assertEquals(raw.coords.first(), smooth.coords.first())
        assertEquals(raw.coords.last(), smooth.coords.last())
        assertNotEquals(raw.coords, smooth.coords)
        assertEquals(before, graph.edges().toList())
    }

    // ── exploreThick.test (subset)

    private fun walkDays(vararg walks: Via, noiseM: Double = 3.0): Pair<ExploreGraph, List<com.odomap.core.explore.ExploreWalkSummary>> {
        val graph = ExploreGraph()
        val results = walks.mapIndexed { i, v ->
            val t0 = T0 + i * DAY_MS
            computeExploreWalk(graph, t0, pathPoints(v, 1.4, t0 = t0, noiseM = noiseM, seed = i + 1))
        }
        return graph to results
    }

    @Test
    fun `an out-and-back whose way back drifted sideways is one line, two passes thick`() {
        for (drift in listOf(5, 10, 15, 20)) {
            val (graph, results) = walkDays(via(0 to 0, 400 to 0, 400 to drift, 0 to drift))
            val result = results[0]
            assertTrue("drift $drift", graphLengthM(graph) < 420)
            assertEquals(0, parallelDuplicates(graph))
            assertTrue(result.pioneeredM < 420 && result.pioneeredM <= result.onFootM)
            assertTrue(graph.edges().count { it.visits == 2 }.toDouble() / graph.edgeCount > 0.9)
            assertTrue(result.walkPasses.size.toDouble() / result.walkEdges.size > 0.9)
        }
    }

    @Test
    fun `a parallel street 25-40 m away stays its own line`() {
        for (gap in listOf(25, 30, 40)) {
            val (graph, results) = walkDays(via(0 to 0, 500 to 0), via(0 to gap, 500 to gap))
            assertTrue(results[1].pioneeredM > 480)
            assertTrue(graphLengthM(graph) > 980)
        }
    }

    @Test
    fun `three walks of the same street make it thicker`() {
        val (graph, results) = walkDays(via(0 to 0, 400 to 0), via(0 to 0, 400 to 0), via(400 to 0, 0 to 0), noiseM = 4.0)
        assertTrue(results[1].pioneeredM + results[2].pioneeredM < 20)
        assertEquals(exploreLineLevel(3), exploreLines(graph.edges()).maxOf { it.level })
    }

    @Test
    fun `walking the same street the next day 15m off adds no new path`() {
        val (graph, results) = walkDays(via(0 to 0, 500 to 0), via(500 to 15, 0 to 15))
        assertTrue(results[1].pioneeredM < ExploreRules.DEFAULT.stepM * 2)
        assertTrue(graphLengthM(graph) < 520)
    }

    // ── exploreWalkPath.test (subset)

    @Test
    fun `a 30m out-and-back is two moves on one line — to the end and back`() {
        val graph = ExploreGraph()
        val result = computeExploreWalk(graph, T0, pathPoints(via(0 to 0, 0 to 30, 0 to 0), 1.3))
        assertEquals(0, result.walkPath[0])
        assertEquals(result.walkEdges.size, result.walkPasses.size)
        val edges = result.walkEdges.map { (a, b) -> graph.edge(a, b)!!.copy(visits = 1) }
        val lines = exploreLines(edges, ExploreLinesOptions(smooth = true, nodes = true))
        assertEquals(1, lines.size)
        val moves = exploreLineMoves(lines, result.walkPath)
        assertEquals(2, moves.size)
        val (out, back) = moves.map { it!! }
        assertEquals(0, out.line)
        assertEquals(0, back.line)
        assertEquals(out.to, back.from, 0.0)
        assertEquals(1.0, abs(out.to - out.from), 0.0)
        assertEquals(out.from, back.to, 0.0)
    }
}
