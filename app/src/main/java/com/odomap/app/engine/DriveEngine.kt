package com.odomap.app.engine

import com.odomap.app.model.Bounds
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.Fix
import com.odomap.core.car.CarProfile
import com.odomap.core.explore.EdgePair
import com.odomap.core.explore.ExploreEdge
import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExploreRecorder
import com.odomap.core.explore.ExploreWalkSummary

/** Live numbers and lines of the drive in progress. */
data class LiveDrive(
    val id: String,
    val title: String?,
    val startedAt: Long,
    val distanceM: Double,
    val pioneeredM: Double,
    val fixCount: Int,
    /** this drive's known (already driven) parts, levels from the cumulative net */
    val inkGeoJson: String,
    /** this drive's first-time parts */
    val newGeoJson: String,
)

/** A finished drive: the stored summary plus its edges (for the detail map). */
class FinishedDrive(val record: DriveRecord, val walkEdges: List<EdgePair>, val newEdges: List<EdgePair>)

/**
 * The drive computation around one cumulative [ExploreGraph] (pure Kotlin — unit-tested on the JVM).
 * Not thread-safe: the caller confines it to one thread (the recorder's engine dispatcher).
 */
class DriveEngine(val graph: ExploreGraph = CarProfile.newGraph()) {
    private class Active(
        val id: String,
        val title: String?,
        val source: String,
        val startedAt: Long,
        val recorder: ExploreRecorder,
    ) {
        var fixCount = 0
        var lastT = startedAt
        var bounds: Bounds? = null
    }

    private var active: Active? = null

    val isDriving: Boolean get() = active != null
    val activeId: String? get() = active?.id

    fun start(id: String, startedAt: Long, title: String?, source: String) {
        check(active == null) { "drive already active" }
        active = Active(id, title, source, startedAt, ExploreRecorder(graph, startedAt.toDouble(), config = CarProfile.pipeline))
    }

    fun push(fix: Fix) {
        val a = active ?: return
        if (fix.t < a.startedAt) return
        a.recorder.push(fix.toRawPoint())
        a.fixCount += 1
        a.lastT = maxOf(a.lastT, fix.t)
        a.bounds = (a.bounds ?: Bounds.of(fix.lat, fix.lng)).extend(fix.lat, fix.lng)
    }

    /** Settled-so-far numbers; [withLines] also builds this drive's GeoJSON (costlier — call ~1/s). */
    fun live(withLines: Boolean): LiveDrive? {
        val a = active ?: return null
        val s = a.recorder.summary()
        return LiveDrive(
            a.id, a.title, a.startedAt, s.onFootM, s.pioneeredM, a.fixCount,
            if (withLines) edgesToGeoJson(knownEdges(s)) else "",
            if (withLines) edgesToGeoJson(edgesOf(s.newEdges)) else "",
        )
    }

    /** Ends the drive (settles everything). Returns null when nothing was driven (no fix / zero length). */
    fun finish(endedAt: Long): FinishedDrive? {
        val a = active ?: return null
        active = null
        val s = a.recorder.finish()
        if (a.fixCount == 0 && s.walkEdges.isEmpty()) return null
        val record =
            DriveRecord(
                id = a.id,
                title = a.title,
                startedAt = a.startedAt,
                endedAt = maxOf(endedAt, a.lastT),
                distanceM = s.onFootM,
                pioneeredM = s.pioneeredM,
                edgeCount = s.walkEdges.size,
                newEdgeCount = s.newEdges.size,
                repassEdges = s.walkPasses.size,
                maxPasses = s.walkPasses.maxOfOrNull { it.passes } ?: if (s.walkEdges.isEmpty()) 0 else 1,
                fixCount = a.fixCount,
                source = a.source,
                bounds = boundsOfEdges(edgesOf(s.walkEdges)) ?: a.bounds,
            )
        return FinishedDrive(record, s.walkEdges, s.newEdges)
    }

    /** Drops the active drive without settling (data reset). The graph keeps what was already added. */
    fun abandon() {
        active = null
    }

    fun netGeoJson(): String = edgesToGeoJson(graph.edges())

    fun netLengthM(): Double = graph.edges().sumOf { edgeLengthM(it) }

    fun netBounds(): Bounds? = boundsOfEdges(graph.edges())

    fun edgesOf(pairs: List<EdgePair>): List<ExploreEdge> = pairs.mapNotNull { graph.edge(it.a, it.b) }

    private fun knownEdges(s: ExploreWalkSummary): List<ExploreEdge> {
        val fresh = HashSet<Long>(s.newEdges.size * 2)
        for (p in s.newEdges) fresh.add(p.a.toLong() shl 32 or p.b.toLong())
        return s.walkEdges.filter { !fresh.contains(it.a.toLong() shl 32 or it.b.toLong()) }.mapNotNull { graph.edge(it.a, it.b) }
    }

    /** GeoJSON (ink, new) of a stored drive's edges with today's levels. */
    fun driveGeoJson(walk: List<EdgePair>, fresh: List<EdgePair>): Pair<String, String> {
        val freshKeys = HashSet<Long>(fresh.size * 2)
        for (p in fresh) freshKeys.add(p.a.toLong() shl 32 or p.b.toLong())
        val ink = walk.filter { !freshKeys.contains(it.a.toLong() shl 32 or it.b.toLong()) }
        return edgesToGeoJson(edgesOf(ink)) to edgesToGeoJson(edgesOf(fresh))
    }
}
