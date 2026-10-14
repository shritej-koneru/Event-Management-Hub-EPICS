package com.example.eventmanagementhub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.eventmanagementhub.data.api.AuthProvider
import com.example.eventmanagementhub.ui.screens.AuthScreen
import com.example.eventmanagementhub.ui.screens.ChatScreen
import com.example.eventmanagementhub.ui.screens.EventDetailScreen
import com.example.eventmanagementhub.ui.screens.EventsScreen
import com.example.eventmanagementhub.ui.screens.HomeScreen
import com.example.eventmanagementhub.ui.screens.MapScreen
import com.example.eventmanagementhub.ui.screens.ProfileScreen
import com.example.eventmanagementhub.ui.theme.EventManagementHubTheme
import com.example.eventmanagementhub.ui.theme.PrimaryPurple
import com.example.eventmanagementhub.ui.theme.BodyBackground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val icon: ImageVector
)

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    var authed by remember { mutableStateOf(AuthProvider.token != null) }

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
            navController.navigate("auth") {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    val tabs = listOf(
        TabItem("home", "Home", Icons.Default.Home),
        TabItem("events", "Events", Icons.Default.List),
        TabItem("map", "Map", Icons.Default.Map),
        TabItem("chat", "Chat", Icons.Default.Chat),
        TabItem("profile", "Profile", Icons.Default.Person)
    )

    fun openTab(route: String) {
        val rootId = navController.currentBackStack.value.firstOrNull()?.destination?.id
        navController.navigate(route) {
            if (rootId != null) popUpTo(rootId) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = BodyBackground,
        bottomBar = {
            if (authed) {
                NavigationBar(containerColor = Color.White) {
                    val backStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = backStackEntry?.destination?.route
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = PrimaryPurple,
                                selectedTextColor = PrimaryPurple,
                                indicatorColor = Color(0xFFEDE9FF),
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            )
                        )
                    }
                }
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
                    HomeScreen(onEventClick = { id ->
                        navController.navigate("event/$id")
                    })
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
                composable("chat") {
                    ChatScreen()
                }
                composable("profile") {
                    ProfileScreen(
                        onEventClick = { id ->
                            navController.navigate("event/$id")
                        },
                        onSignOut = {
                            authed = false
                            mapTargetVenue = null
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