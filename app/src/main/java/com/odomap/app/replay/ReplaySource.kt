package com.odomap.app.replay

import android.content.res.AssetManager
import com.odomap.app.location.EventSource
import com.odomap.app.model.SourceEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

data class ReplayState(
    val playing: Boolean = false,
    val demoId: String? = null,
    val title: String? = null,
    /** 1-based position in the queue */
    val index: Int = 0,
    val total: Int = 0,
    /** share of the current drive played (0..1) */
    val progress: Float = 0f,
)

/** Speed multipliers offered by the demo panel. */
val REPLAY_SPEEDS = listOf(1, 10, 30, 60)

/**
 * Plays bundled demo drives (assets/drives/<id>.jsonl) as if they happened now: location fixes keep their original timestamps (so stored
 * drives carry the demo dates) but are emitted at [multiplier]× real time. Gear events (D … P) are emitted in order with the fixes.
 */
class ReplaySource(
    private val assets: AssetManager,
    private val queue: List<String>,
    private val multiplier: StateFlow<Int>,
    private val state: MutableStateFlow<ReplayState>,
    /** wall pause between drives of a queue (the arrival summary shows meanwhile) */
    private val gapMs: Long = 4000,
) : EventSource {
    override val name: String = "replay"

    override fun events(): Flow<SourceEvent> =
        flow {
            try {
                queue.forEachIndexed { qi, id ->
                    val events = withContext(Dispatchers.IO) { load(assets, id) }
                    val timed = events.filter { it.t != Long.MIN_VALUE }
                    val t0 = timed.firstOrNull()?.t ?: 0L
                    val tEnd = timed.lastOrNull()?.t ?: t0
                    var title = id
                    var anchorWall = System.nanoTime() / 1_000_000
                    var anchorT = t0
                    var anchorMult = multiplier.value.coerceAtLeast(1)
                    for (e in events) {
                        val ev = e.event
                        if (ev is SourceEvent.DemoBegin) title = ev.title
                        if (e.t != Long.MIN_VALUE) {
                            val mult = multiplier.value.coerceAtLeast(1)
                            val now = System.nanoTime() / 1_000_000
                            if (mult != anchorMult) {
                                // re-anchor so a speed change applies from here on
                                anchorT = anchorT + (now - anchorWall) * anchorMult
                                anchorWall = now
                                anchorMult = mult
                            }
                            val due = anchorWall + (e.t - anchorT) / mult
                            if (due - now >= 4) delay(due - now)
                        }
                        emit(ev)
                        if (ev is SourceEvent.Loc || ev is SourceEvent.DemoBegin) {
                            val p = if (tEnd > t0 && e.t != Long.MIN_VALUE) ((e.t - t0).toFloat() / (tEnd - t0)) else 0f
                            state.value = ReplayState(true, id, title, qi + 1, queue.size, p)
                        }
                    }
                    if (qi < queue.size - 1) delay(gapMs)
                }
            } finally {
                state.value = ReplayState()
            }
        }

    companion object {
        fun index(assets: AssetManager): List<DemoDrive> =
            assets.open("${DemoDriveFormat.ASSET_DIR}/index.json").bufferedReader().use { DemoDriveFormat.parseIndex(it.readText()) }

        fun load(assets: AssetManager, id: String): List<TimedEvent> =
            InputStreamReader(assets.open("${DemoDriveFormat.ASSET_DIR}/$id.jsonl"), Charsets.UTF_8).use { DemoDriveFormat.parseDrive(it, id) }
    }
}
