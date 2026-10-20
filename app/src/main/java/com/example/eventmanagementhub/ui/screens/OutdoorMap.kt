package com.example.eventmanagementhub.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Outdoor layer of the navigation screen, backed by OpenStreetMap raster
 * tiles via osmdroid (Apache-2.0). No Google Maps API key, Google Cloud
 * project, billing account or Play Services is required.
 *
 * Tile source: osmdroid's default OSM "Mapnik" tiles. MapView draws the
 * built-in "© OpenStreetMap contributors" attribution overlay. Tiles are
 * fetched on demand with an app User-Agent (OSM tile-usage policy) and
 * cached on disk by osmdroid — never bulk-downloaded.
 *
 * Coordinates: the backend holds no outdoor (lat/lng) coordinates for venues,
 * so none are invented here. The map starts on the user's real location when
 * permission was granted; outdoor turn-by-turn still hands off to an external
 * maps app via a deep link in the route sheet.
 */

/** Bridges MapScreen's zoom/recenter buttons to the osmdroid [MapView]. */
class OutdoorMapController {
    private var mapView: MapView? = null

    internal fun attach(map: MapView) {
        mapView = map
    }

    internal fun detach() {
        mapView = null
    }

    fun zoomIn() {
        mapView?.controller?.zoomIn()
    }

    fun zoomOut() {
        mapView?.controller?.zoomOut()
    }

    /** Centers on [target] when one exists; never invents a position. */
    fun recenter(target: GeoPoint?, zoom: Double = 16.0) {
        if (target == null) return
        mapView?.controller?.apply {
            setZoom(zoom)
            setCenter(target)
        }
    }
}

@Composable
fun OutdoorMap(
    myLocation: GeoPoint?,
    modifier: Modifier = Modifier,
    controller: OutdoorMapController = remember { OutdoorMapController() }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // osmdroid configuration (safe to re-apply). The User-Agent identifies
    // this app to the OpenStreetMap tile servers, as their usage policy
    // requires; the tile cache lives in app-specific storage (no permissions).
    remember {
        Configuration.getInstance().apply {
            userAgentValue = "EventManagementHub/1.0 (${context.packageName})"
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
            setMultiTouchControls(true)
            // World view until a real location arrives — no fabricated centre.
            // `this.controller` (MapView's IMapController) — not the outer
            // OutdoorMapController parameter, which shadows the property.
            this.controller.setZoom(2.5)
        }
    }

    // osmdroid MapView must follow the screen lifecycle and detach when this
    // composable leaves composition (mode switch, navigation away).
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Parent zoom/recenter buttons drive the same MapView.
    DisposableEffect(controller, mapView) {
        controller.attach(mapView)
        onDispose { controller.detach() }
    }

    // Single-fix "You are here" marker. Coordinates come only from
    // DeviceLocation (LocationManager) — nothing is fabricated.
    val fixMarker = remember(myLocation, mapView) {
        myLocation?.let { pos ->
            Marker(mapView).apply {
                position = pos
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "You are here"
            }
        }
    }
    DisposableEffect(fixMarker, mapView) {
        fixMarker?.let { mapView.overlays.add(it) }
        mapView.invalidate()
        onDispose { fixMarker?.let { mapView.overlays.remove(it) } }
    }

    LaunchedEffect(myLocation, mapView) {
        if (myLocation != null) {
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(myLocation)
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}
