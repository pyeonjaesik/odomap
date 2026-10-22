package com.odomap.app.ui.map

import android.graphics.Color
import com.odomap.app.engine.EMPTY_FEATURES
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.exponential
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.product
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.sum
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The map look: an offline "paper" style (background + faint graticule — no network, no tiles) with the road net drawn in ink, thickness by
 * level 1..5; optionally the OpenFreeMap "liberty" vector map underneath, washed out with a paper veil.
 */
object OdoStyle {
    const val PAPER = "#F4EFE5"
    const val INK = "#22303C"
    const val INK_STRONG = "#101C27"
    const val ACCENT = "#E2552D"
    private const val GRID_MINOR = "#EAE3D5"
    private const val GRID_MAJOR = "#DDD3C1"
    const val OPENFREEMAP_LIBERTY = "https://tiles.openfreemap.org/styles/liberty"

    const val SRC_NET = "odo-net"
    const val SRC_INK = "odo-drive-ink"
    const val SRC_NEW = "odo-drive-new"
    const val SRC_PUCK = "odo-puck"

    private val PAPER_STYLE =
        """{"version":8,"name":"odomap-paper","sources":{},"layers":[{"id":"paper","type":"background","paint":{"background-color":"$PAPER"}}]}"""

    fun builder(baseMap: Boolean): Style.Builder = if (baseMap) Style.Builder().fromUri(OPENFREEMAP_LIBERTY) else Style.Builder().fromJson(PAPER_STYLE)

    /** Our sources and layers on top of whatever style is loaded. [dimNet] = the net is context only (drive detail). */
    fun addLayers(style: Style, baseMap: Boolean, dimNet: Boolean) {
        if (baseMap) {
            style.addSource(GeoJsonSource("odo-veil", WORLD))
            style.addLayer(FillLayer("odo-veil", "odo-veil").withProperties(fillColor(PAPER), fillOpacity(0.62f)))
        } else {
            style.addSource(GeoJsonSource("odo-grid-minor", grid(0.01)))
            style.addSource(GeoJsonSource("odo-grid-major", grid(0.1)))
            style.addLayer(
                LineLayer("odo-grid-minor", "odo-grid-minor")
                    .withProperties(lineColor(GRID_MINOR), lineWidth(interpolate(linear(), zoom(), stop(11, 0.3f), stop(15, 0.9f))))
                    .apply { minZoom = 11f },
            )
            style.addLayer(LineLayer("odo-grid-major", "odo-grid-major").withProperties(lineColor(GRID_MAJOR), lineWidth(0.8f)))
        }
        val lineOpts = GeoJsonOptions().withTolerance(0.2f)
        style.addSource(GeoJsonSource(SRC_NET, EMPTY_FEATURES, lineOpts))
        style.addSource(GeoJsonSource(SRC_INK, EMPTY_FEATURES, lineOpts))
        style.addSource(GeoJsonSource(SRC_NEW, EMPTY_FEATURES, lineOpts))
        style.addSource(GeoJsonSource(SRC_PUCK, EMPTY_FEATURES))

        style.addLayer(
            LineLayer("odo-net", SRC_NET).withProperties(
                lineColor(INK),
                lineWidth(width(0f)),
                lineOpacity(if (dimNet) product(literal(0.35f), levelOpacity()) else levelOpacity()),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        // the current / shown drive: a paper halo, then ink (known roads, today's thickness) and accent (first-time roads)
        style.addLayer(
            LineLayer("odo-drive-halo-ink", SRC_INK).withProperties(lineColor(PAPER), lineWidth(width(4f)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)),
        )
        style.addLayer(
            LineLayer("odo-drive-halo-new", SRC_NEW).withProperties(lineColor(PAPER), lineWidth(width(5f)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)),
        )
        style.addLayer(
            LineLayer("odo-drive-ink", SRC_INK).withProperties(lineColor(INK_STRONG), lineWidth(width(0.6f)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)),
        )
        style.addLayer(
            LineLayer("odo-drive-new", SRC_NEW).withProperties(lineColor(ACCENT), lineWidth(width(1.4f)), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)),
        )
        style.addLayer(CircleLayer("odo-puck-halo", SRC_PUCK).withProperties(circleColor(ACCENT), circleOpacity(0.18f), circleRadius(20f)))
        style.addLayer(
            CircleLayer("odo-puck", SRC_PUCK).withProperties(
                circleColor(Color.WHITE),
                circleRadius(9f),
                circleStrokeColor(INK_STRONG),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(CircleLayer("odo-puck-dot", SRC_PUCK).withProperties(circleColor(ACCENT), circleRadius(4.5f)))
    }

    /** line width (px) by thickness level 1..5 and zoom; [extra] widens drive / halo layers */
    private fun width(extra: Float): Expression =
        interpolate(
            exponential(1.5f), zoom(),
            stop(9, sum(literal(0.4f + extra * 0.4f), product(literal(0.3f), get("level")))),
            stop(13, sum(literal(0.7f + extra), product(literal(0.9f), get("level")))),
            stop(17, sum(literal(1.4f + extra * 1.6f), product(literal(2.6f), get("level")))),
        )

    private fun levelOpacity(): Expression = interpolate(linear(), get("level"), stop(1, 0.5f), stop(3, 0.78f), stop(5, 0.95f))

    /** graticule over Korea (lat 33..39, lng 124..132) every [step] degrees */
    private fun grid(step: Double): String {
        val sb = StringBuilder("""{"type":"Feature","properties":{},"geometry":{"type":"MultiLineString","coordinates":[""")
        var first = true
        fun line(a: Double, b: Double, c: Double, d: Double) {
            if (!first) sb.append(',')
            first = false
            sb.append("[[").append(a).append(',').append(b).append("],[").append(c).append(',').append(d).append("]]")
        }
        val n = Math.round(1 / step).toInt()
        for (i in 33 * n..39 * n) line(124.0, i.toDouble() / n, 132.0, i.toDouble() / n)
        for (i in 124 * n..132 * n) line(i.toDouble() / n, 33.0, i.toDouble() / n, 39.0)
        sb.append("]}}")
        return sb.toString()
    }

    private const val WORLD =
        """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[[-180,-85],[180,-85],[180,85],[-180,85],[-180,-85]]]}}"""
}
