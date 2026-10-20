package com.example.eventmanagementhub.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.eventmanagementhub.ui.navigation.NavigationUiLogic
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
 * Home view: the map opens centred on VR Siddhartha Engineering College
 * (real campus coordinates from [NavigationUiLogic.COLLEGE_LAT]/
 * [NavigationUiLogic.COLLEGE_LNG]) with a labelled college marker. The
 * user's own location, when permission was granted, is a separate green
 * "You are here" marker — never substituted for the college. Outdoor
 * turn-by-turn still hands off to an external maps app via a deep link in
 * the route sheet.
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
    fun recenter(target: GeoPoint?, zoom: Double = NavigationUiLogic.COLLEGE_ZOOM) {
        if (target == null) return
        mapView?.controller?.apply {
            setZoom(zoom)
            setCenter(target)
        }
    }
}

/** Solid circle icon so college vs user markers are visually distinct. */
private fun circleIcon(color: Int, sizePx: Int = 120): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setColor(android.graphics.Color.WHITE)
        style = Paint.Style.STROKE
        strokeWidth = sizePx / 18f
    }
    val r = sizePx / 2.2f
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, r, fill)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, r, stroke)
    return bitmap
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

    val collegePoint = remember {
        GeoPoint(NavigationUiLogic.COLLEGE_LAT, NavigationUiLogic.COLLEGE_LNG)
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.DEFAULT_TILE_SOURCE)
            setMultiTouchControls(true)
            // Home view: the real college coordinates at street-level zoom.
            // `this.controller` (MapView's IMapController) — not the outer
            // OutdoorMapController parameter, which shadows the property.
            this.controller.setZoom(NavigationUiLogic.COLLEGE_ZOOM)
            this.controller.setCenter(collegePoint)
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

    // College landmark marker — always present, distinct colour from the
    // user's green fix so the two are never confused.
    val collegeMarker = remember(mapView, collegePoint) {
        Marker(mapView).apply {
            position = collegePoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = NavigationUiLogic.COLLEGE_NAME
            snippet = NavigationUiLogic.COLLEGE_ADDRESS
            setIcon(
                androidx.core.graphics.drawable.DrawableCompat.wrap(
                    android.graphics.drawable.BitmapDrawable(
                        mapView.resources,
                        circleIcon(Color(0xFF3D2B8C).toArgb())
                    )
                )
            )
        }
    }
    DisposableEffect(collegeMarker, mapView) {
        mapView.overlays.add(collegeMarker)
        mapView.invalidate()
        onDispose { mapView.overlays.remove(collegeMarker) }
    }

    // User single-fix marker. Coordinates come only from DeviceLocation
    // (LocationManager) — never replaced by the college position.
    val fixMarker = remember(myLocation, mapView) {
        myLocation?.let { pos ->
            Marker(mapView).apply {
                position = pos
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "You are here"
                setIcon(
                    androidx.core.graphics.drawable.DrawableCompat.wrap(
                        android.graphics.drawable.BitmapDrawable(
                            mapView.resources,
                            circleIcon(Color(0xFF2E7D32).toArgb())
                        )
                    )
                )
            }
        }
    }
    DisposableEffect(fixMarker, mapView) {
        fixMarker?.let { mapView.overlays.add(it) }
        mapView.invalidate()
        onDispose { fixMarker?.let { mapView.overlays.remove(it) } }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier
    )
}
