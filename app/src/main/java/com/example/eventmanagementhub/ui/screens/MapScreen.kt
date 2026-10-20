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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
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
 * Polished campus navigation canvas driven entirely by the backend map
 * (fractional venue positions + undirected edges from GET /api/navigation/map).
 *
 * Visual language (Google-Maps-inspired indoor plan):
 *  - soft neutral floor background
 *  - rooms as white rounded cards with centred names (ellipsised when long)
 *  - corridors as soft beds under every edge; ordinary links are thin muted
 *    centre-lines; the computed route is a thick purple path with a white
 *    casing and rounded caps
 *  - start = green chip + dot, destination = red chip + dot, active step =
 *    purple ring + "Now" chip; traveled route segments mute once navigating
 *  - fixed corner legend (does not zoom with the map)
 *
 * Graph topology, distances and route order always come from the backend —
 * nothing is invented or re-wired here.
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
    val textMeasurer = rememberTextMeasurer()
    val pathSet = remember(pathIds) { pathIds.toSet() }
    val completedSet = remember(pathIds, activeNodeId) {
        NavigationUiLogic.completedPathIds(pathIds, activeNodeId)
    }

    val roomNameStyle = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = NavyText,
        textAlign = TextAlign.Center
    )
    val chipTextStyle = TextStyle(
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White
    )

    // Fractional positions from the backend map (fallback: canvas centre).
    val positions = remember(map.venues) {
        map.venues.associate { it.id to Offset(it.x.toFloat(), it.y.toFloat()) }
    }
    // Undirected edges, one segment each (server stores each edge once).
    val edges = remember(map.edges) { map.edges.map { it.from to it.to } }

    // Room cards are measured against the canvas size so long names ellipsize
    // consistently; size comes from onSizeChanged on the Canvas host.
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val roomNameStyleLocal = roomNameStyle
    val rooms = remember(map.venues, canvasSize, textMeasurer) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@remember emptyList()
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        map.venues.map { v ->
            val frac = positions[v.id] ?: Offset(0.5f, 0.5f)
            val center = Offset(frac.x * w, frac.y * h)
            val maxLabel = (w * 0.22f).coerceIn(64f, 150f)
            val layout = textMeasurer.measure(
                text = v.name,
                style = roomNameStyleLocal,
                constraints = Constraints(maxWidth = maxLabel.toInt())
            )
            IndoorRoom(
                id = v.id,
                center = center,
                width = (layout.size.width + 32f).coerceIn(64f, maxLabel + 32f),
                height = (layout.size.height + 24f).coerceAtLeast(38f)
            )
        }
    }
    val nameById = remember(map.venues) { map.venues.associate { it.id to it.name } }

    Box(modifier = modifier) {
        Canvas(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(20.dp))
                .background(IndoorMapBackground)
                .onSizeChanged { canvasSize = it }
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

            // --- corridors + links (under rooms) --------------------------------
            for ((a, b) in edges) {
                val p1 = pos(a)
                val p2 = pos(b)
                val bothOnPath = pathSet.isNotEmpty() && a in pathSet && b in pathSet
                val bothCompleted = completedSet.isNotEmpty() && a in completedSet && b in completedSet

                if (bothOnPath) {
                    // White casing + coloured route stroke (remaining bright,
                    // already-walked segments muted).
                    drawLine(
                        color = IndoorRouteCasing,
                        start = p1,
                        end = p2,
                        strokeWidth = 16f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = if (bothCompleted) IndoorRouteTraveled else IndoorRoute,
                        start = p1,
                        end = p2,
                        strokeWidth = 10f,
                        cap = StrokeCap.Round
                    )
                } else {
                    // Soft corridor bed + thin muted centre line.
                    drawLine(
                        color = IndoorCorridorBed,
                        start = p1,
                        end = p2,
                        strokeWidth = 12f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = IndoorEdgeLine,
                        start = p1,
                        end = p2,
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // --- room cards ------------------------------------------------------
            fun drawRoomCard(room: IndoorRoom, fill: Color, stroke: Color, strokeW: Float) {
                val topLeft = Offset(room.center.x - room.width / 2f, room.center.y - room.height / 2f)
                drawRoundRect(
                    color = fill,
                    topLeft = topLeft,
                    size = Size(room.width, room.height),
                    cornerRadius = CornerRadius(14f, 14f)
                )
                drawRoundRect(
                    color = stroke,
                    topLeft = topLeft,
                    size = Size(room.width, room.height),
                    cornerRadius = CornerRadius(14f, 14f),
                    style = Stroke(width = strokeW)
                )
            }

            fun drawRoomLabel(room: IndoorRoom, name: String) {
                val maxLabel = (room.width - 20f).toInt().coerceAtLeast(40)
                val layout = textMeasurer.measure(
                    text = name,
                    style = roomNameStyleLocal,
                    constraints = Constraints(maxWidth = maxLabel)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        room.center.x - layout.size.width / 2f,
                        room.center.y - layout.size.height / 2f
                    )
                )
            }

            fun drawChip(center: Offset, label: String, color: Color) {
                val layout = textMeasurer.measure(label, style = chipTextStyle)
                val cw = layout.size.width + 18f
                val ch = layout.size.height + 10f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(center.x - cw / 2f, center.y - ch / 2f),
                    size = Size(cw, ch),
                    cornerRadius = CornerRadius(ch / 2f, ch / 2f)
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f)
                )
            }

            for (room in rooms) {
                val onPath = room.id in pathSet
                val isActive = room.id == activeNodeId
                val fill = when {
                    onPath || isActive -> IndoorRoomOnPathFill
                    else -> IndoorRoomFill
                }
                val stroke = when {
                    isActive -> IndoorActiveRing
                    room.id == startId -> IndoorStart
                    room.id == endId -> IndoorDestination
                    onPath -> IndoorRoute.copy(alpha = 0.45f)
                    else -> IndoorRoomStroke
                }
                val strokeW = when {
                    isActive || room.id == startId || room.id == endId -> 2.5f
                    onPath -> 1.8f
                    else -> 1.5f
                }
                drawRoomCard(room, fill, stroke, strokeW)
                drawRoomLabel(room, nameById[room.id] ?: room.id)
            }

            // --- status rings + chips (drawn last so they sit on top) ------------
            for (room in rooms) {
                val id = room.id
                if (id == activeNodeId) {
                    drawCircle(
                        color = IndoorActiveRing.copy(alpha = 0.18f),
                        radius = maxOf(room.width, room.height) / 2f + 10f,
                        center = room.center
                    )
                    drawCircle(
                        color = IndoorActiveRing,
                        radius = maxOf(room.width, room.height) / 2f + 6f,
                        center = room.center,
                        style = Stroke(width = 3f)
                    )
                    drawChip(
                        center = Offset(room.center.x, room.center.y - room.height / 2f - 16f),
                        label = "Now",
                        color = IndoorActiveRing
                    )
                }
                if (id == startId) {
                    drawCircle(
                        color = IndoorStart,
                        radius = 7f,
                        center = Offset(room.center.x, room.center.y + room.height / 2f + 12f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3f,
                        center = Offset(room.center.x, room.center.y + room.height / 2f + 12f)
                    )
                    drawChip(
                        center = Offset(room.center.x, room.center.y - room.height / 2f - 16f),
                        label = "Start",
                        color = IndoorStart
                    )
                }
                if (id == endId) {
                    drawCircle(
                        color = IndoorDestination,
                        radius = 7f,
                        center = Offset(room.center.x, room.center.y + room.height / 2f + 12f)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3f,
                        center = Offset(room.center.x, room.center.y + room.height / 2f + 12f)
                    )
                    drawChip(
                        center = Offset(room.center.x, room.center.y - room.height / 2f - 16f),
                        label = "End",
                        color = IndoorDestination
                    )
                }
            }
        }

        IndoorMapLegend(
            Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )
    }
}

/** Measured room card geometry for one venue on the indoor canvas. */
private data class IndoorRoom(
    val id: String,
    val center: Offset,
    val width: Float,
    val height: Float
)

/**
 * Compact fixed legend for the indoor map. Lives outside the zoomable canvas
 * so it stays readable at any zoom level.
 */
@Composable
private fun IndoorMapLegend(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
        border = BorderStroke(1.dp, CardBorder),
        modifier = modifier.testTag("indoor-map-legend")
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            LegendRow(IndoorStart, "Start")
            LegendRow(IndoorRoute, "Route")
            LegendRow(IndoorDestination, "Destination")
            LegendRow(IndoorEdgeLine, "Walkway", isLine = true)
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, isLine: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        if (isLine) {
            Canvas(Modifier.size(width = 14.dp, height = 6.dp)) {
                drawLine(
                    color = color,
                    start = Offset(0f, size.height / 2f),
                    end = Offset(size.width, size.height / 2f),
                    strokeWidth = 4f,
                    cap = StrokeCap.Round
                )
            }
        } else {
            Canvas(Modifier.size(10.dp)) {
                drawCircle(color = color)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = NavyText
        )
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
