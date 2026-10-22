package com.odomap.app.ui.map

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.odomap.app.engine.EMPTY_FEATURES
import com.odomap.app.engine.pointGeoJson
import com.odomap.app.model.Bounds
import com.odomap.app.model.Fix
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource

/** Seoul — where the camera starts with an empty map */
private val HOME = LatLng(37.55, 126.99)

/**
 * MapLibre map with the Odomap layers. Data in as GeoJSON strings (built off the main thread by the engine).
 * @param fit bounds to show; re-applied whenever [fitKey] changes
 * @param follow keep the camera on [puck]
 * @param padding extra space (e.g. under an overlay card) on [left, top, right, bottom]
 */
@Composable
fun OdoMap(
    modifier: Modifier,
    net: String,
    ink: String = EMPTY_FEATURES,
    fresh: String = EMPTY_FEATURES,
    puck: Fix? = null,
    baseMap: Boolean = false,
    dimNet: Boolean = false,
    fit: Bounds? = null,
    fitKey: Any? = null,
    follow: Boolean = false,
    padding: List<Dp> = listOf(24.dp, 24.dp, 24.dp, 24.dp),
) {
    if (LocalCanvasMap.current) {
        CanvasMap(modifier, net, ink, fresh, puck, dimNet, fit, follow, padding)
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val mapView =
        remember(density.density) {
            val options =
                MapLibreMapOptions.createFromAttributes(context)
                    // render the map at the UI's scaled density (lines, puck and the OpenFreeMap labels grow with the cards)
                    .pixelRatio(density.density)
                    .textureMode(true)
                    .logoEnabled(false)
                    .compassEnabled(false)
                    .attributionEnabled(true)
                    .attributionGravity(Gravity.BOTTOM or Gravity.END)
                    .camera(CameraPosition.Builder().target(HOME).zoom(11.0).build())
            MapView(context, options)
        }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var style by remember(mapView) { mutableStateOf<Style?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        var created = false
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_CREATE -> {
                        mapView.onCreate(null)
                        created = true
                    }
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { m ->
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            map = m
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (created) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(map, baseMap, dimNet) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(OdoStyle.builder(baseMap)) { s ->
            OdoStyle.addLayers(s, baseMap, dimNet)
            style = s
        }
        m.uiSettings.isAttributionEnabled = baseMap
    }
    LaunchedEffect(style, net) { style?.getSourceAs<GeoJsonSource>(OdoStyle.SRC_NET)?.setGeoJson(net) }
    LaunchedEffect(style, ink) { style?.getSourceAs<GeoJsonSource>(OdoStyle.SRC_INK)?.setGeoJson(ink) }
    LaunchedEffect(style, fresh) { style?.getSourceAs<GeoJsonSource>(OdoStyle.SRC_NEW)?.setGeoJson(fresh) }
    LaunchedEffect(style, puck) {
        val src = style?.getSourceAs<GeoJsonSource>(OdoStyle.SRC_PUCK) ?: return@LaunchedEffect
        src.setGeoJson(if (puck == null) EMPTY_FEATURES else pointGeoJson(puck.lat, puck.lng, puck.bearing))
    }
    val pad = with(density) { padding.map { it.roundToPx() } }
    LaunchedEffect(map, fitKey, follow) {
        val m = map ?: return@LaunchedEffect
        if (follow) return@LaunchedEffect
        val b = fit ?: return@LaunchedEffect
        while (mapView.width == 0 || mapView.height == 0) kotlinx.coroutines.delay(50)
        val bounds =
            if (b.maxLat - b.minLat < 0.002 && b.maxLng - b.minLng < 0.002) {
                LatLngBounds.from(b.maxLat + 0.003, b.maxLng + 0.003, b.minLat - 0.003, b.minLng - 0.003)
            } else {
                LatLngBounds.from(b.maxLat, b.maxLng, b.minLat, b.minLng)
            }
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad[0], pad[1], pad[2], pad[3]))
    }
    LaunchedEffect(map, puck, follow) {
        val m = map ?: return@LaunchedEffect
        val p = puck ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val zoom = if (m.cameraPosition.zoom < 13.0) 14.0 else m.cameraPosition.zoom
        m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(p.lat, p.lng)).zoom(zoom).padding(pad[0].toDouble(), pad[1].toDouble(), pad[2].toDouble(), pad[3].toDouble()).build()))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
