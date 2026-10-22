package com.odomap.core

import com.odomap.core.explore.EdgePass
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreRules
import com.odomap.core.explore.exploreDayOf
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.explore.exploreSnapRadiusM
import com.odomap.core.geo.jsRound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

// Port of clientApp __tests__/run/explore/exploreGraph.test.ts (the net alone: plane points straight in, 1 s apart, 1.4 m/s).
class ExploreGraphTest {
    private val rules = ExploreRules.DEFAULT
    private val day = exploreDayOf(T0)

    @Test
    fun `counts a first walk on new streets as pioneered`() {
        val graph = ExploreGraph()
        val result = graph.addWalk(graphWalk(via(0 to 0, 500 to 0)), day)
        assertTrue(result.onFootM > 495)
        assertTrue(result.pioneeredM > 490)
        assertTrue(result.pioneeredM <= result.onFootM + 0.5)
        assertTrue(result.newNodes >= floor(500 / rules.stepM).toInt())
        for (node in graph.nodes()) {
            assertEquals(1, node.visits)
            assertEquals(day, node.day)
        }
    }

    @Test
    fun `does not count the same street twice, and counts a repeat visit for thickness`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 500 to 0)), day)
        val nodes = graph.nodeCount
        val again = graph.addWalk(graphWalk(via(0 to 6, 500 to 6), t0 = T0 + 3600e3), day + 1)
        assertTrue(again.pioneeredM < 10)
        assertTrue(graph.nodeCount - nodes < 3)
        assertTrue(graph.edges().count { it.visits >= 2 } > 50)
    }

    @Test
    fun `counts a parallel alley 30m away as new`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 500 to 0)), day)
        val alley = graph.addWalk(graphWalk(via(0 to 30, 500 to 30), t0 = T0 + 3600e3), day)
        assertTrue(alley.pioneeredM > 490)
    }

    @Test
    fun `counts an out-and-back walk once and rides the same line back, two passes thick`() {
        val graph = ExploreGraph()
        val result = graph.addWalk(graphWalk(via(0 to 0, 400 to 0, 0 to 3)), day)
        assertTrue(result.onFootM > 795)
        assertTrue(result.pioneeredM > 395)
        assertTrue(result.pioneeredM < 400 + rules.stepM)
        assertTrue(graphLengthM(graph) < 410)
        assertEquals(result.walkEdges.size, result.walkPasses.size)
        for (edge in graph.edges()) assertEquals(2, edge.visits)
    }

    @Test
    fun `does not create new nodes from weak positions`() {
        val graph = ExploreGraph()
        val result = graph.addWalk(graphWalk(via(0 to 0, 300 to 0), weak = true), day)
        assertEquals(0.0, result.pioneeredM, 0.0)
        assertEquals(0, graph.nodeCount)
    }

    @Test
    fun `skips segments that were not on foot and does not join across them`() {
        val graph = ExploreGraph()
        val first = graphWalk(via(0 to 0, 200 to 0))
        val ride = graphWalk(via(200 to 0, 1200 to 0), speed = 12.0, t0 = first.last().t + 1000, onFoot = false)
        val last = graphWalk(via(1200 to 0, 1400 to 0), t0 = ride.last().t + 1000)
        val result = graph.addWalk(first + ride + last, day)
        assertTrue(result.onFootM > 390 && result.onFootM < 410)
        assertTrue(result.pioneeredM < 410)
        assertTrue(graph.nodes().none { plane.toXY(it).x > 220 && plane.toXY(it).x < 1180 })
    }

    @Test
    fun `gives the same answer for the same input, in one go or point by point`() {
        val points = graphWalk(via(0 to 0, 300 to 0, 300 to 200, -50 to 220))
        val a = ExploreGraph()
        val ra = a.addWalk(points, day)
        val b = ExploreGraph()
        val live = b.beginWalk(day)
        for (point in points) live.push(point)
        assertEquals(ra, live.end())
        assertEquals(a.nodes().toList(), b.nodes().toList())
    }

    @Test
    fun `continues the same way after saving and loading the graph`() {
        val original = ExploreGraph()
        original.addWalk(graphWalk(via(0 to 0, 300 to 0, 300 to 300)), day)
        val copy = ExploreGraph()
        for (node in original.nodes()) copy.loadNode(node)
        for (edge in original.edges()) copy.loadEdge(edge)
        assertEquals(original.nextId, copy.nextId)
        val next = graphWalk(via(300 to 300, 0 to 300, 0 to 0), t0 = T0 + 7200e3)
        assertEquals(original.addWalk(next, day + 1), copy.addWalk(next, day + 1))
    }

    @Test
    fun `rounds new node positions to 1e-6 degrees`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 100 to 0)), day)
        for (node in graph.nodes()) {
            assertEquals(node.lat, jsRound(node.lat * 1e6) / 1e6, 0.0)
            assertEquals(node.lng, jsRound(node.lng * 1e6) / 1e6, 0.0)
        }
    }

    @Test
    fun `measures short distances like haversine`() {
        assertEquals(50.0, exploreDistanceM(plane.toLatLng(0.0, 0.0), plane.toLatLng(30.0, 40.0)), 0.05)
    }

    @Test
    fun `uses the Seoul calendar day`() {
        fun utc(s: String) = Instant.parse(s).toEpochMilli().toDouble()
        assertEquals(exploreDayOf(utc("2026-10-06T00:00:00Z")), exploreDayOf(utc("2026-10-06T14:59:00Z")))
        assertEquals(exploreDayOf(utc("2026-10-06T00:00:00Z")) + 1, exploreDayOf(utc("2026-10-06T15:00:00Z")))
    }

    @Test
    fun `widens the snap distance with the position error, 12 to 24 m`() {
        assertEquals(rules.snapMinM, exploreSnapRadiusM(null), 0.0)
        assertEquals(12.0, exploreSnapRadiusM(5.0), 0.0)
        assertEquals(18.0, exploreSnapRadiusM(15.0), 1e-9)
        assertEquals(rules.snapMaxM, exploreSnapRadiusM(40.0), 0.0)
    }

    @Test
    fun `snaps onto the street line between nodes, and rides a street walked 15 m beside it`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 500 to 0)), day)
        val nodes = graph.nodeCount
        val near = graph.addWalk(graphWalk(via(4 to 11, 496 to 11), t0 = T0 + 3600e3), day + 1)
        assertEquals(0.0, near.pioneeredM, 0.0)
        assertEquals(nodes, graph.nodeCount)
        val drift = graph.addWalk(graphWalk(via(0 to 15, 500 to 15), t0 = T0 + 7200e3), day + 2)
        assertTrue(drift.pioneeredM < rules.stepM * 2)
        assertEquals(0, parallelDuplicates(graph))
        assertTrue(graph.edges().count { it.visits == 3 } > 55)
    }

    @Test
    fun `keeps a parallel street 25 m away separate`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 500 to 0)), day)
        val other = graph.addWalk(graphWalk(via(0 to 25, 500 to 25), t0 = T0 + 3600e3), day)
        assertTrue(other.pioneeredM > 480)
        assertTrue(graphLengthM(graph) > 980)
    }

    @Test
    fun `branches off where the walk really turns`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 400 to 0)), day)
        val turn = graph.addWalk(graphWalk(via(0 to 0, 200 to 0, 200 to 200), t0 = T0 + 3600e3), day + 1)
        assertTrue(turn.pioneeredM > 200 - 3 * rules.stepM)
        assertTrue(turn.pioneeredM <= turn.onFootM)
        for (node in graph.nodes().filter { it.day == day + 1 }) assertTrue(abs(plane.toXY(node).x - 200) < 8)
    }

    @Test
    fun `counts passes inside one walk up to passCap, but not a short step back`() {
        val graph = ExploreGraph()
        val v = ArrayList(via(0 to 0))
        repeat(5) { v.addAll(via(60 to 0, 0 to 0)) }
        val pacing = graph.addWalk(graphWalk(v), day)
        assertTrue(graphLengthM(graph) < 70)
        assertTrue(pacing.pioneeredM < 70)
        for (edge in graph.edges()) assertTrue(edge.visits <= rules.passCap)
        assertEquals(rules.passCap, graph.edges().maxOf { it.visits })
        val other = ExploreGraph()
        val back = other.addWalk(graphWalk(via(0 to 0, 100 to 0, 92 to 0, 200 to 0)), day)
        assertEquals(emptyList<EdgePass>(), back.walkPasses)
        for (edge in other.edges()) assertEquals(1, edge.visits)
    }

    @Test
    fun `a walk that crosses the street, turns back and ends a few steps later leaves no spike`() {
        val graph = ExploreGraph()
        val result = graph.addWalk(graphWalk(via(0 to 0, 200 to 0, 200 to 16, 176 to 16)), day)
        assertTrue(graphLengthM(graph) < 210)
        assertTrue(result.pioneeredM < 210)
    }

    @Test
    fun `never makes an edge longer than maxEdgeM`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 300 to 0)), day)
        graph.addWalk(graphWalk(via(150 to 60, 160 to 22, 175 to 0, 300 to 0), t0 = T0 + 3600e3), day + 1)
        for (edge in graph.edges()) assertTrue(exploreDistanceM(edge.aLat, edge.aLng, edge.bLat, edge.bLng) <= rules.maxEdgeM + 0.2)
    }

    @Test
    fun `finds the shortest way along the net, or nothing within the limit`() {
        val graph = ExploreGraph()
        graph.addWalk(graphWalk(via(0 to 0, 100 to 0)), day)
        val ids = graph.nodes().sortedBy { plane.toXY(it).x }.map { it.id }
        assertEquals(ids.subList(0, 4), graph.route(ids[0], ids[3], 100.0))
        assertNull(graph.route(ids[0], ids[ids.size - 1], 20.0))
        assertEquals(listOf(ids[2]), graph.route(ids[2], ids[2], 0.0))
    }

    @Test
    fun `rules are injectable — a coarser rule set builds a coarser net`() {
        val coarse = ExploreRules(stepM = 16.0, maxEdgeM = 24.0, snapMinM = 20.0, snapMaxM = 40.0)
        val fine = ExploreGraph()
        val wide = ExploreGraph(rules = coarse)
        fine.addWalk(graphWalk(via(0 to 0, 500 to 0)), day)
        wide.addWalk(graphWalk(via(0 to 0, 500 to 0)), day)
        assertTrue(wide.nodeCount < fine.nodeCount * 0.6)
        // 18 m beside: a new street with the walking rules, the same street with the coarse ones
        assertTrue(fine.addWalk(graphWalk(via(0 to 18, 500 to 18), t0 = T0 + 3600e3), day + 1).pioneeredM > 400)
        assertTrue(wide.addWalk(graphWalk(via(0 to 18, 500 to 18), t0 = T0 + 3600e3), day + 1).pioneeredM < 40)
    }
}
