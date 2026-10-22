package com.odomap.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.odomap.app.engine.DriveEngine
import com.odomap.app.model.SourceEvent
import com.odomap.app.record.DriveRecorder
import com.odomap.app.replay.DemoDriveFormat
import com.odomap.app.store.GraphStore
import com.odomap.app.tts.Speaker
import com.odomap.app.ui.DemoPanel
import com.odomap.app.ui.DrivesScreen
import com.odomap.app.ui.DrivingScreen
import com.odomap.app.ui.HomeScreen
import com.odomap.app.ui.OdoAppUi
import com.odomap.app.ui.map.LocalCanvasMap
import com.odomap.app.ui.theme.OdoTheme
import com.odomap.app.replay.ReplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Screens rendered on the JVM (Robolectric) with the real recorder fed by the demo replay at high speed. The map is CanvasMap (MapLibre's GL
 * renderer cannot run here). Output: odomap-ui-*.png in -PodomapRenderDir (skipped without it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir: String? = System.getProperty("odomap.render.dir")
    private val spoken = ArrayList<String>()

    private fun shot(name: String) {
        val dir = outDir ?: return
        compose.waitForIdle()
        captureScreenRoboImage("$dir/odomap-ui-$name.png")
    }

    private fun recorder(root: File): DriveRecorder {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return DriveRecorder(
            app, root,
            object : Speaker {
                override fun speak(text: String) {
                    spoken.add(text)
                }

                override fun shutdown() {}
            },
        ).also { it.replaySpeed.value = 1_000_000 }
    }

    private fun waitDrives(r: DriveRecorder, n: Int) {
        val until = System.currentTimeMillis() + 60_000
        while (r.drives.value.size < n || r.replayState.value.playing) {
            check(System.currentTimeMillis() < until) { "replay timeout" }
            Thread.sleep(50)
        }
        compose.waitForIdle()
    }

    private fun run(qualifiers: String, tag: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val root = Files.createTempDirectory("odomap-ui").toFile()
        val r = recorder(root)
        compose.setContent {
            CompositionLocalProvider(LocalCanvasMap provides true) {
                OdoTheme { OdoAppUi(recorder = r, hasLocation = true, onAskPermission = {}) }
            }
        }
        shot("$tag-0-empty")
        r.playDemo(listOf("commute-1-am"))
        waitDrives(r, 1)
        shot("$tag-1-arrival-commute-1-am")
        r.dismissArrival()
        r.playDemo(listOf("commute-2-am"))
        waitDrives(r, 2)
        shot("$tag-2-arrival-commute-2-am")
        r.dismissArrival()
        compose.waitForIdle()
        shot("$tag-3-home-after-2")
        val d1 = r.drives.value.last()
        val d2 = r.drives.value.first()
        println("[ui] $tag drive1 ${d1.title} ${d1.distanceM.toInt()} m / first ${d1.pioneeredM.toInt()} m; drive2 ${d2.title} ${d2.distanceM.toInt()} m / first ${d2.pioneeredM.toInt()} m; tts=$spoken")
        assertEquals(2, r.drives.value.size)
        assertTrue(d1.pioneeredM > 10_000)
        assertTrue(d2.pioneeredM < 1_000)
        assertTrue(r.net.value.geoJson.contains("\"level\":2"))
        assertTrue(spoken.first().startsWith("오늘 처음 달린 길"))
        root.deleteRecursively()
    }

    @Test
    fun landscape() = run("w1280dp-h720dp-land-mdpi", "land")

    @Test
    fun portrait() = run("w540dp-h960dp-port-mdpi", "port")

    /** mid-drive states (live card, driving screen) and the list / detail / demo panel, from a prepared net */
    @Test
    fun liveAndLists() {
        RuntimeEnvironment.setQualifiers("w1280dp-h720dp-land-mdpi")
        val root = Files.createTempDirectory("odomap-ui").toFile()
        val r = recorder(root)
        r.playDemo(listOf("commute-1-am", "commute-2-am", "commute-1-pm"))
        waitDrives(r, 3)
        r.dismissArrival()
        val pm = r.drives.value.first { it.title == "퇴근 10/26" }
        println("[ui] queue: " + r.drives.value.joinToString { "${it.title} ${it.distanceM.toInt()} m / first ${it.pioneeredM.toInt()} m" })
        assertTrue("out-of-order demo drive recorded whole: ${pm.distanceM}", pm.distanceM > 12_000) // commute-1-pm is ~14.3 km (index.json)
        // a drive in progress: half of commute-3-am (partly new roads) on the stored net
        val engine = DriveEngine(GraphStore(root).load())
        val events = File("src/main/assets/drives/commute-3-am.jsonl").reader().use { DemoDriveFormat.parseDrive(it, "commute-3-am") }
        val locs = events.mapNotNull { (it.event as? SourceEvent.Loc)?.fix }
        engine.start("live", locs.first().t - 1000, "출근 10/28", "test")
        val half = locs.take(locs.size * 2 / 3)
        half.forEach { engine.push(it) }
        val live = engine.live(withLines = true)!!
        val net = r.net.value
        var screen by mutableStateOf("home")
        compose.setContent {
            CompositionLocalProvider(LocalCanvasMap provides true) {
                OdoTheme {
                    when (screen) {
                        "home" ->
                            HomeScreen(
                                netGeoJson = net.geoJson, netVersion = net.version, net = net, live = live, puck = half.last(),
                                replay = ReplayState(true, "commute-3-am", "출근 10/28", 1, 1, 0.66f), speed = 60, baseMap = false, refit = 0,
                                hasLocation = true, onAskPermission = {}, onToggleBaseMap = {}, onFit = {}, onDrives = {}, onLogoLongPress = {}, onStopDemo = {},
                            )
                        "driving" -> DrivingScreen(live = live, onLogoLongPress = {})
                        "list" ->
                            DrivesScreen(
                                recorder = r, drives = r.drives.value, net = net.geoJson, baseMap = false,
                                selected = r.drives.value.first { it.title == "퇴근 10/26" }.id, onSelect = {}, onBack = {}, onClose = {},
                            )
                        "demo" -> DemoPanel(recorder = r, replay = ReplayState(), demoOverride = true, onDemoOverride = {}, onClose = {}, onPlayed = {})
                    }
                }
            }
        }
        shot("live-home-land")
        screen = "driving"
        shot("live-driving-land")
        screen = "list"
        Thread.sleep(500)
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        shot("list-detail-land")
        screen = "demo"
        shot("demo-panel-land")
        println("[ui] live ${live.distanceM.toInt()} m, first ${live.pioneeredM.toInt()} m")
        root.deleteRecursively()
    }
}
