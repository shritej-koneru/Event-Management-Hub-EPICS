package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.Venue

/**
 * Embedded offline catalog powering 100% offline usage after login.
 * Mirrors backend/src/seed.js, venues.js and graph.js exactly (contract/API-CONTRACT.md §1).
 * DO NOT edit a venue id/edge here without updating the backend + contract together.
 */

val offlineEvents: List<Event> = listOf(
    Event(
        id = "evt_001", title = "Android Dev Workshop", category = "Tech",
        venue = "Room 204", venueId = "room-204",
        date = "2026-10-15", time = "10:00", durationMin = 90,
        capacity = 50, registered = 32, host = "Dr. Mehra", icon = "🤖",
        description = "Hands-on session building a Jetpack Compose app end to end.",
        tags = listOf("android", "compose", "workshop")
    ),
    Event(
        id = "evt_002", title = "Music Night", category = "Entertainment",
        venue = "Auditorium", venueId = "auditorium",
        date = "2026-10-16", time = "18:00", durationMin = 150,
        capacity = 300, registered = 180, host = "Campus Music Club", icon = "🎤",
        description = "Live sets from student bands across the campus.",
        tags = listOf("music", "live", "concert")
    ),
    Event(
        id = "evt_003", title = "Annual Tech Carnival", category = "Tech",
        venue = "Main Hall", venueId = "main-hall",
        date = "2026-10-18", time = "09:00", durationMin = 360,
        capacity = 500, registered = 420, host = "Student Council", icon = "🎉",
        description = "Showcases, hackathons and robotics demos all day.",
        tags = listOf("carnival", "hackathon", "robotics")
    ),
    Event(
        id = "evt_004", title = "Mindfulness & Yoga", category = "Wellness",
        venue = "Room 101", venueId = "room-101",
        date = "2026-10-20", time = "07:30", durationMin = 60,
        capacity = 40, registered = 12, host = "Wellness Center", icon = "🧘",
        description = "Morning stretch and guided meditation to start the day.",
        tags = listOf("yoga", "wellness", "meditation")
    ),
    Event(
        id = "evt_005", title = "AI Fireside Chat", category = "Tech",
        venue = "Tech Lounge", venueId = "tech-lounge",
        date = "2026-10-21", time = "17:30", durationMin = 90,
        capacity = 60, registered = 55, host = "Prof. Iyer", icon = "🧠",
        description = "Open conversation on practical AI for students.",
        tags = listOf("ai", "ml", "talk")
    ),
    Event(
        id = "evt_006", title = "Drama: The Glass Menagerie", category = "Arts",
        venue = "Auditorium", venueId = "auditorium",
        date = "2026-10-23", time = "19:00", durationMin = 120,
        capacity = 250, registered = 90, host = "Drama Society", icon = "🎭",
        description = "Student production of Tennessee Williams’ classic.",
        tags = listOf("theatre", "drama", "play")
    ),
    Event(
        id = "evt_007", title = "Robotics Lab Open Day", category = "Tech",
        venue = "Lab B", venueId = "lab-b",
        date = "2026-10-24", time = "11:00", durationMin = 180,
        capacity = 80, registered = 63, host = "Robotics Club", icon = "🚀",
        description = "Tour the lab, watch demos, and drive the bots yourself.",
        tags = listOf("robotics", "demo", "lab")
    ),
    Event(
        id = "evt_008", title = "Coding Contest: Speedrun", category = "Competitive",
        venue = "Room 205", venueId = "room-205",
        date = "2026-10-25", time = "14:00", durationMin = 120,
        capacity = 60, registered = 44, host = "ACM Chapter", icon = "⚡",
        description = "90 minutes of DSA problems. Fastest solvers win swag.",
        tags = listOf("coding", "dsa", "contest")
    ),
    Event(
        id = "evt_009", title = "Campus Poetry Slam", category = "Arts",
        venue = "Library", venueId = "library",
        date = "2026-10-27", time = "18:30", durationMin = 90,
        capacity = 45, registered = 21, host = "Lit Club", icon = "📖",
        description = "Open-mic poetry and spoken word.",
        tags = listOf("poetry", "spoken-word", "open-mic")
    ),
    Event(
        id = "evt_010", title = "Startup Pitch Night", category = "Competitive",
        venue = "Registration Desk", venueId = "registration",
        date = "2026-10-29", time = "17:00", durationMin = 150,
        capacity = 100, registered = 38, host = "E-Cell", icon = "💡",
        description = "Student founders pitch; alumni panel picks a winner.",
        tags = listOf("startup", "pitch", "entrepreneurship")
    ),
    Event(
        id = "evt_011", title = "Photography Walk", category = "Arts",
        venue = "Cafeteria", venueId = "cafeteria",
        date = "2026-11-01", time = "08:00", durationMin = 120,
        capacity = 40, registered = 9, host = "Photo Club", icon = "📷",
        description = "Golden-hour walk around campus; bring any camera.",
        tags = listOf("photography", "walk", "outdoors")
    ),
    Event(
        id = "evt_012", title = "Career Fair 2026", category = "Career",
        venue = "Main Hall", venueId = "main-hall",
        date = "2026-11-05", time = "10:00", durationMin = 300,
        capacity = 600, registered = 480, host = "Placement Cell", icon = "💼",
        description = "Meet recruiters from 40+ companies, resume reviews, mock interviews.",
        tags = listOf("career", "jobs", "networking")
    )
)

// id -> neighbor id -> meters (mirrors GRAPH in backend/src/venues.js).
val offlineGraph: Map<String, Map<String, Int>> = mapOf(
    "entrance" to mapOf("lobby" to 40),
    "lobby" to mapOf("entrance" to 40, "registration" to 15, "main-hall" to 30),
    "registration" to mapOf("lobby" to 15, "auditorium" to 20),
    "auditorium" to mapOf("registration" to 20, "main-hall" to 25, "tech-lounge" to 30),
    "main-hall" to mapOf("lobby" to 30, "auditorium" to 25, "room-101" to 18, "room-102" to 18, "cafeteria" to 22),
    "room-101" to mapOf("main-hall" to 18, "room-102" to 8),
    "room-102" to mapOf("main-hall" to 18, "room-101" to 8, "tech-lounge" to 12),
    "room-204" to mapOf("room-205" to 8, "lab-a" to 10, "tech-lounge" to 15),
    "room-205" to mapOf("room-204" to 8, "lab-b" to 10),
    "lab-a" to mapOf("room-204" to 10, "lab-b" to 8, "library" to 20),
    "lab-b" to mapOf("lab-a" to 8, "room-205" to 10),
    "cafeteria" to mapOf("main-hall" to 22, "tech-lounge" to 18, "library" to 25),
    "library" to mapOf("lab-a" to 20, "cafeteria" to 25),
    "tech-lounge" to mapOf("auditorium" to 30, "room-102" to 12, "room-204" to 15, "cafeteria" to 18)
)

val offlineVenueNames: Map<String, String> = mapOf(
    "entrance" to "Entrance",
    "lobby" to "Lobby",
    "registration" to "Registration Desk",
    "auditorium" to "Auditorium",
    "main-hall" to "Main Hall",
    "room-101" to "Room 101",
    "room-102" to "Room 102",
    "room-204" to "Room 204",
    "room-205" to "Room 205",
    "lab-a" to "Lab A",
    "lab-b" to "Lab B",
    "cafeteria" to "Cafeteria",
    "library" to "Library",
    "tech-lounge" to "Tech Lounge"
)

/**
 * Canonical venue ordering. The single source of truth for the graph traversal
 * order used by [OfflineEngine] and for enumerating Socket.IO rooms, so those
 * lists cannot drift apart from [offlineVenueNames].
 */
val offlineVenueOrder: List<String> = listOf(
    "entrance", "lobby", "registration", "auditorium", "main-hall",
    "room-101", "room-102", "room-204", "room-205", "lab-a", "lab-b",
    "cafeteria", "library", "tech-lounge"
)

val offlineVenues: List<Venue> = offlineVenueOrder.map { id ->
    Venue(id = id, name = offlineVenueNames[id].orEmpty(), neighbors = offlineGraph[id].orEmpty().keys.toList())
}

val offlineAnnouncements: List<Announcement> = listOf(
    Announcement(id = 1, room = "main-hall", message = "Annual Tech Carnival opens tomorrow at Main Hall! 🎉", at = "", eventId = "evt_003"),
    Announcement(id = 2, room = "room-204", message = "Android Dev Workshop: bring a laptop, seats are filling up. 🤖", at = "", eventId = "evt_001"),
    Announcement(id = 3, room = "auditorium", message = "Music Night doors open at 17:30. 🔊", at = "", eventId = "evt_002")
)