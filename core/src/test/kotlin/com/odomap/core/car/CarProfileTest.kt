package com.odomap.core.car

import com.odomap.core.DAY_MS
import com.odomap.core.T0
import com.odomap.core.Via
import com.odomap.core.componentsOf
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExplorePipelineConfig
import com.odomap.core.explore.computeExploreWalk
import com.odomap.core.explore.exploreDistanceM
import com.odomap.core.graphLengthM
import com.odomap.core.plane
import com.odomap.core.run.DopplerCheck
import com.odomap.core.run.PathSmoother
import com.odomap.core.run.RawPoint
import com.odomap.core.run.RunFilter
import com.odomap.core.run.RunPause
import com.odomap.core.via
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

// Car rule set (CarProfile) on simulated drives (tools/routes, 1 Hz, AR(1) GPS noise 3–4.5 m, traffic-light stops, tunnels) and synthetic roads.
class CarProfileTest {
    private fun fresh(): ExploreGraph = CarProfile.newGraph()

    /** dead ends of the net (a single drive drawn as one line has two) */
    private fun deadEnds(graph: ExploreGraph): Int = graph.nodes().count { graph.degree(it.id) == 1 }

    private fun components(graph: ExploreGraph): Int = componentsOf(graph.edges().map { it.a to it.b })

    // ── 1. nothing dropped as "vehicle"

    @Test
    fun `counts the whole drive — nothing is dropped as vehicle`() {
        for (id in listOf("commute-1-am", "commute-3-pm", "trip-coast")) {
            val drive = loadDrive(id)
            val result = runDrive(fresh(), drive)
            assertTrue("$id counted ${result.onFootM} of ${drive.truth.lengthM}", result.onFootM >= 0.99 * drive.truth.lengthM)
            assertEquals(0.0, result.vehicleM, 0.0)
            // the phone (walking) pipeline would drop it all
            val phone = computeExploreWalk(ExploreGraph(), drive.startedAt, drive.points)
            assertTrue(phone.onFootM < 0.5 * drive.truth.lengthM)
        }
    }

    // ── 2. same road → one line

    @Test
    fun `the same commute the next day pioneers almost nothing`() {
        val graph = fresh()
        val day1 = runDrive(graph, loadDrive("commute-1-am"))
        assertTrue(day1.pioneeredM > 0.98 * day1.onFootM)
        val day2 = runDrive(graph, loadDrive("commute-2-am"))
        println("car: day 2 am pioneered %.0f m of %.0f m".format(day2.pioneeredM, day2.onFootM))
        assertTrue("day 2 pioneered ${day2.pioneeredM}", day2.pioneeredM <= 0.03 * day2.onFootM)
        assertTrue(graphLengthM(graph) < 1.03 * loadDrive("commute-1-am").truth.lengthM)
    }

    @Test
    fun `the way home on the opposite carriageway merges into the same line`() {
        val graph = fresh()
        runDrive(graph, loadDrive("commute-1-am"))
        val pm = loadDrive("commute-1-pm")
        val am = truthNamed("commute-am-hannam")
        // pm route lying within 30 m of the am route = same road (other carriageway, 10–30 m on divided roads); only ~1 km of it is within 10 m
        val shared = pm.truth.lengthM - lengthAwayM(pm.truth, am, 30.0)
        val close = pm.truth.lengthM - lengthAwayM(pm.truth, am, 10.0)
        assertTrue(shared > 2.5 * close)
        val result = runDrive(graph, pm)
        val merged = result.onFootM - result.pioneeredM
        println("car: pm merged %.0f m into the am line, shared road (≤ 30 m) %.0f m, same carriageway (≤ 10 m) %.0f m".format(merged, shared, close))
        assertTrue("merged $merged of shared $shared", merged >= 0.85 * shared)
        assertTrue("merged $merged of shared $shared", merged <= 1.1 * shared)
    }

    @Test
    fun `a detour pioneers only the part that differs`() {
        val graph = fresh()
        runDrive(graph, loadDrive("commute-1-am"))
        runDrive(graph, loadDrive("commute-2-am"))
        val day3 = loadDrive("commute-3-am") // via 성수대교 instead of 한남대교
        val expected = lengthAwayM(day3.truth, truthNamed("commute-am-hannam"), 30.0)
        val result = runDrive(graph, day3)
        println("car: day 3 detour pioneered %.0f m, expected %.0f m (route %.0f m)".format(result.pioneeredM, expected, day3.truth.lengthM))
        assertTrue("pioneered ${result.pioneeredM} expected $expected", kotlin.math.abs(result.pioneeredM - expected) <= 0.03 * day3.truth.lengthM)
    }

    // ── 3. pass counts

    @Test
    fun `five days of commuting make the trunk thick`() {
        val graph = fresh()
        for (day in 1..5) for (half in listOf("am", "pm")) runDrive(graph, loadDrive("commute-$day-$half"))
        val am = truthNamed("commute-am-hannam")
        val pm = truthNamed("commute-pm-hannam")
        var amLen = 0.0
        var amThick = 0.0
        var trunkLen = 0.0
        var trunkThick = 0.0
        for (e in graph.edges()) {
            val lat = (e.aLat + e.bLat) / 2
            val lng = (e.aLng + e.bLng) / 2
            val len = exploreDistanceM(e.aLat, e.aLng, e.bLat, e.bLng)
            if (am.distanceM(lat, lng) > 12) continue
            // the 한남대교 am route was driven on days 1, 2, 4, 5
            amLen += len
            if (e.visits >= 4) amThick += len
            // driven both ways (am + pm hannam, 8 drives) → level 4 (≥ 5 passes) or more
            if (pm.distanceM(lat, lng) <= 30) {
                trunkLen += len
                if (com.odomap.core.explore.exploreLineLevel(e.visits) >= 4) trunkThick += len
            }
        }
        println("car: am corridor %.0f m, %.0f%% with ≥ 4 passes; shared trunk %.0f m, %.0f%% at level ≥ 4".format(amLen, 100 * amThick / amLen, trunkLen, 100 * trunkThick / trunkLen))
        assertTrue("am corridor $amThick / $amLen", amThick >= 0.95 * amLen)
        assertTrue("shared trunk $trunkThick / $trunkLen", trunkLen > 2000 && trunkThick >= 0.85 * trunkLen)
    }

    // ── 4. parallel roads stay apart (synthetic, the generator's urban GPS noise: AR(1) 4.5 m)

    private fun twoDrives(a: Via, b: Via, seed: Int): Pair<ExploreGraph, com.odomap.core.explore.ExploreWalkSummary> {
        val graph = fresh()
        computeExploreWalk(graph, T0, carDrive(a, seed = seed), config = CarProfile.pipeline)
        val t1 = T0 + DAY_MS
        return graph to computeExploreWalk(graph, t1, carDrive(b, t0 = t1, seed = seed + 1000), config = CarProfile.pipeline)
    }

    @Test
    fun `parallel roads 40 m or more apart stay two lines`() {
        for (gap in listOf(40.0, 45.0, 50.0)) {
            val shares = ArrayList<Double>()
            var linked = 0
            for (seed in 1..20) {
                // A from the south, B from the north — two distinct roads gap metres apart for 3 km
                val (graph, b) = twoDrives(via(0 to -600, 0 to 0, 3000 to 0, 3000 to -600), via(0 to 640, 0 to gap, 3000 to gap, 3000 to 640), seed)
                shares.add(b.pioneeredM / b.onFootM)
                if (crossLinks(graph, gap / 2) > 0) linked += 1
            }
            // noise makes a rare short touch at 40 m (two noisy lines 4.5 m each); never at 45 m and more
            println("car: parallel %.0f m — pioneered share worst %.3f mean %.3f, cross-linked in %d of 20".format(gap, shares.min(), shares.average(), linked))
            val strict = gap >= 45
            assertTrue("gap $gap worst ${shares.min()}", shares.min() >= if (strict) 0.99 else 0.9)
            assertTrue("gap $gap mean ${shares.average()}", shares.average() >= 0.98)
            assertTrue("gap $gap linked $linked", linked <= if (strict) 0 else 2)
        }
    }

    @Test
    fun `turning off onto a road 40 m beside the known one leaves it soon`() {
        for (seed in 1..10) {
            // ride the known road for 1 km, then a 40 m side shift (service road) for 2.9 km
            val (_, b) = twoDrives(via(-1000 to 0, 3000 to 0), via(-1000 to 0, 0 to 0, 60 to 40, 3000 to 40), seed)
            assertTrue("seed $seed pioneered ${b.pioneeredM}", b.pioneeredM >= 0.85 * 2940)
        }
    }

    @Test
    fun `driving back on the other carriageway 15–25 m away is the same road`() {
        for (gap in listOf(15.0, 20.0, 25.0)) {
            val shares = ArrayList<Double>()
            for (seed in 1..20) {
                // out east on y = 0, back west gap metres north; both turn at the same side roads (x = 0 / 3000, 10 m apart)
                val (_, back) = twoDrives(via(0 to -600, 0 to 0, 3000 to 0, 3000 to -600), via(3010 to -600, 3010 to gap, -10 to gap, -10 to -600), seed)
                shares.add(back.pioneeredM / back.onFootM)
            }
            println("car: other carriageway %.0f m — pioneered share worst %.3f mean %.3f".format(gap, shares.max(), shares.average()))
            assertTrue("gap $gap worst ${shares.max()}", shares.max() <= 0.25)
            assertTrue("gap $gap mean ${shares.average()}", shares.average() <= 0.05)
        }
    }

    /** edges joining the two sides of y = splitM (local metres) away from the ends */
    private fun crossLinks(graph: ExploreGraph, splitM: Double): Int =
        graph.edges().count { e ->
            val a = plane.toXY(e.aLat, e.aLng)
            val b = plane.toXY(e.bLat, e.bLng)
            (a.y < splitM) != (b.y < splitM) && a.x > 100 && a.x < 2900
        }

    // ── 5. tunnels / GPS gaps

    @Test
    fun `tunnels are bridged — highway drives stay one line and fully counted`() {
        for (id in listOf("weekend-gapyeong", "trip-gangneung")) {
            val drive = loadDrive(id)
            val graph = fresh()
            val result = runDrive(graph, drive)
            assertTrue("$id counted ${result.onFootM}", result.onFootM >= 0.995 * drive.truth.lengthM)
            assertEquals(id, 1, components(graph))
            // the phone rules break at every tunnel
            val walking = computeExploreWalk(ExploreGraph(), drive.startedAt, drive.points, config = CarProfile.pipeline)
            assertTrue(walking.onFootM < result.onFootM - 1000)
        }
    }

    private fun withGap(fromM: Double, toM: Double, gapS: Double): List<RawPoint> {
        val before = carDrive(via(0 to 0, fromM to 0), speed = 25.0, noiseM = 3.0, seed = 3)
        val after = carDrive(via(toM to 0, toM + 2000 to 0), speed = 25.0, t0 = before.last().t + gapS * 1000, noiseM = 3.0, seed = 4)
        return before + after
    }

    @Test
    fun `a 2_5 km gap at driving speed is a straight line counted as driven`() {
        val graph = fresh()
        val result = computeExploreWalk(graph, T0, withGap(2000.0, 4500.0, 100.0), config = CarProfile.pipeline)
        assertTrue("counted ${result.onFootM}", result.onFootM > 6400)
        assertTrue(result.pioneeredM > 6300)
        assertEquals(1, components(graph))
    }

    @Test
    fun `a teleport, a too long gap or a user pause is not bridged`() {
        // 5 km in 20 s (250 m/s)
        val teleport = fresh()
        val jumped = computeExploreWalk(teleport, T0, withGap(2000.0, 7000.0, 20.0), config = CarProfile.pipeline)
        assertEquals(2, components(teleport))
        assertTrue(jumped.onFootM < 4100)
        // 4 km in 160 s — plausible speed but longer than bridgeMaxM
        val far = fresh()
        computeExploreWalk(far, T0, withGap(2000.0, 6000.0, 160.0), config = CarProfile.pipeline)
        assertEquals(2, components(far))
        // 1 km gap during a user pause
        val points = withGap(2000.0, 3000.0, 60.0)
        val pauseAt = points[80].t + 500
        val paused = fresh()
        computeExploreWalk(paused, T0, points, pauses = listOf(RunPause(pauseAt, pauseAt + 59_000)), config = CarProfile.pipeline)
        assertEquals(2, components(paused))
    }

    @Test
    fun `phone rules never bridge (defaults unchanged)`() {
        val graph = ExploreGraph()
        computeExploreWalk(graph, T0, withGap(2000.0, 2600.0, 30.0), config = ExplorePipelineConfig(onFootFilter = false))
        assertEquals(2, components(graph))
    }

    // ── 6. traffic-light stops make no hairballs

    @Test
    fun `stops at lights leave no spurs — the net is as long as the route`() {
        for (id in listOf("commute-1-am", "commute-1-pm", "commute-3-am")) {
            val drive = loadDrive(id)
            val graph = fresh()
            runDrive(graph, drive)
            val net = graphLengthM(graph)
            // road length of the route (the pm route has a real 1.1 km out-and-back that is one road)
            val road = uniqueRoadM(drive.truth)
            assertTrue("$id net $net road $road", net >= 0.95 * road && net <= 1.05 * road)
            val line = graphPolyline(graph)
            val off = line.resample(5.0).count { drive.truth.distanceM(it.lat, it.lng) > 15 } * 5.0
            assertTrue("$id off-route $off m", off < 50)
            assertTrue("$id dead ends ${deadEnds(graph)}", deadEnds(graph) <= 3)
        }
    }

    @Test
    fun `stops without Doppler speed (position only) still leave no hairballs`() {
        for (id in listOf("commute-1-am", "commute-3-am")) {
            val drive = loadDrive(id)
            val graph = fresh()
            runDrive(graph, Drive(id, drive.startedAt, drive.points.map { it.copy(speed = null, course = null) }))
            val net = graphLengthM(graph)
            val road = uniqueRoadM(drive.truth)
            assertTrue("$id net $net road $road", net >= 0.95 * road && net <= 1.05 * road)
            assertTrue("$id dead ends ${deadEnds(graph)}", deadEnds(graph) <= 4)
        }
    }

    @Test
    fun `live recording settles a prefix of the final result`() {
        val drive = loadDrive("weekend-gapyeong") // two tunnels
        val final = runDrive(fresh(), drive)
        val recorder = com.odomap.core.explore.ExploreRecorder(fresh(), drive.startedAt, config = CarProfile.pipeline)
        var previous = 0.0
        for (chunk in drive.points.chunked(97)) {
            recorder.push(chunk)
            val live = recorder.summary()
            assertTrue(live.pioneeredM >= previous && live.pioneeredM <= final.pioneeredM + 1e-6)
            previous = live.pioneeredM
        }
        val done = recorder.finish()
        assertEquals(final.pioneeredM, done.pioneeredM, 1e-9)
        assertEquals(final.onFootM, done.onFootM, 1e-9)
        assertEquals(final.walkPath, done.walkPath)
    }

    // ── 7. smoother tuned for cars

    @Test
    fun `car smoother follows the road closer than the walking one`() {
        for (id in listOf("commute-1-am", "commute-3-pm", "weekend-gapyeong")) {
            val drive = loadDrive(id)
            fun rms(f: RunFilter): Pair<Double, Int> {
                val errors = ArrayList<Double>()
                val smoother = PathSmoother(f, CarProfile.pipeline.maxSpeedMps, DopplerCheck(f)) { errors.add(drive.truth.distanceM(it.lat, it.lng)) }
                for (p in drive.points) smoother.add(p)
                smoother.close()
                return sqrt(errors.sumOf { it * it } / errors.size) to errors.size
            }
            val (car, carN) = rms(CarProfile.pipeline.runFilter)
            val (phone, _) = rms(RunFilter.DEFAULT)
            assertEquals("$id keeps every fix", drive.points.size, carN)
            println("car: $id smoothed cross-track RMS car %.2f m, phone filter %.2f m".format(car, phone))
            assertTrue("$id car $car phone $phone", car < phone * 0.97)
        }
    }

    // ── 8. speed

    @Test
    fun `a 220 km drive goes through in well under 2 s`() {
        runDrive(fresh(), loadDrive("commute-1-am")) // warm-up
        val drive = loadDrive("trip-gangneung")
        val t0 = System.nanoTime()
        runDrive(fresh(), drive)
        val ms = (System.nanoTime() - t0) / 1e6
        println("car: trip-gangneung ${drive.points.size} fixes %.1f km in %.0f ms".format(drive.truth.lengthM / 1000, ms))
        assertTrue("took $ms ms", ms < 2000)
    }
}
