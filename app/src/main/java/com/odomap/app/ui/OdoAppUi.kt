package com.odomap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odomap.app.R
import com.odomap.app.engine.EMPTY_FEATURES
import com.odomap.app.record.DriveRecorder
import com.odomap.app.ui.map.OdoMap
import com.odomap.app.ui.theme.Odo
import com.odomap.app.ux.UxRestrictions

/** Root: home map / drive list / detail, the driving-restricted screen, arrival sheet and demo panel. */
@Composable
fun OdoAppUi(recorder: DriveRecorder, hasLocation: Boolean, onAskPermission: () -> Unit) {
    val context = LocalContext.current
    val net by recorder.net.collectAsStateWithLifecycle()
    val live by recorder.live.collectAsStateWithLifecycle()
    val puck by recorder.puck.collectAsStateWithLifecycle()
    val status by recorder.status.collectAsStateWithLifecycle()
    val arrival by recorder.arrival.collectAsStateWithLifecycle()
    val replay by recorder.replayState.collectAsStateWithLifecycle()
    val drives by recorder.drives.collectAsStateWithLifecycle()
    val speed by recorder.replaySpeed.collectAsStateWithLifecycle()
    val uxFlow = remember { UxRestrictions.flow(context) }
    val uxRestricted by uxFlow.collectAsStateWithLifecycle(initialValue = null)

    var showDrives by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showDemo by rememberSaveable { mutableStateOf(false) }
    var baseMap by rememberSaveable { mutableStateOf(false) }
    var demoOverride by rememberSaveable { mutableStateOf(true) }
    var refit by remember { mutableIntStateOf(0) }

    val driving = status?.driving == true || live != null
    val restricted = (uxRestricted ?: driving) && !(demoOverride && replay.playing)

    BackHandler(enabled = showDrives && !restricted) {
        if (selected != null) selected = null else showDrives = false
    }

    Box(Modifier.fillMaxSize().background(Odo.Paper)) {
        when {
            restricted -> DrivingScreen(live = live, onLogoLongPress = { showDemo = true })
            showDrives ->
                DrivesScreen(
                    recorder = recorder,
                    drives = drives,
                    net = net.geoJson,
                    baseMap = baseMap,
                    selected = selected,
                    onSelect = { selected = it },
                    onBack = { if (selected != null) selected = null else showDrives = false },
                    onClose = {
                        selected = null
                        showDrives = false
                    },
                )
            else ->
                HomeScreen(
                    netGeoJson = net.geoJson,
                    netVersion = net.version,
                    net = net,
                    live = live,
                    puck = puck,
                    replay = replay,
                    speed = speed,
                    baseMap = baseMap,
                    refit = refit,
                    hasLocation = hasLocation,
                    onAskPermission = onAskPermission,
                    onToggleBaseMap = { baseMap = !baseMap },
                    onFit = { refit++ },
                    onDrives = { showDrives = true },
                    onLogoLongPress = { showDemo = true },
                    onStopDemo = { recorder.stopDemo() },
                )
        }

        val a = arrival
        if (a != null && !restricted) {
            ArrivalSheet(
                record = a,
                onDismiss = { recorder.dismissArrival() },
                onView = {
                    recorder.dismissArrival()
                    selected = a.id
                    showDrives = true
                },
            )
        }
        if (showDemo) {
            DemoPanel(
                recorder = recorder,
                replay = replay,
                demoOverride = demoOverride,
                onDemoOverride = { demoOverride = it },
                onClose = { showDemo = false },
                onPlayed = {
                    showDemo = false
                    showDrives = false
                    selected = null
                },
            )
        }
    }
}

@Composable
internal fun HomeScreen(
    netGeoJson: String,
    netVersion: Int,
    net: com.odomap.app.record.NetState,
    live: com.odomap.app.engine.LiveDrive?,
    puck: com.odomap.app.model.Fix?,
    replay: com.odomap.app.replay.ReplayState,
    speed: Int,
    baseMap: Boolean,
    refit: Int,
    hasLocation: Boolean,
    onAskPermission: () -> Unit,
    onToggleBaseMap: () -> Unit,
    onFit: () -> Unit,
    onDrives: () -> Unit,
    onLogoLongPress: () -> Unit,
    onStopDemo: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 640.dp
        val panelWidth = if (maxWidth >= 1000.dp) 360.dp else 320.dp
        val following = live != null
        OdoMap(
            modifier = Modifier.fillMaxSize(),
            net = netGeoJson,
            ink = live?.inkGeoJson?.ifEmpty { null } ?: EMPTY_FEATURES,
            fresh = live?.newGeoJson?.ifEmpty { null } ?: EMPTY_FEATURES,
            puck = if (following || replay.playing) puck else null,
            baseMap = baseMap,
            fit = net.bounds,
            fitKey = Triple(netVersion, refit, maxWidth > maxHeight),
            follow = following,
            padding =
                if (wide) listOf(panelWidth + 40.dp, 40.dp, 40.dp, 40.dp)
                else listOf(28.dp, 110.dp, 28.dp, if (live != null) 300.dp else 220.dp),
        )
        val cards: @Composable () -> Unit = {
            if (live != null) LiveCard(live) else StatsCard(net)
            if (replay.playing) DemoChip(replay, speed, onStopDemo)
            if (!hasLocation && !replay.playing) PermissionCard(onAskPermission)
            if (net.loaded && net.driveCount == 0 && live == null && !replay.playing) HintCard(stringResource(R.string.empty_map))
        }
        if (wide) {
            Column(
                Modifier.safeDrawingPadding().padding(20.dp).width(panelWidth),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Logo(onLogoLongPress)
                cards()
            }
            Column(
                Modifier.safeDrawingPadding().padding(20.dp).align(Alignment.TopEnd),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                PillButton(stringResource(R.string.btn_drives), onClick = onDrives, filled = true)
                PillButton(stringResource(if (baseMap) R.string.btn_basemap_off else R.string.btn_basemap_on), onClick = onToggleBaseMap)
                PillButton(stringResource(R.string.btn_fit), onClick = onFit)
            }
        } else {
            Row(
                Modifier.safeDrawingPadding().padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo(onLogoLongPress)
                Spacer(Modifier.weight(1f))
                PillButton(stringResource(R.string.btn_drives), onClick = onDrives, filled = true)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(stringResource(if (baseMap) R.string.btn_basemap_off else R.string.btn_basemap_on), onClick = onToggleBaseMap)
                    PillButton(stringResource(R.string.btn_fit), onClick = onFit)
                }
                cards()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Logo(onLongPress: () -> Unit) {
    Row(
        Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp).background(Odo.Accent, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(stringResource(R.string.app_name_ko), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Odo.Ink)
            Text(stringResource(R.string.tagline), fontSize = 13.sp, color = Odo.InkSoft)
        }
    }
}
