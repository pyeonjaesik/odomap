package com.odomap.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.odomap.app.model.Bounds
import com.odomap.app.model.Fix
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * true → [OdoMap] draws with a plain Compose Canvas instead of MapLibre (JVM screenshot tests, where the GL map cannot run).
 * Same layers and colours, flat projection, no tiles.
 */
val LocalCanvasMap = staticCompositionLocalOf { false }

private class Level(val level: Int, val lines: List<List<Pair<Double, Double>>>)

private fun parse(geo: String): List<Level> {
    if (geo.length < 60) return emptyList()
    val fs = JSONObject(geo).optJSONArray("features") ?: return emptyList()
    return List(fs.length()) { i ->
        val f = fs.getJSONObject(i)
        val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
        Level(
            f.getJSONObject("properties").optInt("level", 1),
            List(coords.length()) { j ->
                val line: JSONArray = coords.getJSONArray(j)
                List(line.length()) { k -> line.getJSONArray(k).getDouble(1) to line.getJSONArray(k).getDouble(0) }
            },
        )
    }
}

@Composable
internal fun CanvasMap(
    modifier: Modifier,
    net: String,
    ink: String,
    fresh: String,
    puck: Fix?,
    dimNet: Boolean,
    fit: Bounds?,
    follow: Boolean,
    padding: List<Dp>,
) {
    val netL = remember(net) { parse(net) }
    val inkL = remember(ink) { parse(ink) }
    val freshL = remember(fresh) { parse(fresh) }
    val pad = with(LocalDensity.current) { padding.map { it.toPx() } }
    val density = LocalDensity.current.density
    Canvas(modifier) {
        drawRect(Color(0xFFF4EFE5))
        val all = netL + inkL + freshL
        var b = fit
        if (b == null) for (l in all) for (line in l.lines) for ((lat, lng) in line) b = (b ?: Bounds.of(lat, lng)).extend(lat, lng)
        val bounds = b ?: Bounds(37.5, 126.9, 37.6, 127.1)
        val kx = cos(Math.toRadians((bounds.minLat + bounds.maxLat) / 2))
        val availW = size.width - pad[0] - pad[2]
        val availH = size.height - pad[1] - pad[3]
        var scale = min(availW / max(1e-6, (bounds.maxLng - bounds.minLng) * kx), availH / max(1e-6, bounds.maxLat - bounds.minLat))
        var cLat = (bounds.minLat + bounds.maxLat) / 2
        var cLng = (bounds.minLng + bounds.maxLng) / 2
        if (follow && puck != null) {
            scale = 12_000.0 * density // ~zoom 14
            cLat = puck.lat
            cLng = puck.lng
        }
        val cx = pad[0] + availW / 2
        val cy = pad[1] + availH / 2
        fun pt(lat: Double, lng: Double) = Offset((cx + (lng - cLng) * kx * scale).toFloat(), (cy - (lat - cLat) * scale).toFloat())
        // graticule every 0.01°
        val grid = Color(0xFFEAE3D5)
        val step = 0.01
        val west = cLng - cx / (kx * scale)
        val north = cLat + cy / scale
        var lng = Math.floor(west / step) * step
        while (pt(cLat, lng).x < size.width) {
            val x = pt(cLat, lng).x
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
            lng += step
        }
        var lat = Math.ceil(north / step) * step
        while (pt(lat, cLng).y < size.height) {
            val y = pt(lat, cLng).y
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            lat -= step
        }
        fun draw(levels: List<Level>, color: Color, extra: Float, alphaOf: (Int) -> Float) {
            for (l in levels.sortedBy { it.level }) {
                val path = Path()
                for (line in l.lines) {
                    line.forEachIndexed { i, (la, ln) ->
                        val p = pt(la, ln)
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                }
                val w = (0.7f + extra + 0.9f * l.level) * density
                drawPath(path, color.copy(alpha = alphaOf(l.level)), style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val inkColor = Color(0xFF22303C)
        draw(netL, inkColor, 0f) { lv -> (0.5f + (lv - 1) * 0.11f) * (if (dimNet) 0.35f else 1f) }
        draw(inkL, Color(0xFFF4EFE5), 4f) { 1f }
        draw(freshL, Color(0xFFF4EFE5), 5f) { 1f }
        draw(inkL, Color(0xFF101C27), 0.6f) { 1f }
        draw(freshL, Color(0xFFE2552D), 1.4f) { 1f }
        if (puck != null) {
            val p = pt(puck.lat, puck.lng)
            drawCircle(Color(0x2EE2552D), 20f * density, p)
            drawCircle(Color.White, 9f * density, p)
            drawCircle(Color(0xFF101C27), 9f * density, p, style = Stroke(3f * density))
            drawCircle(Color(0xFFE2552D), 4.5f * density, p)
        }
    }
}
