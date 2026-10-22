package com.odomap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odomap.app.R
import com.odomap.app.engine.LiveDrive
import com.odomap.app.model.DriveRecord
import com.odomap.app.record.NetState
import com.odomap.app.replay.ReplayState
import com.odomap.app.ui.theme.Odo

@Composable
fun OdoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .shadow(6.dp, RoundedCornerShape(18.dp), clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
            .clip(RoundedCornerShape(18.dp))
            .background(Odo.Card)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        content = content,
    )
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, filled: Boolean = false, accent: Boolean = false) {
    val bg = when {
        accent -> Odo.Accent
        filled -> Odo.Ink
        else -> Odo.Card
    }
    val fg = if (filled || accent) Odo.Paper else Odo.Ink
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (filled || accent) bg else Odo.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Stat(label: String, value: String, unit: String, color: Color = Odo.Ink, size: TextUnit = 30.sp, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 14.sp, color = Odo.InkSoft)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = size, fontWeight = FontWeight.Bold, color = color)
            Text(" $unit", fontSize = 15.sp, color = Odo.InkSoft, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
fun StatsCard(net: NetState) {
    OdoCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(stringResource(R.string.stat_driven), km(net.drivenM), stringResource(R.string.unit_km))
            Stat(stringResource(R.string.stat_pioneered), km(net.pioneeredM), stringResource(R.string.unit_km), color = Odo.Accent)
            Stat(stringResource(R.string.stat_drives), net.driveCount.toString(), stringResource(R.string.unit_times))
        }
    }
}

@Composable
fun LiveCard(live: LiveDrive) {
    OdoCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Odo.Accent, CircleShape))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.live_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Odo.Accent)
            Spacer(Modifier.weight(1f))
            live.title?.let { Text(it, fontSize = 14.sp, color = Odo.InkSoft) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat(stringResource(R.string.live_this_drive), km(live.distanceM), stringResource(R.string.unit_km), size = 36.sp)
            Stat(stringResource(R.string.live_pioneered), km(live.pioneeredM), stringResource(R.string.unit_km), color = Odo.Accent, size = 36.sp)
        }
    }
}

@Composable
fun DemoChip(replay: ReplayState, speed: Int, onStop: () -> Unit) {
    OdoCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.demo_playing, replay.index, replay.total, replay.title ?: "", speed),
                fontSize = 14.sp,
                color = Odo.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.demo_stop),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Odo.Accent,
                modifier = Modifier.clickable(onClick = onStop).padding(6.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Odo.PaperDeep)) {
            Box(Modifier.fillMaxWidth(replay.progress.coerceIn(0f, 1f)).height(4.dp).background(Odo.Ink))
        }
    }
}

@Composable
fun PermissionCard(onAsk: () -> Unit) {
    OdoCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.permission_needed), fontSize = 15.sp, color = Odo.Ink)
        Spacer(Modifier.height(10.dp))
        PillButton(stringResource(R.string.permission_grant), onClick = onAsk, filled = true)
    }
}

@Composable
fun HintCard(text: String) {
    OdoCard(Modifier.fillMaxWidth()) { Text(text, fontSize = 15.sp, color = Odo.InkSoft) }
}

/** Shown on P: "오늘 42km 중 처음 달린 길 12.4km". */
@Composable
fun ArrivalSheet(record: DriveRecord, onDismiss: () -> Unit, onView: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x5522303C))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        OdoCard(
            Modifier
                .safeDrawingPadding()
                .padding(20.dp)
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        ) {
            Text(stringResource(R.string.arrival_title) + " · " + (record.title ?: dateTime(record.endedAt)), fontSize = 15.sp, color = Odo.InkSoft)
            Spacer(Modifier.height(10.dp))
            val total = km(record.distanceM)
            val fresh = km(record.pioneeredM)
            if (record.pioneeredM >= 50) {
                val line = stringResource(R.string.arrival_line, total, fresh)
                val at = line.lastIndexOf(fresh)
                Text(
                    buildAnnotatedString {
                        append(line.substring(0, at))
                        withStyle(SpanStyle(color = Odo.Accent, fontWeight = FontWeight.Bold)) { append(line.substring(at)) }
                    },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Odo.Ink,
                    lineHeight = 36.sp,
                )
            } else {
                Text(stringResource(R.string.arrival_none, total), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Odo.Ink)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${clock(record.startedAt)} → ${clock(record.endedAt)} · " +
                    stringResource(R.string.minutes_value, minutesOf(record.startedAt, record.endedAt)),
                fontSize = 15.sp,
                color = Odo.InkSoft,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.arrival_view), onClick = onView, filled = true)
                PillButton(stringResource(R.string.arrival_ok), onClick = onDismiss)
            }
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier, thickness: Dp = 1.dp) {
    Box(modifier.fillMaxWidth().height(thickness).background(Odo.Line))
}
