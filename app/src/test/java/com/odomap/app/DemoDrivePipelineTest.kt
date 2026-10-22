package com.odomap.app

import com.odomap.app.engine.DriveEngine
import com.odomap.app.engine.FinishedDrive
import com.odomap.app.engine.edgeLengthM
import com.odomap.app.engine.edgesToGeoJson
import com.odomap.app.model.SourceEvent
import com.odomap.app.replay.DemoDriveFormat
import com.odomap.app.store.DriveStore
import com.odomap.app.store.GraphStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The recorder's path without Android: demo drive file → events (gear D … P) → DriveEngine on the cumulative graph → stores → reload.
 * Also renders the drawn lines to PNG (system property odomap.render.dir) for a visual check of thickness levels.
 */
class DemoDrivePipelineTest {
    private val assets = File("src/main/assets/drives")

    private fun drive(engine: DriveEngine, id: String): FinishedDrive {
        val events = File(assets, "$id.jsonl").reader().use { DemoDriveFormat.parseDrive(it, id) }
        var title: String? = null
        var finished: FinishedDrive? = null
        for (e in events) {
            when (val ev = e.event) {
                is SourceEvent.DemoBegin -> title = ev.title
                is SourceEvent.State ->
                    if (ev.status.driving && !engine.isDriving) engine.start("t-$id", ev.status.t, title, "replay:$id")
                    else if (!ev.status.driving && engine.isDriving) finished = engine.finish(ev.status.t)
                is SourceEvent.Loc -> engine.push(ev.fix)
            }
        }
        return finished!!
    }

    @Test
    fun commuteTwiceThickensAndPersists() {
        val root = Files.createTempDirectory("odomap").toFile()
        val graphStore = GraphStore(root)
        val driveStore = DriveStore(root)
        val engine = DriveEngine(graphStore.load())

        val first = drive(engine, "commute-1-am")
        driveStore.save(first.record, first.walkEdges, first.newEdges)
        graphStore.saveDirty(engine.graph)
        render(engine, "after-commute-1-am")
        val levelsAfterFirst = engine.graph.edges().groupingBy { it.visits.coerceAtMost(5) }.eachCount()

        val second = drive(engine, "commute-2-am")
        driveStore.save(second.record, second.walkEdges, second.newEdges)
        graphStore.saveDirty(engine.graph)
        render(engine, "after-commute-2-am")
        val levelsAfterSecond = engine.graph.edges().groupingBy { it.visits.coerceAtMost(5) }.eachCount()

        val r1 = first.record
        val r2 = second.record
        println("[demo] 1st: ${r1.title} ${"%.2f".format(r1.distanceM / 1000)} km, pioneered ${"%.2f".format(r1.pioneeredM / 1000)} km, edges ${r1.edgeCount}")
        println("[demo] 2nd: ${r2.title} ${"%.2f".format(r2.distanceM / 1000)} km, pioneered ${"%.2f".format(r2.pioneeredM / 1000)} km, edges ${r2.edgeCount}")
        println("[demo] visits histogram after 1st $levelsAfterFirst, after 2nd $levelsAfterSecond")
        println("[demo] net ${"%.2f".format(engine.netLengthM() / 1000)} km, ${engine.graph.edgeCount} edges, ${graphStore.tileCount()} tiles")

        assertTrue("first drive is long", r1.distanceM > 10_000)
        assertTrue("first drive mostly pioneered", r1.pioneeredM > 0.8 * r1.distanceM)
        assertTrue("same route again: little pioneered", r2.pioneeredM < 0.2 * r2.distanceM)
        assertTrue("second pass thickens lines", (levelsAfterSecond[2] ?: 0) + (levelsAfterSecond[3] ?: 0) > (levelsAfterFirst[2] ?: 0) * 3)
        val geo = JSONObject(edgesToGeoJson(engine.graph.edges()))
        val levels = (0 until geo.getJSONArray("features").length()).map { geo.getJSONArray("features").getJSONObject(it).getJSONObject("properties").getInt("level") }
        assertTrue("level 2+ lines drawn: $levels", levels.any { it >= 2 })

        // reload from disk: same net
        val reloaded = GraphStore(root).load()
        assertEquals(engine.graph.edgeCount, reloaded.edgeCount)
        assertEquals(engine.graph.nodeCount, reloaded.nodeCount)
        assertEquals(engine.graph.edges().sumOf { it.visits }, reloaded.edges().sumOf { it.visits })
        val drives = driveStore.list()
        assertEquals(2, drives.size)
        assertEquals(r2.id.takeIf { r2.startedAt > r1.startedAt } ?: r1.id, drives.first().id)
        assertEquals(second.newEdges.size, driveStore.edges(r2.id)!!.second.size)
        root.deleteRecursively()
    }

    @Test
    fun allDemoDrivesInOrder() {
        val engine = DriveEngine()
        val ids = JSONArray(File(assets, "index.json").readText()).let { a -> List(a.length()) { a.getJSONObject(it).getString("id") } }
        for (id in ids) {
            val t0 = System.nanoTime()
            val d = drive(engine, id).record
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("[demo] $id ${d.title}: ${"%.1f".format(d.distanceM / 1000)} km, first ${"%.1f".format(d.pioneeredM / 1000)} km, repass ${d.repassEdges} (${ms} ms)")
            assertTrue(d.pioneeredM <= d.distanceM + 1)
        }
        val t0 = System.nanoTime()
        val geo = engine.netGeoJson()
        println("[demo] net ${"%.1f".format(engine.netLengthM() / 1000)} km, ${engine.graph.edgeCount} edges, geojson ${geo.length / 1024} KB in ${(System.nanoTime() - t0) / 1_000_000} ms")
        println("[demo] levels ${engine.graph.edges().groupingBy { com.odomap.core.explore.exploreLineLevel(it.visits) }.eachCount().toSortedMap()}")
        render(engine, "all-drives")
    }

    private fun render(engine: DriveEngine, name: String) {
        val dir = System.getProperty("odomap.render.dir") ?: return
        val b = engine.netBounds() ?: return
        val canvas = TestCanvas(1400, 900, 0xF4EFE5)
        val kx = Math.cos(Math.toRadians((b.minLat + b.maxLat) / 2))
        val s = minOf((canvas.w - 80) / ((b.maxLng - b.minLng) * kx), (canvas.h - 80) / (b.maxLat - b.minLat))
        fun x(lng: Double) = 40 + (lng - b.minLng) * kx * s
        fun y(lat: Double) = canvas.h - 40 - (lat - b.minLat) * s
        val lines = com.odomap.core.explore.exploreLines(engine.graph.edges(), com.odomap.app.engine.MAP_LINE_OPTIONS)
        for (line in lines.sortedBy { it.level }) {
            val radius = 0.4 + line.level * 0.7
            val alpha = 0.45 + line.level * 0.1
            for (i in 1 until line.coords.size) {
                canvas.line(x(line.coords[i - 1].lng), y(line.coords[i - 1].lat), x(line.coords[i].lng), y(line.coords[i].lat), radius, 0x22303C, alpha)
            }
        }
        val file = File(dir, "odomap-test-$name.png")
        canvas.writePng(file)
        println("[demo] rendered $file (${engine.graph.edges().sumOf { edgeLengthM(it) }.toInt()} m)")
    }
}
