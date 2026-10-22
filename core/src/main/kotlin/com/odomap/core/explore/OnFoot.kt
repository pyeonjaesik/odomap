package com.odomap.core.explore

import kotlin.math.max

// Port of clientApp src/services/run/explore/onFoot.ts (rules v3). Pure computation.
//
// Decides per smoothed sample whether it was walked/ran (on foot) or not (vehicle / standing still), from:
//  - speed: median smoothed speed in ±windowS around the sample (≥ vehicleSpeedMps → vehicle),
//  - steps (if counted): moving faster than moveSpeedMps with cadence < stepMinCadence → vehicle/bike; cadence < stillMaxCadence and not really
//    moving → still,
//  - phone activity recognition (if any): vehicle/bike with confidence ≥ 1 → vehicle; still → still when not really moving.
// A changed decision must hold for holdS before it is taken, so decisions are emitted windowS + holdS late; flush() settles the rest.
//
// CAR-TUNING HOOK: [OnFootRules] is an injectable data class, and [OnFootClassifier] takes `judge = false` (ExplorePipelineConfig.onFootFilter)
// to let every sample through as "counted" — for a driving app the on-foot filter is the opposite of what we want; a car rule set replaces it later.

/** On-foot rules (onFoot.ts ON_FOOT_RULES — values unchanged). Bump [version] when changing. */
data class OnFootRules(
    val version: Int = 3,
    /** decision window: this many seconds before and after the sample */
    val windowS: Double = 15.0,
    /** a changed decision must hold this long (s) */
    val holdS: Double = 15.0,
    /** median speed at or above this → vehicle (m/s, 25 km/h) */
    val vehicleSpeedMps: Double = 7.0,
    /** with steps: moving at least this fast (m/s) … */
    val moveSpeedMps: Double = 1.5,
    /** … with fewer steps per minute than this → vehicle/bike */
    val stepMinCadence: Double = 30.0,
    /** with steps: fewer steps/min than this while slower than moveSpeedMps → still (v3) */
    val stillMaxCadence: Double = 12.0,
    /** activity recognition is used up to this age (s) */
    val activityMaxAgeS: Double = 1800.0,
    /** walk / run split (m/s) — stats */
    val runSpeedMps: Double = 2.2,
    /** median speed below this → standing, not added to walk/run time/distance */
    val stillSpeedMps: Double = 0.5,
    /** break the window after a gap longer than this (s) */
    val gapS: Double = 60.0,
    /** time stats: count at most this much (s) per sample gap */
    val maxStepS: Double = 10.0,
) {
    companion object {
        val DEFAULT = OnFootRules()
    }
}

/** A smoothed sample (the fields of runDistance SmoothedSample the classifier needs). */
data class OnFootSample(
    val t: Double,
    val lat: Double,
    val lng: Double,
    /** smoothed speed (m/s) */
    val speed: Double,
    val weak: Boolean,
    val restart: Boolean,
    /** raw-fix position error (m, null if unknown) — not used for the decision, passed on to the net */
    val acc: Double? = null,
)

enum class OnFootKind(val json: String) { WALK("walk"), RUN("run"), VEHICLE("vehicle"), STILL("still") }

data class OnFootDecision(
    val t: Double,
    val lat: Double,
    val lng: Double,
    val speed: Double,
    val weak: Boolean,
    val restart: Boolean,
    val acc: Double?,
    val onFoot: Boolean,
    val kind: OnFootKind,
    /** walking confirmed by steps (cadence ≥ stillMaxCadence), only when on foot */
    val paced: Boolean,
)

/** activity kinds (iOS CMMotionActivity / Android Activity Recognition in one set) */
enum class MotionActivity(val json: String) {
    STILL("still"), WALK("walk"), RUN("run"), BIKE("bike"), VEHICLE("vehicle"), UNKNOWN("unknown");

    companion object {
        fun of(value: String?): MotionActivity = entries.firstOrNull { it.json == value } ?: UNKNOWN
    }
}

/** cumulative step count at a time */
data class StepCount(val t: Double, val count: Double)

/** activity fix: confidence 0 low · 1 medium · 2 high */
data class ActivityFix(val t: Double, val kind: MotionActivity, val confidence: Double)

/**
 * Steps / activity recorded with the walk, in time order. The lists are read at decision time, so a list that grows while recording
 * (pass a MutableList) behaves like the TS array that grows.
 */
data class OnFootMotion(val steps: List<StepCount>? = null, val activity: List<ActivityFix>? = null)

data class OnFootStats(
    val walkS: Double = 0.0,
    val runS: Double = 0.0,
    val vehicleS: Double = 0.0,
    val walkM: Double = 0.0,
    val runM: Double = 0.0,
    val vehicleM: Double = 0.0,
)

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size shr 1
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** cumulative steps at `at` (linear between entries, null before the first, last value after the last) */
private fun stepsAt(steps: List<StepCount>, at: Double): Double? {
    if (steps.isEmpty() || at < steps[0].t) return null
    var lo = 0
    var hi = steps.size - 1
    if (at >= steps[hi].t) return steps[hi].count
    while (hi - lo > 1) {
        val mid = (lo + hi) shr 1
        if (steps[mid].t <= at) lo = mid else hi = mid
    }
    val t0 = steps[lo].t
    val n0 = steps[lo].count
    val t1 = steps[hi].t
    val n1 = steps[hi].count
    return if (t1 == t0) n1 else n0 + ((n1 - n0) * (at - t0)) / (t1 - t0)
}

/** activity valid at `at` (last one within activityMaxAgeS) */
private fun activityAt(activity: List<ActivityFix>, at: Double, rules: OnFootRules): ActivityFix? {
    var found: ActivityFix? = null
    var lo = 0
    var hi = activity.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) shr 1
        if (activity[mid].t <= at) {
            found = activity[mid]
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    if (found == null || (at - found.t) / 1000 > rules.activityMaxAgeS) return null
    return found
}

private enum class Judged { FOOT, VEHICLE, STILL }

private class Pending(
    val sample: OnFootSample,
    var raw: Boolean?,
    var speedMedian: Double,
    var reason: Judged,
    var paced: Boolean,
) {
    val t: Double get() = sample.t
}

/**
 * Takes samples, decides on foot or not and emits decisions (onDecision) — late, because of the window.
 * @param judge false = car hook: every sample is judged "on foot" (counted), only the walk/run stats split remains.
 */
class OnFootClassifier(
    private val motion: OnFootMotion,
    private val rules: OnFootRules = OnFootRules.DEFAULT,
    private val judge: Boolean = true,
    private val onDecision: (OnFootDecision) -> Unit,
) {
    /** current run (samples without a break) */
    private var run = ArrayList<Pending>()
    private var rawUpTo = 0
    private var finalUpTo = 0
    private var state: Boolean? = null
    private var lastT = Double.NEGATIVE_INFINITY
    private var prevEmitted: OnFootDecision? = null
    private var walkS = 0.0
    private var runS = 0.0
    private var vehicleS = 0.0
    private var walkM = 0.0
    private var runM = 0.0
    private var vehicleM = 0.0

    /** stats so far (a snapshot) */
    val stats: OnFootStats get() = OnFootStats(walkS, runS, vehicleS, walkM, runM, vehicleM)

    fun push(sample: OnFootSample) {
        if (!sample.t.isFinite() || sample.t <= lastT) return
        val last = run.lastOrNull()
        val broken = sample.restart || (last != null && (sample.t - last.t) / 1000 > rules.gapS)
        if (broken && run.isNotEmpty()) flushRun()
        lastT = sample.t
        run.add(Pending(sample, null, 0.0, Judged.FOOT, false))
        advance(false)
    }

    /** Settles all remaining samples (when the walk ends). */
    fun flush() {
        flushRun()
    }

    private fun flushRun() {
        advance(true)
        run = ArrayList()
        rawUpTo = 0
        finalUpTo = 0
        state = null
    }

    private fun advance(final: Boolean) {
        val run = this.run
        if (run.isEmpty()) return
        val newest = run[run.size - 1].t
        val windowMs = rules.windowS * 1000
        val holdMs = rules.holdS * 1000
        // 1) decisions of samples whose window is full (or at the end)
        while (rawUpTo < run.size && (final || run[rawUpTo].t + windowMs <= newest)) {
            decideRaw(rawUpTo)
            rawUpTo += 1
        }
        // 2) a changed decision is taken only if it holds for holdS
        while (finalUpTo < rawUpTo) {
            val at = run[finalUpTo]
            val raw = at.raw!!
            val ready = final || run[rawUpTo - 1].t > at.t + holdMs
            val state = this.state
            if (state == null) {
                // start of a run: majority within holdS
                if (!ready) break
                var yes = 0
                var total = 0
                var i = finalUpTo
                while (i < rawUpTo && run[i].t <= at.t + holdMs) {
                    total += 1
                    if (run[i].raw == true) yes += 1
                    i += 1
                }
                this.state = yes * 2 >= total
            } else if (raw != state) {
                // to change, every decision from at.t for holdS must agree
                if (!ready) break
                var same = true
                var i = finalUpTo
                while (i < rawUpTo && run[i].t <= at.t + holdMs) {
                    if (run[i].raw != raw) {
                        same = false
                        break
                    }
                    i += 1
                }
                if (same) this.state = raw
            }
            emit(at, this.state!!)
            finalUpTo += 1
        }
    }

    private fun decideRaw(index: Int) {
        val run = this.run
        val sample = run[index]
        val windowMs = rules.windowS * 1000
        val speeds = ArrayList<Double>()
        var first = index
        while (first > 0 && run[first - 1].t >= sample.t - windowMs) first -= 1
        var last = index
        while (last + 1 < run.size && run[last + 1].t <= sample.t + windowMs) last += 1
        for (i in first..last) speeds.add(max(0.0, run[i].sample.speed))
        val speedMedian = median(speeds)
        sample.speedMedian = speedMedian
        // how fast the window really moved (first → last sample) — jittering fixes circle in place even when instant speed jumps (v3)
        val spanS = (run[last].t - run[first].t) / 1000
        val netSpeed = if (spanS > 0) exploreDistanceM(run[first].sample.lat, run[first].sample.lng, run[last].sample.lat, run[last].sample.lng) / spanS else 0.0
        sample.reason = judge(sample.sample, speedMedian, netSpeed, run[first].t, run[last].t)
        sample.raw = sample.reason == Judged.FOOT
        val cadence = if (sample.raw == true) cadenceOf(run[first].t, run[last].t) else null
        sample.paced = cadence != null && cadence >= rules.stillMaxCadence
    }

    /** steps per minute in fromT..toT; null without steps or when the counter went down */
    private fun cadenceOf(fromT: Double, toT: Double): Double? {
        val steps = motion.steps
        if (steps == null || steps.isEmpty() || !(toT > fromT)) return null
        val from = stepsAt(steps, fromT)
        val to = stepsAt(steps, toT)
        if (from == null || to == null || to < from) return null
        return ((to - from) / (toT - fromT)) * 60000
    }

    private fun judge(sample: OnFootSample, speedMedian: Double, netSpeed: Double, fromT: Double, toT: Double): Judged {
        if (!judge) return Judged.FOOT
        if (speedMedian >= rules.vehicleSpeedMps) return Judged.VEHICLE
        val activity = motion.activity
        var activityNow: ActivityFix? = null
        if (activity != null && activity.isNotEmpty()) {
            activityNow = activityAt(activity, sample.t, rules)
            if (activityNow != null && activityNow.confidence >= 1) {
                if (activityNow.kind == MotionActivity.VEHICLE || activityNow.kind == MotionActivity.BIKE) return Judged.VEHICLE
            }
        }
        val cadence = cadenceOf(fromT, toT)
        if (cadence != null) {
            if (netSpeed < rules.moveSpeedMps && cadence < rules.stillMaxCadence) return Judged.STILL
            if (speedMedian >= rules.moveSpeedMps && netSpeed >= rules.moveSpeedMps && cadence < rules.stepMinCadence) return Judged.VEHICLE
        }
        if (activityNow != null && activityNow.confidence >= 1 && activityNow.kind == MotionActivity.STILL && netSpeed < rules.moveSpeedMps) return Judged.STILL
        return Judged.FOOT
    }

    private fun emit(pending: Pending, onFoot: Boolean) {
        val sample = pending.sample
        val offKind =
            when {
                pending.reason == Judged.STILL -> OnFootKind.STILL
                pending.reason == Judged.VEHICLE -> OnFootKind.VEHICLE
                prevEmitted?.kind == OnFootKind.STILL -> OnFootKind.STILL
                else -> OnFootKind.VEHICLE
            }
        val kind = if (!onFoot) offKind else if (pending.speedMedian >= rules.runSpeedMps) OnFootKind.RUN else OnFootKind.WALK
        val decision = OnFootDecision(sample.t, sample.lat, sample.lng, sample.speed, sample.weak, sample.restart, sample.acc, onFoot, kind, onFoot && pending.paced)
        val previous = prevEmitted
        val still = onFoot && pending.speedMedian < rules.stillSpeedMps
        if (previous != null && !still && !sample.restart && sample.t > previous.t && (sample.t - previous.t) / 1000 <= rules.gapS) {
            val seconds = kotlin.math.min((sample.t - previous.t) / 1000, rules.maxStepS)
            val meters = exploreDistanceM(previous.lat, previous.lng, sample.lat, sample.lng)
            when (kind) {
                OnFootKind.WALK -> {
                    walkS += seconds
                    walkM += meters
                }
                OnFootKind.RUN -> {
                    runS += seconds
                    runM += meters
                }
                OnFootKind.VEHICLE -> {
                    vehicleS += seconds
                    vehicleM += meters
                }
                OnFootKind.STILL -> {}
            }
        }
        prevEmitted = decision
        onDecision(decision)
    }
}
