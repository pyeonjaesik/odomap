package com.odomap.core.explore

import com.odomap.core.run.DopplerCheck
import com.odomap.core.run.PathSmoother
import com.odomap.core.run.RawPoint
import com.odomap.core.run.RunFilter
import com.odomap.core.run.RunPause
import com.odomap.core.run.SmoothedSample

// Port of clientApp src/services/run/explore/explorePipeline.ts. Pure computation.
//
//  raw fixes → (outside pauses, usable only) → PathSmoother (Kalman + RTS, same as the distance computation) → OnFootClassifier
//           → ExploreWalk (into the net)
// The smoother drops jumps only above EXPLORE_MAX_SPEED_MPS (80 m/s) so vehicle stretches are still tracked (and can be classified).
// Every smoothed position carries the raw fix's accuracy (v4 — snap radius) and the step-confirmed flag (v5 — short re-passes).
// Same points / pauses / steps in the same order always give the same answer (live settled part = prefix of the final computation).

/** smoother: drop fixes implying more than this speed (m/s, ~290 km/h) */
const val EXPLORE_MAX_SPEED_MPS = 80.0

/**
 * Everything that can be tuned, injectable for a future car rule set. The defaults are the phone app's (faithful port).
 * Note: the net rules ([ExploreRules]) belong to the [ExploreGraph] the walk is added to.
 */
data class ExplorePipelineConfig(
    val runFilter: RunFilter = RunFilter.DEFAULT,
    val maxSpeedMps: Double = EXPLORE_MAX_SPEED_MPS,
    val onFootRules: OnFootRules = OnFootRules.DEFAULT,
    /** CAR-TUNING HOOK: false = do not drop non-walking stretches (every sample counts; stats still split walk/run by speed) */
    val onFootFilter: Boolean = true,
) {
    companion object {
        val DEFAULT = ExplorePipelineConfig()
    }
}

/** algorithm version (net and on-foot rules); the server rebuilds stored results when it changes */
val EXPLORE_ALGORITHM: String = "${ExploreRules.DEFAULT.label}.f${OnFootRules.DEFAULT.version}"

data class ExploreWalkSummary(
    val pioneeredM: Double,
    val onFootM: Double,
    val newNodes: Int,
    val newEdges: List<EdgePair>,
    val walkEdges: List<EdgePair>,
    val walkPasses: List<EdgePass>,
    val walkPath: List<Int>,
    val walkS: Double,
    val runS: Double,
    val vehicleS: Double,
    val walkM: Double,
    val runM: Double,
    val vehicleM: Double,
) {
    companion object {
        fun of(result: ExploreWalkResult, stats: OnFootStats): ExploreWalkSummary =
            ExploreWalkSummary(
                result.pioneeredM, result.onFootM, result.newNodes, result.newEdges, result.walkEdges, result.walkPasses, result.walkPath,
                stats.walkS, stats.runS, stats.vehicleS, stats.walkM, stats.runM, stats.vehicleM,
            )
    }
}

private fun usable(point: RawPoint, filter: RunFilter): Boolean {
    val lat = point.lat
    val lng = point.lng
    val t = point.t
    if (!lat.isFinite() || !lng.isFinite() || !t.isFinite()) return false
    if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return false
    if (lat == 0.0 && lng == 0.0) return false
    val acc = point.acc
    return !(acc != null && acc.isFinite() && acc > filter.maxAccuracyM)
}

/**
 * Feeds one walk into the net as points arrive. Live: push / pause / resume, read summary() (the settled part); at the end finish().
 * The graph is modified by this walk.
 * @param onSample optional observer of smoothed samples (classifier input) — for drawing / debugging / parity tests
 * @param onPoint optional observer of classified points (net input)
 */
class ExploreRecorder(
    graph: ExploreGraph,
    private val startedAt: Double,
    motion: OnFootMotion? = null,
    private val config: ExplorePipelineConfig = ExplorePipelineConfig.DEFAULT,
    private val onSample: ((OnFootSample) -> Unit)? = null,
    private val onPoint: ((ExplorePoint) -> Unit)? = null,
) {
    private val doppler = DopplerCheck(config.runFilter)
    private var piece: PathSmoother
    private val classifier: OnFootClassifier
    private val walk: ExploreWalk = graph.beginWalk(exploreDayOf(startedAt))
    private val pauses = ArrayList<RunPause>()
    private var lastT = Double.NEGATIVE_INFINITY
    private var lastPointT = Double.NEGATIVE_INFINITY
    private var finished: ExploreWalkSummary? = null

    /** [time, accuracy] of the raw fixes given to the smoother — a smoothed position with the same time gets that accuracy */
    private val accs = ArrayList<Pair<Double, Double?>>()
    private var accHead = 0

    init {
        classifier =
            OnFootClassifier(motion ?: OnFootMotion(), config.onFootRules, config.onFootFilter) { decision ->
                val point =
                    ExplorePoint(decision.t, decision.lat, decision.lng, decision.onFoot, decision.weak, decision.restart, decision.acc, decision.paced, pausedBefore(decision.t))
                lastPointT = decision.t
                onPoint?.invoke(point)
                walk.push(point)
            }
        piece = newPiece()
    }

    /** Raw fixes (time order). Fixes before the start, while paused, or not later than the previous one are dropped. */
    fun push(points: Iterable<RawPoint>) {
        if (finished != null) return
        for (point in points) {
            if (!usable(point, config.runFilter) || point.t <= lastT || point.t < startedAt) continue
            lastT = point.t
            val open = pauses.lastOrNull()
            if (open != null && open.end == null && point.t >= open.start) continue
            val acc = point.acc
            accs.add(point.t to (if (acc != null && acc.isFinite() && acc > 0) acc else null))
            piece.add(point)
        }
    }

    fun push(point: RawPoint) = push(listOf(point))

    /** time of the last settled step and the result revision — live drawing uses the net up to here */
    fun settled(): Pair<Double, Int> = walk.settled().first to walk.revision

    /** this walk's state in the net (live drawing) */
    val walkState: ExploreWalk get() = walk

    fun pause(atMs: Double) {
        val last = pauses.lastOrNull()
        if ((last != null && last.end == null) || !atMs.isFinite()) return
        pauses.add(RunPause(atMs, null))
    }

    /** Resume: the paused gap is not joined (close the current piece, start a new one). */
    fun resume(atMs: Double) {
        val last = pauses.lastOrNull()
        if (last == null || last.end != null || !atMs.isFinite()) return
        last.end = kotlin.math.max(atMs, last.start)
        piece.close()
        piece = newPiece()
    }

    /** Settled so far (the last few tens of seconds held by smoothing/classification are missing). */
    fun summary(): ExploreWalkSummary = finished ?: ExploreWalkSummary.of(walk.result(), classifier.stats)

    /** Settles everything and ends. */
    fun finish(): ExploreWalkSummary {
        finished?.let { return it }
        piece.close()
        classifier.flush()
        val result = walk.end()
        val summary = ExploreWalkSummary.of(result, classifier.stats)
        finished = summary
        return summary
    }

    private fun newPiece(): PathSmoother =
        PathSmoother(config.runFilter, config.maxSpeedMps, doppler) { sample: SmoothedSample ->
            val s = OnFootSample(sample.t, sample.lat, sample.lng, sample.speed, sample.weak, sample.restart, accAt(sample.t))
            onSample?.invoke(s)
            classifier.push(s)
        }

    /** a user pause started between the previous net point and t (gap bridges never cross it) */
    private fun pausedBefore(t: Double): Boolean = pauses.any { it.start >= lastPointT && it.start < t }

    private fun accAt(t: Double): Double? {
        val accs = this.accs
        while (accHead < accs.size && accs[accHead].first < t) accHead += 1
        var acc: Double? = null
        if (accHead < accs.size && accs[accHead].first == t) {
            acc = accs[accHead].second
            accHead += 1
        }
        // trim the consumed front now and then
        if (accHead > 256 && accHead * 2 > accs.size) {
            accs.subList(0, accHead).clear()
            accHead = 0
        }
        return acc
    }
}

/** One finished walk at once (server confirmation, app preview). Pauses in ms epoch (open pause: end null). */
fun computeExploreWalk(
    graph: ExploreGraph,
    startedAt: Double,
    points: List<RawPoint>,
    pauses: List<RunPause> = emptyList(),
    motion: OnFootMotion? = null,
    config: ExplorePipelineConfig = ExplorePipelineConfig.DEFAULT,
    onSample: ((OnFootSample) -> Unit)? = null,
    onPoint: ((ExplorePoint) -> Unit)? = null,
): ExploreWalkSummary {
    val recorder = ExploreRecorder(graph, startedAt, motion, config, onSample, onPoint)
    val sortedPauses = pauses.sortedBy { it.start }
    var next = 0
    var resumeAt: Double? = null
    val sorted = points.sortedBy { it.t }
    for (point in sorted) {
        val pending = resumeAt
        if (pending != null && point.t >= pending) {
            recorder.resume(pending)
            resumeAt = null
        }
        while (resumeAt == null && next < sortedPauses.size && sortedPauses[next].start <= point.t) {
            val pause = sortedPauses[next]
            next += 1
            recorder.pause(pause.start)
            val end = pause.end ?: break
            if (end <= point.t) recorder.resume(end) else resumeAt = end
        }
        recorder.push(point)
    }
    return recorder.finish()
}
