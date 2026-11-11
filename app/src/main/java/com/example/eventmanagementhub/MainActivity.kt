package com.example.eventmanagementhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.activity.compose.LocalActivity
import android.annotation.SuppressLint
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.eventmanagementhub.ui.screens.LiveSocketViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.eventmanagementhub.data.api.AuthProvider
import com.example.eventmanagementhub.ui.screens.AiAssistantScreen
import com.example.eventmanagementhub.ui.screens.AuthScreen
import com.example.eventmanagementhub.ui.screens.AnnouncementsEventsScreen
import com.example.eventmanagementhub.ui.screens.ChatThreadScreen
import com.example.eventmanagementhub.ui.screens.EventAnnouncementsScreen
import com.example.eventmanagementhub.ui.screens.HelpChatsScreen
import com.example.eventmanagementhub.ui.screens.NewChatScreen
import com.example.eventmanagementhub.ui.screens.EventDetailScreen
import com.example.eventmanagementhub.ui.screens.EventsScreen
import com.example.eventmanagementhub.ui.screens.HomeScreen
import com.example.eventmanagementhub.ui.screens.MapScreen
import com.example.eventmanagementhub.ui.screens.ProfileScreen
import com.example.eventmanagementhub.ui.theme.EventManagementHubTheme
import com.example.eventmanagementhub.ui.components.HubDivider
import com.example.eventmanagementhub.ui.theme.Accent
import com.example.eventmanagementhub.ui.theme.HubSurface
import com.example.eventmanagementhub.ui.theme.Ink3
import com.example.eventmanagementhub.ui.theme.Paper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Paper bars with dark icons (light style = dark icons on a light scrim colour).
        val paper = Paper.toArgb()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(paper, paper),
            navigationBarStyle = SystemBarStyle.light(paper, paper)
        )
        setContent {
            EventManagementHubTheme {
                AppRoot()
            }
        }
    }
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** Single-line label for the bottom bar when [label] does not fit 5 tabs at 360dp. */
    val shortLabel: String = label
)

/**
 * Flat bottom navigation: Surface with a top hairline, 56dp tall; active tab = Accent
 * icon + label with a 2dp accent bar on top. Labels are single-line (Caption).
 */
@Composable
private fun HubBottomBar(
    tabs: List<TabItem>,
    isSelected: (TabItem) -> Boolean,
    onSelect: (TabItem) -> Unit
) {
    Column(Modifier.fillMaxWidth().background(HubSurface)) {
        HubDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(56.dp)
                .selectableGroup()
        ) {
            tabs.forEach { tab ->
                val selected = isSelected(tab)
                val tint = if (selected) Accent else Ink3
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                        // One accessibility node per tab, with the full name and real bounds
                        // (the visible short label used to show up as a separate zero-size node).
                        .semantics(mergeDescendants = true) { contentDescription = tab.label },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.5f)
                            .height(2.dp)
                            .background(if (selected) Accent else Color.Transparent)
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        tab.shortLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.clearAndSetSemantics { }
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    // Session socket belongs to the signed-in identity; drop them on sign-out/expiry.
    fun dropChatSession() {
        ViewModelProvider(activity)[LiveSocketViewModel::class.java].onSignedOut()
    }
    var authed by remember { mutableStateOf(AuthProvider.token != null) }
    // Keep the socket alive app-wide once signed in so `map:updated` and announcements
    // arrive on any tab (start() is idempotent).
    LaunchedEffect(authed) {
        if (authed) ViewModelProvider(activity)[LiveSocketViewModel::class.java].start()
    }

    /**
     * Venue handed from Event Details ("Navigate to X") to the map tab. Held as
     * state rather than a nav argument so the bottom-bar route stays "map" and
     * still highlights correctly; MapScreen consumes and clears it.
     */
    var mapTargetVenue by remember { mutableStateOf<String?>(null) }

    // A 401 from the backend drops the session and returns the user to sign-in.
    val sessionExpired by AuthProvider.sessionExpired.collectAsStateWithLifecycle()
    LaunchedEffect(sessionExpired) {
        if (sessionExpired) {
            AuthProvider.consumeSessionExpiry()
            authed = false
            mapTargetVenue = null
            dropChatSession()
            navController.navigate("auth") {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    val tabs = listOf(
        TabItem("home", "Home", Icons.Default.Home),
        TabItem("events", "Events", Icons.Default.List),
        TabItem("map", "Map", Icons.Default.Map),
        TabItem("announcements", "Announcements", Icons.Default.Campaign, shortLabel = "Updates"),
        TabItem("profile", "Profile", Icons.Default.Person)
    )

    @SuppressLint("RestrictedApi") // currentBackStack: reads the root destination to pop to
    fun openTab(route: String) {
        val rootId = navController.currentBackStack.value.firstOrNull()?.destination?.id
        navController.navigate(route) {
            if (rootId != null) popUpTo(rootId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = Paper,
        bottomBar = {
            if (authed) {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                HubBottomBar(
                    tabs = tabs,
                    isSelected = { tab ->
                        // Nested routes (announcements/{eventId}/...) keep their tab highlighted.
                        currentRoute == tab.route || currentRoute?.startsWith(tab.route + "/") == true
                    },
                    onSelect = { tab ->
                        // Re-tapping the active tab from a nested screen returns to its root.
                        if (currentRoute != null && currentRoute.startsWith(tab.route + "/")) {
                            navController.popBackStack(tab.route, inclusive = false)
                        } else openTab(tab.route)
                    }
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            NavHost(
                navController = navController,
                startDestination = "auth"
            ) {
                composable("auth") {
                    AuthScreen(
                        onAuthed = { isNewAccount ->
                            authed = true
                            navController.navigate(if (isNewAccount) "profile" else "home") {
                                popUpTo("auth") { inclusive = true }
                            }
                        }
                    )
                }
                composable("home") {
                    HomeScreen(
                        onEventClick = { id -> navController.navigate("event/$id") },
                        // Same hand-off as Event Details: the Map tab consumes the venue.
                        onDirections = { e ->
                            mapTargetVenue = e.venueId
                            openTab("map")
                        },
                        onSeeAll = { openTab("events") },
                        onAssistant = { navController.navigate("assistant") }
                    )
                }
                composable("assistant") {
                    AiAssistantScreen(
                        onBack = { navController.popBackStack() },
                        onOpenEvent = { id -> navController.navigate("event/$id") }
                    )
                }
                composable("events") {
                    EventsScreen(onEventClick = { id ->
                        navController.navigate("event/$id")
                    })
                }
                composable("map") {
                    MapScreen(
                        initialTo = mapTargetVenue,
                        onTargetConsumed = { mapTargetVenue = null }
                    )
                }
                composable("announcements") {
                    AnnouncementsEventsScreen(
                        onEventClick = { id -> navController.navigate("announcements/${Uri.encode(id)}") },
                        onBrowseEvents = { openTab("events") }
                    )
                }
                composable(
                    route = "announcements/{eventId}",
                    arguments = listOf(navArgument("eventId") { type = NavType.StringType })
                ) { entry ->
                    val eventId = entry.arguments?.getString("eventId") ?: ""
                    EventAnnouncementsScreen(
                        eventId = eventId,
                        onBack = { navController.popBackStack() },
                        onHelp = { navController.navigate("announcements/${Uri.encode(eventId)}/help") }
                    )
                }
                composable(
                    route = "announcements/{eventId}/help",
                    arguments = listOf(navArgument("eventId") { type = NavType.StringType })
                ) { entry ->
                    val eventId = entry.arguments?.getString("eventId") ?: ""
                    HelpChatsScreen(
                        eventId = eventId,
                        onBack = { navController.popBackStack() },
                        onOpenTicket = { tid ->
                            navController.navigate("announcements/${Uri.encode(eventId)}/chat/${Uri.encode(tid)}")
                        },
                        onNewChat = { navController.navigate("announcements/${Uri.encode(eventId)}/help/new") }
                    )
                }
                composable(
                    route = "announcements/{eventId}/help/new",
                    arguments = listOf(navArgument("eventId") { type = NavType.StringType })
                ) { entry ->
                    val eventId = entry.arguments?.getString("eventId") ?: ""
                    NewChatScreen(
                        eventId = eventId,
                        onBack = { navController.popBackStack() },
                        onCreated = { tid ->
                            // Replace the form with the new thread; back returns to the chat list.
                            navController.navigate("announcements/${Uri.encode(eventId)}/chat/${Uri.encode(tid)}") {
                                popUpTo("announcements/{eventId}/help/new") { inclusive = true }
                            }
                        }
                    )
                }
                composable(
                    route = "announcements/{eventId}/chat/{ticketId}",
                    arguments = listOf(
                        navArgument("eventId") { type = NavType.StringType },
                        navArgument("ticketId") { type = NavType.StringType }
                    )
                ) { entry ->
                    ChatThreadScreen(
                        ticketId = entry.arguments?.getString("ticketId") ?: "",
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("profile") {
                    ProfileScreen(
                        onEventClick = { id ->
                            navController.navigate("event/$id")
                        },
                        onSignOut = {
                            authed = false
                            mapTargetVenue = null
                            dropChatSession()
                            navController.navigate("auth") {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    )
                }
                composable(
                    route = "event/{eventId}",
                    arguments = listOf(navArgument("eventId") { type = NavType.StringType })
                ) { entry ->
                    val eventId = entry.arguments?.getString("eventId") ?: ""
                    EventDetailScreen(
                        eventId = eventId,
                        onBack = { navController.popBackStack() },
                        onNavigate = { venueId ->
                            // Pass the venue through to the map tab.
                            mapTargetVenue = venueId
                            openTab("map")
                        }
                    )
                }
            }
        }
    }
}