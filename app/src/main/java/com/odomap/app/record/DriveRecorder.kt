package com.odomap.app.record

import android.content.Context
import android.util.Log
import com.odomap.app.R
import com.odomap.app.drive.LiveEventSource
import com.odomap.app.engine.DriveEngine
import com.odomap.app.engine.EMPTY_FEATURES
import com.odomap.app.engine.FinishedDrive
import com.odomap.app.engine.LiveDrive
import com.odomap.app.engine.formatKm
import com.odomap.app.location.EventSource
import com.odomap.app.model.Bounds
import com.odomap.app.model.DriveRecord
import com.odomap.app.model.DriveStatus
import com.odomap.app.model.Fix
import com.odomap.app.model.SourceEvent
import com.odomap.app.replay.ReplaySource
import com.odomap.app.replay.ReplayState
import com.odomap.app.store.DriveStore
import com.odomap.app.store.GraphStore
import com.odomap.app.tts.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** The cumulative map: drawn net + totals. */
data class NetState(
    val geoJson: String = EMPTY_FEATURES,
    /** bumps on every change (cheap equality for the map) */
    val version: Int = 0,
    val netLengthM: Double = 0.0,
    val bounds: Bounds? = null,
    val driveCount: Int = 0,
    val drivenM: Double = 0.0,
    val pioneeredM: Double = 0.0,
    val loaded: Boolean = false,
)

/** A stored drive ready to draw. */
data class DriveDetail(val record: DriveRecord, val inkGeoJson: String, val newGeoJson: String)

/**
 * App-scoped recorder: consumes one [EventSource] (live car or demo replay), starts a drive when the state says driving (gear D / speed rule),
 * feeds fixes into the [DriveEngine] on the cumulative graph, and on park (P) stores the drive + changed graph tiles, publishes the arrival
 * summary and speaks it. All engine work runs on one thread. The foreground [RecordingService] keeps the process alive for live recording.
 */
class DriveRecorder(
    private val context: Context,
    root: File,
    private val speaker: Speaker,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "odomap-engine") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val graphStore = GraphStore(root)
    private val driveStore = DriveStore(root)
    private lateinit var engine: DriveEngine

    private val _net = MutableStateFlow(NetState())
    val net: StateFlow<NetState> = _net.asStateFlow()
    private val _drives = MutableStateFlow<List<DriveRecord>>(emptyList())
    val drives: StateFlow<List<DriveRecord>> = _drives.asStateFlow()
    private val _live = MutableStateFlow<LiveDrive?>(null)
    val live: StateFlow<LiveDrive?> = _live.asStateFlow()
    private val _puck = MutableStateFlow<Fix?>(null)
    val puck: StateFlow<Fix?> = _puck.asStateFlow()
    private val _status = MutableStateFlow<DriveStatus?>(null)
    val status: StateFlow<DriveStatus?> = _status.asStateFlow()
    private val _arrival = MutableStateFlow<DriveRecord?>(null)
    val arrival: StateFlow<DriveRecord?> = _arrival.asStateFlow()
    private val _sourceName = MutableStateFlow("—")
    val sourceName: StateFlow<String> = _sourceName.asStateFlow()

    val replayState = MutableStateFlow(ReplayState())
    val replaySpeed = MutableStateFlow(60)

    private var liveRequested = false
    private var liveJob: Job? = null
    private var replayJob: Job? = null

    // drive bookkeeping (engine thread)
    private val recent = ArrayDeque<Fix>()
    private var fixLog: DriveStore.FixLog? = null
    private var pendingTitle: String? = null
    private var pendingSource: String? = null
    private var lastNumbersAt = 0L
    private var lastLinesAt = 0L
    private var lastPuckAt = 0L
    private var lastFix: Fix? = null

    init {
        scope.launch {
            engine = DriveEngine(graphStore.load())
            refreshNet()
        }
    }

    // ---- control (any thread) ----

    /** Start listening to the car (called by RecordingService once permissions allow). */
    fun startLive() {
        scope.launch {
            liveRequested = true
            if (replayJob == null) attachLive()
        }
    }

    fun stopLive() {
        scope.launch {
            liveRequested = false
            liveJob?.cancelAndJoin()
            liveJob = null
        }
    }

    /** Play demo drives in order (replaces the live source until done). */
    fun playDemo(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            replayJob?.cancelAndJoin()
            liveJob?.cancelAndJoin()
            liveJob = null
            lastFix?.let { if (engine.isDriving) finishDrive(it.t) }
            val source = ReplaySource(context.assets, ids, replaySpeed, replayState)
            _sourceName.value = source.name
            replayJob =
                scope.launch {
                    try {
                        source.events().collect { handle(it) }
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            if (engine.isDriving) finishDrive(lastFix?.t ?: System.currentTimeMillis())
                            replayJob = null
                            if (liveRequested) attachLive()
                        }
                    }
                }
        }
    }

    fun stopDemo() {
        scope.launch { replayJob?.cancelAndJoin() }
    }

    fun dismissArrival() {
        _arrival.value = null
    }

    /** 전체 삭제: every drive, the whole net. */
    fun resetAll(onDone: () -> Unit = {}) {
        scope.launch {
            replayJob?.cancelAndJoin()
            engine.abandon()
            fixLog?.close()
            fixLog = null
            driveStore.clear()
            graphStore.clear()
            engine = DriveEngine(com.odomap.core.car.CarProfile.newGraph())
            _live.value = null
            _arrival.value = null
            _puck.value = null
            recent.clear()
            lastFix = null
            refreshNet()
            onDone()
        }
    }

    suspend fun detail(id: String): DriveDetail? =
        withContext(dispatcher) {
            val record = _drives.value.firstOrNull { it.id == id } ?: return@withContext null
            val (walk, fresh) = driveStore.edges(id) ?: return@withContext DriveDetail(record, EMPTY_FEATURES, EMPTY_FEATURES)
            val (ink, new) = engine.driveGeoJson(walk, fresh)
            DriveDetail(record, ink, new)
        }

    // ---- engine thread ----

    private fun attachLive() {
        if (liveJob != null) return
        val source: EventSource =
            try {
                LiveEventSource(context)
            } catch (t: Throwable) {
                Log.w(TAG, "live source unavailable", t)
                return
            }
        _sourceName.value = source.name
        liveJob =
            scope.launch {
                try {
                    source.events().collect { handle(it) }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.w(TAG, "live source failed", t)
                }
            }
    }

    private fun handle(e: SourceEvent) {
        when (e) {
            is SourceEvent.DemoBegin -> {
                recent.clear() // a demo drive's clock is unrelated to the previous one's
                pendingTitle = e.title
                pendingSource = "replay:${e.demoId}"
            }
            is SourceEvent.State -> {
                _status.value = e.status
                if (e.status.driving && !engine.isDriving) {
                    startDrive(e.status.t)
                } else if (!e.status.driving && engine.isDriving) {
                    finishDrive(e.status.t)
                }
            }
            is SourceEvent.Loc -> onFix(e.fix)
        }
    }

    private fun onFix(fix: Fix) {
        lastFix = fix
        if (recent.isNotEmpty() && fix.t < recent.last().t) recent.clear() // clock went backwards
        recent.addLast(fix)
        while (recent.isNotEmpty() && fix.t - recent.first().t > RECENT_MS) recent.removeFirst()
        val now = System.nanoTime() / 1_000_000
        if (now - lastPuckAt >= PUCK_MS) {
            lastPuckAt = now
            _puck.value = fix
        }
        if (!engine.isDriving) return
        engine.push(fix)
        fixLog?.append(fix)
        publishLive(now, force = false)
    }

    private fun publishLive(now: Long, force: Boolean) {
        val lines = force || now - lastLinesAt >= LINES_MS
        if (!lines && now - lastNumbersAt < NUMBERS_MS) return
        lastNumbersAt = now
        val prev = _live.value
        val snap = engine.live(withLines = lines) ?: return
        if (lines) {
            lastLinesAt = now
            _live.value = snap
        } else {
            _live.value = snap.copy(inkGeoJson = prev?.inkGeoJson ?: EMPTY_FEATURES, newGeoJson = prev?.newGeoJson ?: EMPTY_FEATURES)
        }
    }

    private fun startDrive(t: Long) {
        val id = "d" + System.currentTimeMillis()
        val source = pendingSource ?: _sourceName.value
        engine.start(id, t, pendingTitle, source)
        pendingTitle = null
        pendingSource = null
        _arrival.value = null
        fixLog = driveStore.openFixLog(id)
        // fixes that arrived just before the state change (speed rule starts in the past; gear and fixes may race)
        for (fix in recent) if (fix.t >= t && fix.t <= t + RECENT_MS) {
            engine.push(fix)
            fixLog?.append(fix)
        }
        publishLive(System.nanoTime() / 1_000_000, force = true)
    }

    private fun finishDrive(t: Long) {
        val id = engine.activeId
        fixLog?.close()
        fixLog = null
        val finished: FinishedDrive? = engine.finish(t)
        _live.value = null
        if (finished == null) {
            id?.let { driveStore.discard(it) }
            graphStore.saveDirty(engine.graph)
            return
        }
        driveStore.save(finished.record, finished.walkEdges, finished.newEdges)
        graphStore.saveDirty(engine.graph)
        refreshNet()
        _arrival.value = finished.record
        speaker.speak(arrivalSpeech(finished.record))
    }

    private fun arrivalSpeech(r: DriveRecord): String =
        if (r.pioneeredM >= 50) context.getString(R.string.tts_arrival, formatKm(r.pioneeredM))
        else context.getString(R.string.tts_arrival_none)

    private fun refreshNet() {
        val drives = driveStore.list()
        _drives.value = drives
        _net.value =
            NetState(
                geoJson = engine.netGeoJson(),
                version = _net.value.version + 1,
                netLengthM = engine.netLengthM(),
                bounds = engine.netBounds(),
                driveCount = drives.size,
                drivenM = drives.sumOf { it.distanceM },
                pioneeredM = drives.sumOf { it.pioneeredM },
                loaded = true,
            )
    }

    companion object {
        private const val TAG = "DriveRecorder"
        private const val RECENT_MS = 60_000L
        private const val PUCK_MS = 100L
        private const val NUMBERS_MS = 250L
        private const val LINES_MS = 1000L
    }
}
