package com.example.eventmanagementhub.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.ui.navigation.GeoMarker
import com.example.eventmanagementhub.ui.navigation.OutdoorGeoLogic
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
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

/** Ring icon (transparent centre) for venues whose position is derived/approximate. */
private fun hollowIcon(color: Int, sizePx: Int = 84): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = sizePx / 7f
    }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2.6f, ring)
    return bitmap
}

@Composable
fun OutdoorMap(
    myLocation: GeoPoint?,
    campusMap: CampusMapResponse? = null,
    onRouteHere: (String) -> Unit = {},
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

    // Server venues + walking edges + faint bounds. Rebuilt whenever the map
    // is refetched (campusMap changes on `map:updated`).
    var selected by remember { mutableStateOf<GeoMarker?>(null) }
    val geoMarkers = remember(campusMap) { campusMap?.let { OutdoorGeoLogic.markers(it) } ?: emptyList() }
    val geoLines = remember(campusMap) { campusMap?.let { OutdoorGeoLogic.edgeLines(it) } ?: emptyList() }
    val geoBounds = remember(campusMap) { OutdoorGeoLogic.drawableBounds(campusMap?.bounds) }
    DisposableEffect(mapView, geoMarkers, geoLines, geoBounds) {
        val added = ArrayList<Overlay>()
        geoBounds?.let { b ->
            added += Polygon(mapView).apply {
                points = listOf(
                    GeoPoint(b.north, b.west), GeoPoint(b.north, b.east),
                    GeoPoint(b.south, b.east), GeoPoint(b.south, b.west)
                )
                fillPaint.color = Color(0xFF3D2B8C).copy(alpha = 0.04f).toArgb()
                outlinePaint.color = Color(0xFF3D2B8C).copy(alpha = 0.25f).toArgb()
                outlinePaint.strokeWidth = 2f
            }
        }
        geoLines.forEach { l ->
            added += Polyline(mapView).apply {
                setPoints(listOf(GeoPoint(l.fromLat, l.fromLng), GeoPoint(l.toLat, l.toLng)))
                outlinePaint.color = Color(0xFF3D2B8C).copy(alpha = 0.55f).toArgb()
                outlinePaint.strokeWidth = 3f
            }
        }
        val solid = android.graphics.drawable.BitmapDrawable(
            mapView.resources, circleIcon(Color(0xFFE65100).toArgb(), 72)
        )
        val hollow = android.graphics.drawable.BitmapDrawable(
            mapView.resources, hollowIcon(Color(0xFFE65100).toArgb())
        )
        geoMarkers.forEach { g ->
            added += Marker(mapView).apply {
                position = GeoPoint(g.lat, g.lng)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = g.name
                setIcon(if (g.placed) solid else hollow)
                setOnMarkerClickListener { _, _ -> selected = g; true }
            }
        }
        // Insert beneath the college / my-location markers.
        mapView.overlays.addAll(0, added)
        mapView.invalidate()
        onDispose {
            mapView.overlays.removeAll(added.toSet())
            mapView.invalidate()
        }
    }
    // Refresh/drop the selection when the map refetches.
    LaunchedEffect(geoMarkers) {
        selected = selected?.let { sel -> geoMarkers.firstOrNull { it.id == sel.id } }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        selected?.let { g ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .testTag("outdoor-venue-card"),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(g.name, fontWeight = FontWeight.SemiBold, color = Color(0xFF1B1B3A))
                    if (g.kind.isNotBlank()) Text(g.kind, color = Color(0xFF6B6B80))
                    if (g.description.isNotBlank()) Text(g.description, color = Color(0xFF6B6B80))
                    if (!g.placed) Text("Approximate position", color = Color(0xFF9A9AAE))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRouteHere(g.id); selected = null }) { Text("Route here") }
                        TextButton(onClick = { selected = null }) { Text("Close") }
                    }
                }
            }
        }
    }
}
