package com.odomap.core.car

import com.odomap.core.explore.ExploreGraph
import com.odomap.core.explore.ExplorePipelineConfig
import com.odomap.core.explore.ExploreRules
import com.odomap.core.explore.OnFootRules
import com.odomap.core.run.RunFilter

/**
 * Rule set for driving. The app builds its graph with [newGraph] and feeds drives with [pipeline].
 * Tuning lives here; the phone defaults stay untouched in [ExploreRules.DEFAULT] / [ExplorePipelineConfig.DEFAULT].
 */
object CarProfile {
    /**
     * Net rules for roads (tuned on tools/routes drives: 1 Hz, AR(1) GPS noise 3–4.5 m → ~3 m after smoothing, ~4.5 m between two drives).
     * Same road → one line: the other carriageway of a divided road (10–30 m) is ridden as the known line; a distinct road ≥ 40 m beside stays apart.
     */
    val graphRules: ExploreRules =
        ExploreRules.DEFAULT.copy(
            family = "c",
            version = 1,
            // line shape is GPS-limited (mean 2.8 m off the road for any step 8–30 m); 20 m ≈ one fix at 72 km/h, 2.5× fewer nodes than 8 m
            stepM = 20.0,
            maxEdgeM = 30.0,
            // snap 18 m (acc ≤ 9 m) … 32 m (acc 16 m): joins at junctions and a 10–18 m carriageway, while a road 40 m away is ~5σ off
            snapMinM = 18.0,
            snapMaxM = 32.0,
            snapAccuracyFactor = 2.0,
            // riding a known line: band 18 + 15 = 33 m holds the other carriageway (≤ 30 m); still under 40 m
            driftBandM = 15.0,
            ownBandM = 20.0,
            // a new road coming in parallel: 18 + 4 = 22 m (unchanged margin)
            freeBandM = 4.0,
            // hold / exit lengths scaled to the 20 m step (8 / 6 steps): leaving a wide carriageway needs 120 m out of the band in a row
            driftHoldM = 160.0,
            exitConfirmM = 120.0,
            ownHoldM = 100.0,
            // routing to the next snap target: steps are 2.5× longer
            routeSlackM = 40.0,
            routeMaxM = 120.0,
            // no step counter in a car; a re-pass counts after 40 m (stop-and-go jitter never counts)
            repassM = 40.0,
            repassPacedM = 40.0,
            // tunnels / dropped fixes: straight line up to 3 km and 5 min, ≤ 45 m/s (162 km/h) — faster is a teleport, not joined
            bridgeMaxM = 3000.0,
            bridgeMaxS = 300.0,
            bridgeMaxSpeedMps = 45.0,
            // a down-weighted (weak) fix inside a new road does not cut the line (phone: it breaks — indoor drift)
            weakSkipMaxM = 300.0,
        )

    /** keep every moving stretch — a car is the vehicle */
    val pipeline: ExplorePipelineConfig =
        ExplorePipelineConfig.DEFAULT.copy(
            onFootFilter = false,
            // car acceleration noise: constant 1 m²/s³ instead of 0.1 + 0.15·v² (135 at 30 m/s — the walking model stops smoothing at speed);
            // cross-track error vs the true road 3.41 → 3.23 m (urban), 2.59 → 2.44 m (highway), no fix rejected
            runFilter = RunFilter.DEFAULT.copy(processNoise = 1.0, processNoiseSpeedK = 0.0),
            // nothing is judged (onFootFilter = false): a short window only for the stats, live lag 30 s → 5 s
            onFootRules = OnFootRules.DEFAULT.copy(windowS = 5.0, holdS = 0.0),
        )

    /** stored-net rebuild key (net rules + pipeline tuning) */
    val algorithm: String = "${graphRules.label}.p1"

    fun newGraph(nextId: Int = 1): ExploreGraph = ExploreGraph(nextId, graphRules)
}
