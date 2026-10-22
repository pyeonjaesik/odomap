package com.odomap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.odomap.app.R
import com.odomap.app.record.DriveRecorder
import com.odomap.app.replay.REPLAY_SPEEDS
import com.odomap.app.replay.ReplaySource
import com.odomap.app.replay.ReplayState
import com.odomap.app.ui.theme.Odo
import com.odomap.app.ui.theme.UiScaled

/** Developer / demo menu (long-press the logo): demo drives, speed, restriction override, reset. */
@Composable
fun DemoPanel(
    recorder: DriveRecorder,
    replay: ReplayState,
    demoOverride: Boolean,
    onDemoOverride: (Boolean) -> Unit,
    onClose: () -> Unit,
    onPlayed: () -> Unit,
) {
    val context = LocalContext.current
    val demos = remember { runCatching { ReplaySource.index(context.assets) }.getOrDefault(emptyList()) }
    val speed by recorder.replaySpeed.collectAsStateWithLifecycle()
    val source by recorder.sourceName.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) { UiScaled {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp))
                .background(Odo.Card)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.demo_title), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Odo.Ink, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.demo_close), color = Odo.InkSoft, fontSize = 16.sp, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
            }
            Text(stringResource(R.string.demo_source, source), fontSize = 13.sp, color = Odo.InkFaint)
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.demo_speed), fontSize = 14.sp, color = Odo.InkSoft)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in REPLAY_SPEEDS) {
                    PillButton("$s×", onClick = { recorder.replaySpeed.value = s }, filled = s == speed)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.demo_override), fontSize = 15.sp, color = Odo.Ink, modifier = Modifier.weight(1f))
                Switch(
                    checked = demoOverride,
                    onCheckedChange = onDemoOverride,
                    colors = SwitchDefaults.colors(checkedTrackColor = Odo.Ink, checkedThumbColor = Odo.Paper),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(
                    stringResource(R.string.demo_play_all),
                    onClick = {
                        recorder.playDemo(demos.map { it.id })
                        onPlayed()
                    },
                    accent = true,
                )
                if (replay.playing) PillButton(stringResource(R.string.demo_stop), onClick = { recorder.stopDemo() })
                Spacer(Modifier.weight(1f))
                PillButton(stringResource(R.string.demo_reset), onClick = { confirmReset = true })
            }
            if (confirmReset) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Odo.PaperDeep).padding(14.dp)) {
                    Column {
                        Text(stringResource(R.string.demo_reset_confirm), fontSize = 15.sp, color = Odo.Ink)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton(
                                stringResource(R.string.demo_reset_yes),
                                onClick = {
                                    confirmReset = false
                                    recorder.resetAll()
                                },
                                accent = true,
                            )
                            PillButton(stringResource(R.string.demo_cancel), onClick = { confirmReset = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Divider()
            LazyColumn(Modifier.fillMaxWidth()) {
                items(demos, key = { it.id }) { d ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                recorder.playDemo(listOf(d.id))
                                onPlayed()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Odo.Ink)
                            Text(d.id, fontSize = 13.sp, color = Odo.InkFaint)
                        }
                        Text(
                            stringResource(R.string.demo_minutes, km(d.distanceM), Math.round(d.minutes).toInt()),
                            fontSize = 15.sp,
                            color = Odo.InkSoft,
                        )
                        if (replay.playing && replay.demoId == d.id) {
                            Spacer(Modifier.padding(4.dp))
                            Text("▶", color = Odo.Accent, fontSize = 15.sp)
                        }
                    }
                    Divider()
                }
            }
        }
    } }
}
