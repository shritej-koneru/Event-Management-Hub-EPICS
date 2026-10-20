package com.example.eventmanagementhub.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.PathResponse
import com.example.eventmanagementhub.data.model.Venue
import com.example.eventmanagementhub.ui.components.CustomSearchBar
import com.example.eventmanagementhub.ui.navigation.DeviceLocation
import com.example.eventmanagementhub.ui.navigation.NavigationUiLogic
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/** Start node used until the user picks a different origin. */
private const val DEFAULT_START = "entrance"

/** Indoor = backend venue graph on the Compose canvas. Outdoor = OSM tiles. */
private enum class MapMode { Indoor, Outdoor }

// Venue positions come from GET /api/navigation/map (backend is the source of
// truth); the layout is no longer hard-coded here.

/**
 * Google Maps-style navigation screen. Indoor routes are computed by the
 * backend's venue graph (POST /api/navigation/path) and drawn on the campus
 * canvas; outdoor mode shows OpenStreetMap tiles via osmdroid (no API key)
 * and hands real-world turn-by-turn off to an external maps app via a deep
 * link.
 */
@Composable
fun MapScreen(
    initialTo: String? = null,
    onTargetConsumed: () -> Unit = {},
    repository: HubRepository = HubRepository()
) {
    val context = LocalContext.current

    // ---- backend data -----------------------------------------------------
    var venues by remember { mutableStateOf<List<Venue>>(emptyList()) }
    var campusMap by remember { mutableStateOf<CampusMapResponse?>(null) }
    var from by remember { mutableStateOf(DEFAULT_START) }
    var to by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<PathResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isStale by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    // ---- ui state ---------------------------------------------------------
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(MapMode.Indoor) }
    var navActive by remember { mutableStateOf(false) }
    var navFinished by remember { mutableStateOf(false) }
    var stepIndex by remember { mutableStateOf(0) }
    var mapScale by remember { mutableStateOf(1f) }
    var mapOffset by remember { mutableStateOf(Offset.Zero) }

    // ---- location (privacy: requested only for outdoor / locate) ----------
    var myLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var locationDenied by remember { mutableStateOf(false) }
    val deviceLocation = remember { DeviceLocation(context) }
    val outdoorController = remember { OutdoorMapController() }
    // Real campus landmark (VR Siddhartha) — outdoor map home centre + a
    // searchable destination that needs no backend venue id.
    val collegePoint = remember {
        GeoPoint(NavigationUiLogic.COLLEGE_LAT, NavigationUiLogic.COLLEGE_LNG)
    }
    var collegeSelected by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            locationDenied = false
            deviceLocation.requestSingle { loc ->
                myLocation = loc?.let { GeoPoint(it.latitude, it.longitude) }
            }
        } else {
            locationDenied = true
        }
    }

    fun ensureMyLocation() {
        if (DeviceLocation.hasPermission(context)) {
            deviceLocation.requestSingle { loc ->
                myLocation = loc?.let { GeoPoint(it.latitude, it.longitude) }
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Stop location updates whenever the screen goes away.
    DisposableEffect(Unit) { onDispose { deviceLocation.stop() } }

    val scope = rememberCoroutineScope()

    fun runPath(f: String, t: String) {
        scope.launch {
            loading = true
            error = null
            when (val res = repository.path(f, t)) {
                is Result.Success -> {
                    path = res.data
                    isStale = res.isStale
                }
                is Result.Error -> {
                    path = null
                    error = res.message
                }
            }
            loading = false
        }
    }

    // Load the campus layout once — positions, edges and the venue list all
    // come from GET /api/navigation/map (falls back to the on-device copy).
    LaunchedEffect(Unit) {
        when (val res = repository.campusMap()) {
            is Result.Success -> {
                campusMap = res.data
                venues = res.data.venues.map { Venue(it.id, it.name, it.neighbors) }
                isStale = res.isStale
                loading = false
            }
            is Result.Error -> {
                error = res.message
                loading = false
            }
        }
    }

    /**
     * Re-targets whenever Event Details asks for a specific venue. Keyed on both
     * `venues` (may arrive after the request) and `initialTo` so the route is
     * computed for the venue actually requested, then the request is cleared.
     */
    LaunchedEffect(venues, initialTo) {
        val requested = initialTo?.takeIf { id -> venues.any { it.id == id } }
        if (requested != null) {
            to = requested
            navActive = false
            navFinished = false
            stepIndex = 0
            runPath(from, requested)
            onTargetConsumed()
        }
    }

    val searchResults = remember(venues, query) {
        NavigationUiLogic.searchVenues(venues, query)
    }
    val collegeMatch = remember(query) { NavigationUiLogic.collegeMatches(query) }
    val destName: String = to?.let { id -> venues.firstOrNull { it.id == id }?.name }
        ?: path?.nodes?.lastOrNull()?.name
        ?: ""

    fun selectDestination(id: String) {
        to = id
        collegeSelected = false
        query = ""
        navActive = false
        navFinished = false
        stepIndex = 0
        runPath(from, id)
    }

    fun selectCollegeDestination() {
        query = ""
        to = null
        path = null
        error = null
        collegeSelected = true
        navActive = false
        navFinished = false
        stepIndex = 0
        mode = MapMode.Outdoor
        outdoorController.recenter(collegePoint, NavigationUiLogic.COLLEGE_ZOOM)
    }

    fun clearDestination() {
        to = null
        collegeSelected = false
        path = null
        error = null
        navActive = false
        navFinished = false
        stepIndex = 0
    }

    fun setFrom(id: String) {
        from = id
        navActive = false
        navFinished = false
        stepIndex = 0
        to?.let { runPath(from, it) }
    }

    fun setMode(next: MapMode) {
        if (mode == next) return
        mode = next
        navActive = false
        if (next == MapMode.Outdoor) ensureMyLocation()
    }

    fun advanceStep() {
        val next = NavigationUiLogic.nextStepIndex(stepIndex, path?.steps?.size ?: 0)
        if (next == null) navFinished = true else stepIndex = next
    }

    fun openInGoogleMaps(name: String) {
        val url = NavigationUiLogic.outdoorDirectionsUrl(name)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            error = "Could not open Google Maps on this device"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BodyBackground)
    ) {
        // ---- search -------------------------------------------------------
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CustomSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search destinations",
                modifier = Modifier.testTag("map-search"),
                onSearch = { }
            )
            if (query.isNotBlank() && (searchResults.isNotEmpty() || collegeMatch)) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 56.dp)
                        .testTag("map-search-results"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    border = BorderStroke(1.dp, CardBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(EventCardWhiteGradient)
                            .padding(vertical = 4.dp)
                    ) {
                        if (collegeMatch) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selectCollegeDestination() }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                    .testTag("map-search-college"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    contentDescription = null,
                                    tint = NavyText
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(
                                        NavigationUiLogic.COLLEGE_NAME,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = NavyText,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        NavigationUiLogic.COLLEGE_ADDRESS,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MutedText
                                    )
                                }
                            }
                        }
                        searchResults.take(4).forEach { v ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { selectDestination(v.id) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    contentDescription = null,
                                    tint = PrimaryPurple
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    v.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = NavyText
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- map area ------------------------------------------------------
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            when (mode) {
                MapMode.Indoor -> CampusMap(
                    map = campusMap ?: CampusMapResponse(),
                    pathIds = path?.nodes?.map { it.id } ?: emptyList(),
                    startId = from,
                    endId = to,
                    activeNodeId = if (navActive && !navFinished) {
                        path?.steps?.getOrNull(stepIndex)?.node
                    } else null,
                    scale = mapScale,
                    offset = mapOffset,
                    onTransform = { zoom, pan ->
                        mapScale = (mapScale * zoom).coerceIn(0.8f, 5f)
                        mapOffset += pan
                    },
                    modifier = Modifier.fillMaxSize()
                )

                MapMode.Outdoor -> OutdoorMap(
                    myLocation = myLocation,
                    controller = outdoorController,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (loading && campusMap == null) {
                CircularProgressIndicator(
                    color = PrimaryPurple,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .testTag("map-loading")
                )
            }

            // zoom + locate controls (Maps-style, right edge)
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MapCircleButton(Icons.Default.Add, "Zoom in", "map-zoom-in") {
                    if (mode == MapMode.Outdoor) outdoorController.zoomIn()
                    else mapScale = (mapScale * 1.25f).coerceAtMost(5f)
                }
                MapCircleButton(Icons.Default.Remove, "Zoom out", "map-zoom-out") {
                    if (mode == MapMode.Outdoor) outdoorController.zoomOut()
                    else mapScale = (mapScale / 1.25f).coerceAtLeast(0.8f)
                }
                MapCircleButton(Icons.Default.MyLocation, "My location", "map-recenter") {
                    if (mode == MapMode.Outdoor) {
                        ensureMyLocation()
                        // With a fix already: pan to the user. Without one:
                        // stay/recentre on the college (async fix only updates
                        // the green marker — it never replaces the college).
                        if (myLocation != null) {
                            outdoorController.recenter(myLocation, 16.0)
                        } else {
                            outdoorController.recenter(
                                collegePoint,
                                NavigationUiLogic.COLLEGE_ZOOM
                            )
                        }
                    } else {
                        mapScale = 1f
                        mapOffset = Offset.Zero
                    }
                }
            }

            // travel-mode chips (walking is the only mode the graph supports)
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeChip(
                    label = "Indoor",
                    selected = mode == MapMode.Indoor,
                    tag = "mode-indoor"
                ) { setMode(MapMode.Indoor) }
                ModeChip(
                    label = "Outdoor",
                    selected = mode == MapMode.Outdoor,
                    tag = "mode-outdoor"
                ) { setMode(MapMode.Outdoor) }
            }
        }

        // ---- bottom sheet --------------------------------------------------
        Surface(
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = Color.White,
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("route-sheet")
        ) {
            Column(Modifier.padding(16.dp)) {
                if (isStale) {
                    Text(
                        "Offline — showing the map and route stored on this device",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB26A00)
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (locationDenied && mode == MapMode.Outdoor) {
                    Text(
                        "Location access denied — the blue dot needs the location permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                }

                FromRow(venues, from, onSelect = ::setFrom)
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(Modifier.height(12.dp))

                val p = path
                when {
                    navActive && p != null -> NavigationPanel(
                        pathData = p,
                        stepIndex = stepIndex,
                        finished = navFinished,
                        destName = destName,
                        onNext = ::advanceStep,
                        onEnd = {
                            navActive = false
                            navFinished = false
                            stepIndex = 0
                        }
                    )

                    p != null && to != null && mode == MapMode.Indoor -> RoutePanel(
                        pathData = p,
                        destName = destName,
                        onStart = {
                            navActive = true
                            navFinished = false
                            stepIndex = 0
                        },
                        onClear = ::clearDestination
                    )

                    to != null && mode == MapMode.Outdoor -> OutdoorRoutePanel(
                        destName = destName,
                        onOpenMaps = { openInGoogleMaps(destName) },
                        onClear = ::clearDestination
                    )

                    collegeSelected && mode == MapMode.Outdoor -> OutdoorRoutePanel(
                        destName = NavigationUiLogic.COLLEGE_NAME,
                        onOpenMaps = { openInGoogleMaps(NavigationUiLogic.COLLEGE_NAME) },
                        onClear = ::clearDestination
                    )

                    else -> {
                        Text(
                            "Search for a destination to see the walking route",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedText,
                            modifier = Modifier.testTag("route-sheet-hint")
                        )
                    }
                }

                if (error != null && path == null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("route-error")
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Map canvas (indoor)
// ---------------------------------------------------------------------------

/**
 * Backend-driven campus canvas: fractional venue positions and undirected
 * edges from GET /api/navigation/map, with the computed route highlighted.
 * Pinch/drag to pan-zoom; the start, destination and active navigation node
 * are labelled.
 */
@Composable
private fun CampusMap(
    map: CampusMapResponse,
    pathIds: List<String>,
    startId: String?,
    endId: String?,
    activeNodeId: String?,
    scale: Float,
    offset: Offset,
    onTransform: (Float, Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val edgeColor = CardBorder
    val pathColor = PrimaryPurple
    val pathSet = remember(pathIds) { pathIds.toSet() }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = NavyText
    )

    // Fractional positions from the backend map (fallback: canvas centre).
    val positions = remember(map.venues) {
        map.venues.associate { it.id to Offset(it.x.toFloat(), it.y.toFloat()) }
    }

    // Undirected edges, one segment each (server stores each edge once).
    val edges = remember(map.edges) { map.edges.map { it.from to it.to } }

    // Ids to label on the map, in draw order.
    val labels = remember(startId, endId, activeNodeId, map.venues) {
        listOfNotNull(startId, endId, activeNodeId)
            .distinct()
            .mapNotNull { id -> map.venues.firstOrNull { it.id == id }?.let { it.id to it.name } }
    }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(EventCardWhiteGradient)
            .pointerInput(map.venues) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) {
        val w = size.width
        val h = size.height

        fun pos(id: String): Offset {
            val frac = positions[id] ?: Offset(0.5f, 0.5f)
            return Offset(frac.x * w, frac.y * h)
        }

        // edges
        for ((a, b) in edges) {
            val p1 = pos(a); val p2 = pos(b)
            val onPath = pathSet.isNotEmpty() && pathSet.contains(a) && pathSet.contains(b)
            drawLine(
                color = if (onPath) pathColor else edgeColor,
                start = p1,
                end = p2,
                strokeWidth = if (onPath) 8f else 3f
            )
        }

        // nodes
        for (v in map.venues) {
            val p = pos(v.id)
            val isOnPath = pathSet.isNotEmpty() && pathSet.contains(v.id)
            drawCircle(color = Color.White, radius = 14f, center = p)
            drawCircle(
                color = if (isOnPath) pathColor else Color(0xFF9C93C2),
                radius = 10f,
                center = p
            )
        }

        // start / destination / active-step labels with a white pill behind
        for ((id, name) in labels) {
            val p = pos(id)
            val layout = textMeasurer.measure(name, style = labelStyle)
            val pillPad = 8f
            val pillW = layout.size.width + pillPad * 2
            val pillH = layout.size.height + pillPad * 2
            val pillTopLeft = Offset(p.x - pillW / 2f, p.y - 20f - pillH)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.92f),
                topLeft = pillTopLeft,
                size = androidx.compose.ui.geometry.Size(pillW, pillH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
            )
            drawText(
                textMeasurer = textMeasurer,
                text = name,
                style = labelStyle,
                topLeft = Offset(pillTopLeft.x + pillPad, pillTopLeft.y + pillPad)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom-sheet panels
// ---------------------------------------------------------------------------

/** Origin selector shown at the top of the sheet. */
@Composable
private fun FromRow(
    venues: List<Venue>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val name = venues.firstOrNull { it.id == selected }?.name ?: selected
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("From:", style = MaterialTheme.typography.bodySmall, color = MutedText)
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CardBorder),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.testTag("from-selector")
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = NavyText,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            venues.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.name, maxLines = 1) },
                    onClick = { expanded = false; onSelect(v.id) }
                )
            }
        }
    }
}

/** Compact route summary: real backend distance, estimated walking time. */
@Composable
private fun RoutePanel(
    pathData: PathResponse,
    destName: String,
    onStart: () -> Unit,
    onClear: () -> Unit
) {
    Text(
        "Walk to $destName",
        style = MaterialTheme.typography.titleMedium,
        color = PrimaryPurple,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("route-title")
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${pathData.totalDistanceM} m",
            style = MaterialTheme.typography.bodyLarge,
            color = NavyText,
            fontWeight = FontWeight.Bold
        )
        Text(
            "  ·  ${NavigationUiLogic.walkingEtaLabel(pathData.totalDistanceM)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedText
        )
        Spacer(Modifier.width(8.dp))
        WalkingChip()
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
            modifier = Modifier.testTag("nav-start")
        ) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Start Navigation")
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onClear, modifier = Modifier.testTag("nav-clear")) {
            Icon(Icons.Default.Close, contentDescription = "Clear destination", tint = MutedText)
        }
    }
    Spacer(Modifier.height(8.dp))
    pathData.steps.take(2).forEachIndexed { index, step ->
        Text(
            "${index + 1}. ${step.instruction}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF3A3850),
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }
    if (pathData.steps.size > 2) {
        Text(
            "+${pathData.steps.size - 2} more steps",
            style = MaterialTheme.typography.bodySmall,
            color = MutedText
        )
    }
}

/**
 * Live navigation panel: shows the current backend step; the user advances
 * manually ("Next") — the app has no indoor positioning, so it does not fake
 * automatic step tracking.
 */
@Composable
private fun NavigationPanel(
    pathData: PathResponse,
    stepIndex: Int,
    finished: Boolean,
    destName: String,
    onNext: () -> Unit,
    onEnd: () -> Unit
) {
    if (finished) {
        Icon(
            Icons.Default.Place,
            contentDescription = null,
            tint = PrimaryPurple
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "You have arrived at $destName",
            style = MaterialTheme.typography.titleMedium,
            color = NavyText,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("nav-arrived")
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onEnd,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
            modifier = Modifier.testTag("nav-done")
        ) { Text("Done") }
        return
    }
    val step = pathData.steps.getOrNull(stepIndex)
    if (step == null) {
        Text("Route unavailable", color = MutedText)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = PrimaryPurple
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                step.instruction,
                style = MaterialTheme.typography.titleMedium,
                color = NavyText,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("nav-instruction")
            )
            Text(
                "${step.distanceM} m  ·  ${NavigationUiLogic.stepProgressLabel(stepIndex, pathData.steps.size)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onNext,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
            modifier = Modifier.testTag("nav-next")
        ) { Text("Next") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onEnd,
            border = BorderStroke(1.dp, CardBorder),
            modifier = Modifier.testTag("nav-end")
        ) { Text("End", color = NavyText) }
    }
}

/**
 * Outdoor sheet: no route is drawn by the app (the backend graph is indoor);
 * outdoor turn-by-turn is handed to the real Google Maps app.
 */
@Composable
private fun OutdoorRoutePanel(
    destName: String,
    onOpenMaps: () -> Unit,
    onClear: () -> Unit
) {
    Text(
        "Walk to $destName",
        style = MaterialTheme.typography.titleMedium,
        color = PrimaryPurple,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Outdoor walking directions are provided by the Google Maps app.",
        style = MaterialTheme.typography.bodyMedium,
        color = MutedText
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = onOpenMaps,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
            modifier = Modifier.testTag("outdoor-directions")
        ) {
            Icon(Icons.Default.Place, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Open in Google Maps")
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onClear, modifier = Modifier.testTag("nav-clear-outdoor")) {
            Icon(Icons.Default.Close, contentDescription = "Clear destination", tint = MutedText)
        }
    }
}

// ---------------------------------------------------------------------------
// Small controls
// ---------------------------------------------------------------------------

@Composable
private fun WalkingChip() {
    FilterChip(
        selected = true,
        onClick = { },
        label = { Text("Walking") },
        leadingIcon = {
            Icon(
                Icons.AutoMirrored.Filled.DirectionsWalk,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.testTag("mode-walking")
    )
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White,
            labelColor = NavyText,
            selectedContainerColor = PrimaryPurple,
            selectedLabelColor = Color.White
        ),
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun MapCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.testTag(tag)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = NavyText)
        }
    }
}
