package com.odomap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odomap.app.R
import com.odomap.app.engine.EMPTY_FEATURES
import com.odomap.app.model.DriveRecord
import com.odomap.app.record.DriveDetail
import com.odomap.app.record.DriveRecorder
import com.odomap.app.ui.map.OdoMap
import com.odomap.app.ui.theme.Odo

/** Drive list → detail. Wide (landscape): list and detail side by side; narrow: one at a time. */
@Composable
fun DrivesScreen(
    recorder: DriveRecorder,
    drives: List<DriveRecord>,
    net: String,
    baseMap: Boolean,
    selected: String?,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Odo.Paper)) {
        val wide = maxWidth > maxHeight && maxWidth >= 720.dp
        val listWidth = if (maxWidth >= 1100.dp) 420.dp else 360.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                DriveList(
                    drives, selected, onSelect, onClose,
                    Modifier.width(listWidth).fillMaxHeight(),
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (selected != null) DriveDetailPane(recorder, selected, net, baseMap, null)
                    else Text(stringResource(R.string.drive_pick), color = Odo.InkFaint, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center))
                }
            }
        } else if (selected != null) {
            DriveDetailPane(recorder, selected, net, baseMap, onBack)
        } else {
            DriveList(drives, null, onSelect, onClose, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun DriveList(drives: List<DriveRecord>, selected: String?, onSelect: (String) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Column(modifier.safeDrawingPadding().padding(start = 20.dp, end = 12.dp, top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 26.sp, color = Odo.Ink, modifier = Modifier.clickable(onClick = onClose).padding(end = 14.dp, top = 4.dp, bottom = 4.dp))
            Text(stringResource(R.string.drives_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Odo.Ink)
        }
        Spacer(Modifier.height(10.dp))
        if (drives.isEmpty()) {
            Text(stringResource(R.string.drives_empty), color = Odo.InkFaint, fontSize = 16.sp, modifier = Modifier.padding(top = 24.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(drives, key = { it.id }) { d ->
                val on = d.id == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) Odo.Card else Color.Transparent)
                        .clickable { onSelect(d.id) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title ?: dateTime(d.startedAt), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Odo.Ink)
                        Text(
                            dateTime(d.startedAt) + " · " + stringResource(R.string.minutes_value, minutesOf(d.startedAt, d.endedAt)),
                            fontSize = 14.sp,
                            color = Odo.InkSoft,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(km(d.distanceM) + " km", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Odo.Ink)
                        Text(
                            stringResource(R.string.stat_pioneered) + " " + km(d.pioneeredM) + " km",
                            fontSize = 14.sp,
                            color = if (d.pioneeredM >= 50) Odo.Accent else Odo.InkFaint,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveDetailPane(recorder: DriveRecorder, id: String, net: String, baseMap: Boolean, onBack: (() -> Unit)?) {
    var detail by remember(id) { mutableStateOf<DriveDetail?>(null) }
    LaunchedEffect(id) { detail = recorder.detail(id) }
    val d = detail
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight
        OdoMap(
            modifier = Modifier.fillMaxSize(),
            net = net,
            ink = d?.inkGeoJson ?: EMPTY_FEATURES,
            fresh = d?.newGeoJson ?: EMPTY_FEATURES,
            baseMap = baseMap,
            dimNet = true,
            fit = d?.record?.bounds,
            fitKey = Pair(d?.record?.id, wide),
            padding = if (wide) listOf(330.dp, 40.dp, 40.dp, 40.dp) else listOf(28.dp, 90.dp, 28.dp, 300.dp),
        )
        if (onBack != null) {
            Box(Modifier.safeDrawingPadding().padding(16.dp)) { PillButton("← " + stringResource(R.string.back), onClick = onBack) }
        }
        if (d != null) {
            val r = d.record
            val card = Modifier.safeDrawingPadding().padding(20.dp)
            OdoCard(
                if (wide) card.width(290.dp).align(Alignment.TopStart)
                else card.widthIn(max = 560.dp).fillMaxWidth().align(Alignment.BottomCenter),
            ) {
                Text(r.title ?: dateTime(r.startedAt), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Odo.Ink)
                Text("${dateTime(r.startedAt)} → ${clock(r.endedAt)}", fontSize = 14.sp, color = Odo.InkSoft)
                Spacer(Modifier.height(12.dp))
                val ratio = if (r.distanceM > 0) Math.round(100 * r.pioneeredM / r.distanceM).toInt() else 0
                val rows =
                    listOf(
                        Triple(stringResource(R.string.detail_distance), km(r.distanceM) + " km", Odo.Ink),
                        Triple(stringResource(R.string.detail_pioneered), km(r.pioneeredM) + " km", Odo.Accent),
                        Triple(stringResource(R.string.detail_ratio), stringResource(R.string.percent_value, ratio), Odo.Ink),
                        Triple(stringResource(R.string.detail_time), stringResource(R.string.minutes_value, minutesOf(r.startedAt, r.endedAt)), Odo.Ink),
                        Triple(stringResource(R.string.detail_repass), stringResource(R.string.segments_value, r.repassEdges), Odo.Ink),
                    )
                if (wide) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { (label, value, color) -> DetailRow(label, value, color) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        rows.take(3).forEach { (label, value, color) -> Stat(label, value.substringBefore(" "), value.substringAfter(" ", ""), color, 24.sp) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Legend(Odo.Accent, stringResource(R.string.legend_new))
                    Spacer(Modifier.width(16.dp))
                    Legend(Odo.Ink, stringResource(R.string.legend_known))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = Odo.InkSoft, modifier = Modifier.weight(1f))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.size(6.dp))
        Text(label, fontSize = 13.sp, color = Odo.InkSoft)
    }
}
