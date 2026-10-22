package com.odomap.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odomap.app.R
import com.odomap.app.engine.LiveDrive
import com.odomap.app.ui.theme.Odo

/**
 * While driving (distraction-optimized): two big numbers, nothing to read twice, nothing to touch.
 * Map, lists and summaries open only when parked.
 */
@Composable
fun DrivingScreen(live: LiveDrive?, onLogoLongPress: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Odo.Paper).safeDrawingPadding().padding(28.dp)) {
        val wide = maxWidth > maxHeight
        val big = if (wide) (maxHeight.value / 4.2f).coerceIn(56f, 120f).sp else (maxWidth.value / 4.6f).coerceIn(56f, 110f).sp
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(onLogoLongPress)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(12.dp).background(Odo.Accent, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.driving_now), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Odo.Ink)
            }
            Spacer(Modifier.weight(1f))
            val distance = km(live?.distanceM ?: 0.0)
            val fresh = km(live?.pioneeredM ?: 0.0)
            if (wide) {
                Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(72.dp)) {
                    BigNumber(stringResource(R.string.live_this_drive), distance, Odo.Ink, big)
                    BigNumber(stringResource(R.string.live_pioneered), fresh, Odo.Accent, big)
                }
            } else {
                Column(Modifier.align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(36.dp)) {
                    BigNumber(stringResource(R.string.live_this_drive), distance, Odo.Ink, big)
                    BigNumber(stringResource(R.string.live_pioneered), fresh, Odo.Accent, big)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.driving_hint), fontSize = 16.sp, color = Odo.InkFaint, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun BigNumber(label: String, value: String, color: Color, size: TextUnit) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, fontSize = 24.sp, color = Odo.InkSoft, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = size, fontWeight = FontWeight.Bold, color = color, lineHeight = size)
            Text(" km", fontSize = 28.sp, color = Odo.InkSoft, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}
