package com.odomap.core

import com.odomap.core.explore.ActivityFix
import com.odomap.core.explore.EXPLORE_ALGORITHM
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExplorePipelineConfig
import com.odomap.core.explore.ExploreRecorder
import com.odomap.core.explore.ExploreRules
import com.odomap.core.explore.MotionActivity
import com.odomap.core.explore.OnFootMotion
import com.odomap.core.explore.StepCount
import com.odomap.core.explore.computeExploreWalk
import com.odomap.core.geo.jsRound
import com.odomap.core.run.RawPoint
import com.odomap.core.run.RunPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Port of clientApp __tests__/run/explore/explorePipeline.test.ts, exploreMotion.test.ts (motion part) and exploreStill.test.ts (explore part),
// plus the car-tuning hooks.
class ExplorePipelineTest {
    @Test
    fun `algorithm version string`() {
        assertEquals("g5.f3", EXPLORE_ALGORITHM)
    }

    @Test
    fun `pioneers a clean 1km walk`() {
        val result = computeExploreWalk(ExploreGraph(), T0, pathPoints(via(0 to 0, 600 to 0, 600 to 400), 1.4))
        assertTrue(result.onFootM > 960 && result.onFootM < 1010)
        assertTrue(result.pioneeredM > 950)
        assertTrue(result.walkS > 650)
        assertEquals(0.0, result.runS, 0.0)
        assertEquals(0.0, result.vehicleS, 0.0)
    }

    @Test
    fun `barely pioneers when walking the same street again with GPS noise`() {
        val graph = ExploreGraph()
        computeExploreWalk(graph, T0, pathPoints(via(0 to 0, 800 to 0), 1.4, noiseM = 4.0, seed = 3))
        val t1 = T0 + DAY_MS
        val again = computeExploreWalk(graph, t1, pathPoints(via(0 to 0, 800 to 0), 1.4, noiseM = 4.0, seed = 9, t0 = t1))
        assertTrue(again.onFootM > 760)
        assertTrue(again.pioneeredM < 0.05 * 800)
    }

    @Test
    fun `counts running as on foot and tells it from walking`() {
        val result = computeExploreWalk(ExploreGraph(), T0, pathPoints(via(0 to 0, 2000 to 0), 3.2))
        assertTrue(result.runS > 550)
        assertTrue(result.walkS < 30)
        assertTrue(result.pioneeredM > 1950)
    }

    private fun carRide(): List<RawPoint> {
        val walkA = pathPoints(via(0 to 0, 300 to 0), 1.4)
        val ride = shiftTo(pathPoints(via(300 to 0, 3300 to 0), 11.0), walkA.last().t + 1000)
        val walkB = shiftTo(pathPoints(via(3300 to 0, 3600 to 0), 1.4), ride.last().t + 1000)
        return walkA + ride.drop(1) + walkB.drop(1)
    }

    @Test
    fun `leaves out a car ride in the middle of a walk`() {
        val result = computeExploreWalk(ExploreGraph(), T0, carRide())
        assertTrue(result.vehicleS > 200)
        assertTrue(result.vehicleM > 2500)
        assertTrue(result.onFootM > 520 && result.onFootM < 700)
        assertTrue(result.pioneeredM < 700)
    }

    @Test
    fun `leaves out a slow bus ride when the phone counted no steps`() {
        val walkA = pathPoints(via(0 to 0, 300 to 0), 1.3)
        val bus = shiftTo(pathPoints(via(300 to 0, 1500 to 0), 3.5), walkA.last().t + 1000)
        val walkB = shiftTo(pathPoints(via(1500 to 0, 1800 to 0), 1.3), bus.last().t + 1000)
        val points = walkA + bus.drop(1) + walkB.drop(1)
        val steps = ArrayList<StepCount>()
        var count = 0.0
        var t = T0
        while (t <= points.last().t) {
            steps.add(StepCount(t, jsRound(count)))
            if (t < bus[0].t || t >= bus.last().t) count += (110.0 / 60) * 5
            t += 5000
        }
        val withSteps = computeExploreWalk(ExploreGraph(), T0, points, motion = OnFootMotion(steps = steps))
        assertTrue(withSteps.vehicleM > 1000)
        assertTrue(withSteps.pioneeredM < 700)
        val speedOnly = computeExploreWalk(ExploreGraph(), T0, points)
        assertEquals(0.0, speedOnly.vehicleM, 0.0)
        assertTrue(speedOnly.runM > 1000)
    }

    @Test
    fun `trusts the phone saying it is in a car`() {
        val walkA = pathPoints(via(0 to 0, 300 to 0), 1.3)
        val car = shiftTo(pathPoints(via(300 to 0, 1500 to 0), 4.0), walkA.last().t + 1000)
        val motion = OnFootMotion(activity = listOf(ActivityFix(T0, MotionActivity.WALK, 2.0), ActivityFix(car[0].t, MotionActivity.VEHICLE, 2.0)))
        val result = computeExploreWalk(ExploreGraph(), T0, walkA + car.drop(1), motion = motion)
        assertTrue(result.vehicleM > 1000)
        assertTrue(result.walkM < 400)
    }

    @Test
    fun `does not join across a pause`() {
        val before = pathPoints(via(0 to 0, 200 to 0), 1.4)
        val pauseAt = before.last().t + 500
        val resumeAt = pauseAt + 10 * 60e3
        val after = shiftTo(pathPoints(via(1000 to 0, 1200 to 0), 1.4), resumeAt + 1000)
        val result = computeExploreWalk(ExploreGraph(), T0, before + after, listOf(RunPause(pauseAt, resumeAt)))
        assertTrue(result.onFootM > 380 && result.onFootM < 410)
    }

    @Test
    fun `never pioneers more than it walked, even when GPS noise zigzags the line`() {
        for (seed in 1..5) {
            val points = pathPoints(via(0 to 0, 180 to 0, 180 to 180, 0 to 180, 0 to 0), 1.8, noiseM = 3.0, seed = seed)
            val result = computeExploreWalk(ExploreGraph(), T0, points)
            assertTrue(result.pioneeredM > 600)
            assertTrue(result.pioneeredM <= result.onFootM)
        }
    }

    @Test
    fun `joins the line again even when the pause came part-way between two marks`() {
        val before = pathPoints(via(0 to 0, 207.5 to 0), 1.4)
        val pauseAt = before.last().t + 500
        val resumeAt = pauseAt + 30e3
        val after = shiftTo(pathPoints(via(210 to 4, 400 to 4), 1.4), resumeAt + 1000)
        val result = computeExploreWalk(ExploreGraph(), T0, before + after, listOf(RunPause(pauseAt, resumeAt)))
        assertEquals(1, componentsOf(result.newEdges.map { it.a to it.b }))
        assertTrue(result.pioneeredM <= result.onFootM)
    }

    @Test
    fun `joins the line again after a long gap without positions where the walker stood`() {
        val before = pathPoints(via(0 to 0, 103.5 to 0), 1.4)
        val after = shiftTo(pathPoints(via(106 to 3, 230 to 3), 1.4), before.last().t + 120e3)
        val result = computeExploreWalk(ExploreGraph(), T0, before + after)
        assertEquals(1, componentsOf(result.newEdges.map { it.a to it.b }))
        assertTrue(result.onFootM > 215)
        assertTrue(result.pioneeredM <= result.onFootM)
    }

    @Test
    fun `does not count standing still before finishing as walking time`() {
        val walk = pathPoints(via(0 to 0, 300 to 0), 1.4)
        val last = walk.last()
        val standing = shiftTo(pathPoints(via(300 to 0, 300 to 0.5), 0.004, noiseM = 2.0, seed = 4), last.t + 1000).map { it.copy(speed = 0.0) }
        val result = computeExploreWalk(ExploreGraph(), T0, walk + standing)
        val walkingS = (last.t - walk[0].t) / 1000
        assertTrue(result.walkS > walkingS - 30 && result.walkS < walkingS + 30)
    }

    @Test
    fun `lists the edges it walked — all of the new ones, none inside a car ride`() {
        val graph = ExploreGraph()
        val result = computeExploreWalk(graph, T0, carRide())
        val walked = result.walkEdges.toSet()
        for (edge in result.newEdges) assertTrue(edge in walked)
        for ((a, b) in result.walkEdges) {
            val x = plane.toXY(graph.edge(a, b)!!.aLat, graph.edge(a, b)!!.aLng).x
            assertTrue(x < 450 || x > 3150)
        }
        val t1 = T0 + DAY_MS
        val again = computeExploreWalk(graph, t1, pathPoints(via(0 to 0, 300 to 0), 1.4, t0 = t1))
        assertEquals(0, again.newEdges.size)
        assertTrue(again.walkEdges.size > 25)
    }

    @Test
    fun `live recording settles to the same result as computing at the end`() {
        val points = pathPoints(via(0 to 0, 500 to 0, 500 to 300, 100 to 350), 1.5, noiseM = 3.0, seed = 5)
        val atEnd = computeExploreWalk(ExploreGraph(), T0, points)
        val live = ExploreRecorder(ExploreGraph(), T0)
        var seen = 0.0
        for (chunk in points.chunked(7)) {
            live.push(chunk)
            val now = live.summary().pioneeredM
            assertTrue(now >= seen)
            seen = now
        }
        assertTrue(seen <= atEnd.pioneeredM + 1e-6)
        assertEquals(atEnd, live.finish())
    }

    @Test
    fun `tells a bike ride from a run by steps even with an Android raw step counter`() {
        val run = pathPoints(via(0 to 0, 600 to 0), 3.0)
        val bike = shiftTo(pathPoints(via(600 to 0, 2400 to 0), 5.0), run.last().t + 1000)
        val points = run + bike.drop(1)
        val steps = ArrayList<StepCount>()
        var counter = 1_234_567.0
        var t = T0
        while (t <= points.last().t) {
            steps.add(StepCount(t, jsRound(counter)))
            if (t < bike[0].t) counter += (165.0 / 60) * 5
            t += 5000
        }
        val result = computeExploreWalk(ExploreGraph(), T0, points, motion = OnFootMotion(steps = steps))
        assertTrue(result.runM > 500)
        assertTrue(result.vehicleM > 1500)
        assertTrue(result.pioneeredM < 700)
    }

    @Test
    fun `does not judge a window by steps when the counter went down`() {
        val points = pathPoints(via(0 to 0, 800 to 0), 1.6)
        val middle = points[points.size / 2].t
        val steps = listOf(StepCount(T0, 50_000.0), StepCount(middle, 50_600.0), StepCount(middle + 1000, 3.0), StepCount(points.last().t, 400.0))
        val result = computeExploreWalk(ExploreGraph(), T0, points, motion = OnFootMotion(steps = steps))
        assertEquals(0.0, result.vehicleM, 0.0)
        assertTrue(result.walkM > 700)
    }

    /** exploreStill.test: a phone on a desk — slow drift + Wi-Fi hops (accuracy 12–20 m, no Doppler) */
    private fun indoorDrift(seconds: Int, t0: Double = T0, seed: Int = 7): List<RawPoint> {
        val random = rng(seed)
        val points = ArrayList<RawPoint>()
        var bx = 0.0
        var by = 0.0
        for (s in 0 until seconds) {
            bx = bx * 0.97 + (random() - 0.5) * 3
            by = by * 0.97 + (random() - 0.5) * 3
            val hop = if (s % 9 == 0) (random() - 0.5) * 22 else 0.0
            val p = plane.toLatLng(bx * 4 + hop, by * 4 - hop / 2)
            points.add(RawPoint(p.lat, p.lng, t0 + s * 1000, acc = 12 + random() * 8))
        }
        return points
    }

    private fun stepLines(from: Double, to: Double, cadenceAt: (Double) -> Double): List<StepCount> {
        val lines = arrayListOf(StepCount(from, 0.0))
        var n = 0.0
        var t = from + 5000
        while (t <= to) {
            val added = jsRound((cadenceAt(t) / 60) * 5)
            if (added != 0.0) {
                n += added
                lines.add(StepCount(t, n))
            }
            t += 5000
        }
        return lines
    }

    @Test
    fun `standing still indoors — drifting fixes are not new paths when no steps were counted`() {
        val drift = indoorDrift(240)
        val end = drift.last().t
        val walk = computeExploreWalk(ExploreGraph(), T0, drift, motion = OnFootMotion(steps = stepLines(T0, end) { 0.0 }))
        assertEquals(0.0, walk.pioneeredM, 0.0)
        assertEquals(0.0, walk.walkS + walk.runS + walk.vehicleS, 0.0)
        // without step data the drift is counted (the old rules)
        assertTrue(computeExploreWalk(ExploreGraph(), T0, drift).pioneeredM > 0)
        // a real walk after standing is counted from where the steps start
        val walkPart = shiftTo(pathPoints(via(0 to 0, 300 to 0), 1.3, acc = 6.0, noiseM = 3.0, seed = 3), end + 1000)
        val walkStart = walkPart[0].t
        val steps = stepLines(T0, walkPart.last().t) { if (it >= walkStart) 108.0 else 0.0 }
        val after = computeExploreWalk(ExploreGraph(), T0, drift + walkPart, motion = OnFootMotion(steps = steps))
        assertTrue(after.pioneeredM > 220 && after.pioneeredM < 330)
    }

    // ── car-tuning hooks (not in the TS — these are the injection points for the in-car rule set)

    @Test
    fun `onFootFilter=false keeps a car ride in the net (the faithful default drops it)`() {
        val points = carRide()
        val faithful = computeExploreWalk(ExploreGraph(), T0, points)
        val drive = computeExploreWalk(ExploreGraph(), T0, points, config = ExplorePipelineConfig(onFootFilter = false))
        assertTrue(faithful.pioneeredM < 700)
        assertTrue(drive.pioneeredM > 3000)
        assertEquals(0.0, drive.vehicleM, 0.0)
    }

    @Test
    fun `default config and default rules reproduce the faithful port`() {
        val points = pathPoints(via(0 to 0, 400 to 0, 400 to 12, 0 to 12), 1.4, noiseM = 3.0, seed = 7)
        val a = computeExploreWalk(ExploreGraph(), T0, points)
        val b = computeExploreWalk(ExploreGraph(rules = ExploreRules()), T0, points, config = ExplorePipelineConfig())
        assertEquals(a, b)
    }
}
