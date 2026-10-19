package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.HubRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.CampusMapResponse
import com.example.eventmanagementhub.data.model.PathResponse
import com.example.eventmanagementhub.data.model.Venue
import com.example.eventmanagementhub.ui.theme.*
import kotlinx.coroutines.launch

/** Destination used when the user has not asked to go anywhere specific. */
private const val DEFAULT_DESTINATION = "room-204"

// Venue positions come from GET /api/navigation/map (backend is the source of
// truth); the layout is no longer hard-coded here.

@Composable
fun MapScreen(
    initialTo: String? = null,
    onTargetConsumed: () -> Unit = {},
    repository: HubRepository = HubRepository()
) {
    var venues by remember { mutableStateOf<List<Venue>>(emptyList()) }
    var campusMap by remember { mutableStateOf<CampusMapResponse?>(null) }
    var from by remember { mutableStateOf("entrance") }
    var to by remember { mutableStateOf<String?>(null) }
    var path by remember { mutableStateOf<PathResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isStale by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
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
                is Result.Error -> error = res.message
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
        if (venues.isEmpty()) return@LaunchedEffect
        val requested = initialTo?.takeIf { id -> venues.any { it.id == id } }
        if (requested != null) {
            to = requested
            runPath(from, requested)
            onTargetConsumed()
            return@LaunchedEffect
        }
        if (to == null) {
            to = DEFAULT_DESTINATION
            runPath(from, DEFAULT_DESTINATION)
        }
    }

    val currentTo = to ?: DEFAULT_DESTINATION

    Column(
        Modifier
            .fillMaxSize()
            .background(BodyBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Campus Map & Navigation",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.Black,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            VenueDropdown("From", venues, from, Modifier.weight(1f)) {
                from = it
                runPath(from, currentTo)
            }
            VenueDropdown("To", venues, currentTo, Modifier.weight(1f)) {
                to = it
                runPath(from, it)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PrimaryPurple)
            }
        } else {
            CampusMap(campusMap ?: CampusMapResponse(), path?.nodes?.map { it.id } ?: emptyList())
            Spacer(Modifier.height(12.dp))
            if (isStale) {
                Text(
                    "Offline — showing the map and route stored on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB26A00)
                )
                Spacer(Modifier.height(8.dp))
            }
            val p = path
            if (p != null) {
                PathResultCard(p)
            } else {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun CampusMap(map: CampusMapResponse, pathIds: List<String>) {
    val edgeColor = CardBorder
    val pathColor = PrimaryPurple
    val pathSet = remember(pathIds) { pathIds.toSet() }

    // Fractional positions from the backend map (fallback: canvas centre).
    val positions = remember(map.venues) {
        map.venues.associate { it.id to Offset(it.x.toFloat(), it.y.toFloat()) }
    }

    // Undirected edges, one segment each (server stores each edge once).
    val edges = remember(map.edges) { map.edges.map { it.from to it.to } }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(EventCardWhiteGradient)
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
    }
}

@Composable
private fun PathResultCard(pathData: PathResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(EventCardWhiteGradient)
                .padding(16.dp)
        ) {
            Text(
                "Route  ·  ${pathData.totalDistanceM} m",
                style = MaterialTheme.typography.titleMedium,
                color = PrimaryPurple,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            pathData.steps.forEachIndexed { index, step ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.titleMedium,
                        color = NavyText,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        step.instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF3A3850),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VenueDropdown(
    label: String,
    venues: List<Venue>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val name = venues.firstOrNull { it.id == selected }?.name ?: selected
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, CardBorder)
        ) {
            Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MutedText)
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