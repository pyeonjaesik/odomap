package com.odomap.core.car

import com.odomap.core.graphLengthM
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

// All 14 simulated drives in order into one car net: prints the per-drive table and checks the totals.
class CarDriveReportTest {
    @Test
    fun `all drives — net length matches the unique roads, pioneered matches the new part of each route`() {
        val graph = CarProfile.newGraph()
        val driven = Polyline()
        var totalMs = 0.0
        println("car drive                 route km  counted  pioneered  expected  lv1   lv2   lv3   lv4   lv5 (km of passed net)    ms")
        for (id in ALL_DRIVES) {
            val drive = loadDrive(id)
            // new part = route farther than 30 m from every earlier route (the other carriageway counts as the same road)
            val expected = lengthAwayM(drive.truth, driven, 30.0)
            val t0 = System.nanoTime()
            val result = runDrive(graph, drive)
            val ms = (System.nanoTime() - t0) / 1e6
            totalMs += ms
            val levels = levelLengths(graph, result.walkEdges)
            println(
                "car %-22s %8.2f %8.2f %10.2f %9.2f %s %6.0f".format(
                    id, drive.truth.lengthM / 1000, result.onFootM / 1000, result.pioneeredM / 1000, expected / 1000,
                    (1..5).joinToString(" ") { "%5.1f".format(levels[it] / 1000) }, ms,
                ),
            )
            assertTrue("$id counted", result.onFootM >= 0.99 * drive.truth.lengthM)
            assertTrue("$id pioneered ${result.pioneeredM} expected $expected", abs(result.pioneeredM - expected) <= 0.03 * drive.truth.lengthM)
            for (piece in drive.truth.pieces) driven.add(piece)
        }
        // unique road length of the 8 distinct routes: within 30 m of an earlier route = same road; within 8 m = same carriageway
        val roads = Polyline()
        var road30 = 0.0
        var road8 = 0.0
        for (name in ALL_DRIVES.map { DRIVE_TRUTH[it]!! }.distinct()) {
            val route = truthNamed(name)
            road30 += lengthAwayM(route, roads, 30.0)
            road8 += lengthAwayM(route, roads, 8.0)
            for (piece in route.pieces) roads.add(piece)
        }
        val net = graphLengthM(graph)
        val off = graphPolyline(graph).resample(5.0).count { driven.distanceM(it.lat, it.lng) > 20 } * 5.0
        println(
            "car net %.2f km (%d nodes, %d edges) · unique roads %.2f km (30 m) / %.2f km (8 m, per carriageway) · net > 20 m off a route %.2f km (tunnel chords) · %.0f ms"
                .format(net / 1000, graph.nodeCount, graph.edgeCount, road30 / 1000, road8 / 1000, off / 1000, totalMs),
        )
        assertTrue(abs(net - road30) <= 0.03 * road30)
        assertTrue(off < 5000)
    }
}
