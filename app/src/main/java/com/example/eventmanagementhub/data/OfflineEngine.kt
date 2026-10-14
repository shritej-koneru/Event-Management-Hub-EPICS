package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.model.AiResult
import com.example.eventmanagementhub.data.model.AiSearchResponse
import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.ChartSlice
import com.example.eventmanagementhub.data.model.EventChart
import com.example.eventmanagementhub.data.model.EventNavigation
import com.example.eventmanagementhub.data.model.EventStatistics
import com.example.eventmanagementhub.data.model.EventVenue
import com.example.eventmanagementhub.data.model.PathNode
import com.example.eventmanagementhub.data.model.PathResponse
import com.example.eventmanagementhub.data.model.PathStep
import com.example.eventmanagementhub.data.model.RecommendationResponse
import com.example.eventmanagementhub.data.model.UserProfile

/**
 * On-device replica of the backend AI + navigation logic (ai/scoring.js,
 * ai/tokenizer.js, navigation/graph.js).
 *
 * Used whenever the network/backend is unreachable so the app stays fully useful
 * offline. Weights, stopwords, synonyms, the relevance gate and the reason strings
 * mirror the server exactly — a query that matches nothing returns an empty list
 * here just as it does online.
 */
object OfflineEngine {

    // ---- tokenizer (mirrors backend/src/ai/tokenizer.js) ----

    private val STOP = setOf(
        "a", "an", "the", "for", "and", "or", "to", "of", "in", "on", "at", "with",
        "your", "my", "is", "are", "be", "i", "me", "any", "some", "show", "find"
    )

    private fun tokens(text: String = ""): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), " ")
            .split(Regex("[\\s-]+"))
            .filter { it.length > 1 && it !in STOP }

    private val CATEGORY_SYNONYMS = mapOf(
        "tech" to listOf("technology", "coding", "software", "developer", "android", "ai", "robotics", "app", "code"),
        "entertainment" to listOf("music", "concert", "party", "show", "band", "live"),
        "arts" to listOf("drama", "poetry", "theatre", "theater", "photography", "art", "music"),
        "wellness" to listOf("yoga", "meditation", "health", "relax", "fitness", "mindfulness"),
        "competitive" to listOf("contest", "hackathon", "competition", "pitch", "coding"),
        "career" to listOf("job", "jobs", "internship", "networking", "placement", "recruiter", "career")
    )

    // ---- scoring (mirrors backend/src/ai/scoring.js) ----

    private const val W_TITLE = 4.0
    private const val W_TAG = 3.0
    private const val W_CATEGORY = 3.0
    private const val W_DESCRIPTION = 2.0
    private const val W_SYNONYM = 2.5
    private const val W_HOST = 1.5
    private const val W_POPULARITY = 0.5
    private const val W_TITLE_BOOST = 0.2

    /** Relevance floor: below this an event has no textual relationship to the query. */
    private const val MIN_TEXT_RAW = 0.5

    private const val SCORE_K = 6.0

    private const val MIN_PREFIX_LEN = 3

    private fun hits(words: List<String>, term: String): Boolean =
        words.any { it == term || (term.length >= MIN_PREFIX_LEN && it.startsWith(term)) }


    private fun normalise(value: Double): Double {
        if (value <= 0.0) return 0.0
        return Math.round((value / (value + SCORE_K)) * 10000.0) / 10000.0
    }

    private class Scored(
        val textRaw: Double,
        val popularity: Double,
        val matches: Boolean,
        val fields: Set<String>
    )

    private fun scoreEvent(event: Event, queryTerms: List<String>): Scored {
        val title = tokens(event.title)
        val description = tokens(event.description)
        val host = tokens(event.host)
        val tags = event.tags.map { it.lowercase() }
        val category = event.category.lowercase()
        val synonyms = CATEGORY_SYNONYMS[category].orEmpty()

        var textRaw = 0.0
        var titleHit = false
        val fields = linkedSetOf<String>()

        for (term in queryTerms) {
            if (hits(title, term)) {
                textRaw += W_TITLE
                titleHit = true
                fields += "title"
            } else if (hits(description, term)) {
                textRaw += W_DESCRIPTION
                fields += "description"
            }
            if (hits(host, term)) {
                textRaw += W_HOST
                fields += "host"
            }
            if (hits(listOf(category), term)) {
                textRaw += W_CATEGORY
                fields += "category"
            }
            if (hits(synonyms, term)) {
                textRaw += W_SYNONYM
                fields += "synonym"
            }
            if (hits(tags, term)) {
                textRaw += W_TAG
                fields += "tags"
            }
        }
        if (titleHit) {
            textRaw += W_TITLE_BOOST
            fields += "title"
        }

        // Popularity is a tie-breaker only; it can never make an event eligible.
        val popularity = (event.registered / maxOf(event.capacity, 1).toDouble()) * W_POPULARITY
        return Scored(textRaw, popularity, textRaw >= MIN_TEXT_RAW, fields)
    }

    private val FIELD_REASON = mapOf(
        "title" to "Matches the event title",
        "tags" to "Matches event tags",
        "category" to "Matches the event category",
        "description" to "Matches the event description",
        "synonym" to "Related to the event category",
        "host" to "Matches the host"
    )

    private fun describeMatch(fields: Set<String>, terms: List<String>): String {
        val named = fields.firstOrNull { it in FIELD_REASON }
        if (named != null) return FIELD_REASON.getValue(named)
        return if (terms.isNotEmpty()) "Matches your search" else "Suggested for you"
    }

    /**
     * Personalization for a signed-in user. Mirrors the server: interests, then
     * bookmarks, then recent searches. There is deliberately no "you registered"
     * branch — registrations live on the device, bookmarks are the shared signal.
     */
private fun personalizationSignal(
        event: Event,
        user: UserProfile?,
        recentQueries: List<String> = OfflineStore.recentQueries()
    ): Pair<Double, String> {
        if (user == null) return 0.0 to ""
        val tags = event.tags.map { it.lowercase() }
        val category = event.category.lowercase()
        val title = event.title.lowercase()

        val interestHit = user.interests.firstOrNull { interest ->
            val needle = interest.lowercase()
            tags.contains(needle) || category == needle || title.contains(needle)
        }
        if (interestHit != null) return 1.5 to "Matches your interest in: $interestHit"

        if (user.bookmarks.contains(event.id)) return 1.5 to "You bookmarked this event"

        val recentTerms = recentQueries.flatMap { tokens(it) }
        val similar = recentTerms.any { term ->
            tags.contains(term) || category == term || title.contains(term)
        }
        if (similar) return 1.0 to "Similar to your recent searches"

        return 0.0 to ""
    }

    private fun finalScore(textRaw: Double, popularity: Double, boost: Double): Double =
        normalise(textRaw + popularity + boost)

    /**
     * AI search. The relevance gate mirrors the server: an event is eligible only
     * with real textual evidence, or — for a signed-in user — a personalization
     * signal. A query matching nothing yields an empty list, not the catalogue.
     */
fun search(query: String, limit: Int = 10): AiSearchResponse {
        OfflineStore.addRecentQuery(query)
        return searchOver(query, OfflineStore.allEvents(), OfflineStore.user(), limit)
    }

    /**
     * Pure scoring core, split out from [search] so the ranking rules can be tested
     * against fixed catalogues instead of whatever the device cache happens to hold.
     */
    fun searchOver(
        query: String,
        events: List<Event>,
        user: UserProfile?,
        limit: Int = 10
    ): AiSearchResponse {
        val queryTerms = tokens(query)
        val signedIn = user != null

        val scored = events.map { event ->
            val s = scoreEvent(event, queryTerms)
            val (boost, reason) = personalizationSignal(event, user)
            event to Triple(
                finalScore(s.textRaw, s.popularity, boost),
                reason.ifBlank { describeMatch(s.fields, queryTerms) },
                s.matches || (signedIn && boost > 0)
            )
        }

        val results = scored
            .filter { it.second.third }
            .sortedWith(compareByDescending<Pair<Event, Triple<Double, String, Boolean>>> { it.second.first }
                .thenBy { it.first.date })
            .take(limit)
            .map { (event, meta) ->
                AiResult(event = event, score = meta.first, reason = meta.second)
            }

        return AiSearchResponse(query = query, results = results)
    }

    fun recommendations(): RecommendationResponse {
        val user = OfflineStore.user() ?: return RecommendationResponse()
        return recommendationsFor(user, OfflineStore.allEvents(), OfflineStore.recentQueries())
    }

    /** Pure recommendations core — see [searchOver] for why this is split out. */
    fun recommendationsFor(
        user: UserProfile,
        events: List<Event>,
        recentQueries: List<String>
    ): RecommendationResponse {
        val scored = events.map { event ->
            val s = scoreEvent(event, emptyList())
            val (boost, reason) = personalizationSignal(event, user, recentQueries)
            event to Triple(
                finalScore(s.textRaw, s.popularity, boost),
                reason.ifBlank { "Suggested for you" },
                boost > 0
            )
        }
        val recs = scored
            .filter { it.second.third }
            .sortedWith(compareByDescending<Pair<Event, Triple<Double, String, Boolean>>> { it.second.first }
                .thenBy { it.first.date })
            .take(8)
            .map { (event, meta) -> AiResult(event = event, score = meta.first, reason = meta.second) }
        return RecommendationResponse(recommendations = recs)
    }

    // ---- Event Details (offline composition) ----

    /**
     * Builds the Event Details aggregate from cached data so the screen degrades
     * gracefully offline instead of showing a bare event card.
     */
    fun details(event: Event, from: String?, announcements: List<Announcement>): EventDetails {
        val events = OfflineStore.allEvents()
        val seats = (event.capacity - event.registered).coerceAtLeast(0)
        val occupancy = if (event.capacity > 0) (event.registered * 100) / event.capacity else 0
        val scoped = announcements.filter { it.eventId == event.id }

        val nav = runCatching { path(from?.takeIf { it.isNotBlank() } ?: "entrance", event.venueId) }.getOrNull()

        val stats = EventStatistics(
            capacity = event.capacity,
            registered = event.registered,
            seatsRemaining = seats,
            occupancyPercent = occupancy,
            durationMin = event.durationMin,
            announcementCount = scoped.size,
            bookmarkCount = events.count { OfflineStore.user()?.bookmarks?.contains(it.id) == true },
            isBookmarked = OfflineStore.user()?.bookmarks?.contains(event.id) == true,
            categoryEventCount = events.count { it.category == event.category },
            totalEvents = events.size,
            isPast = event.date < today(),
            daysUntilEvent = daysUntil(event.date)
        )

        val charts = buildList {
            if (event.capacity > 0) {
                add(
                    EventChart(
                        id = "occupancy",
                        title = "Capacity",
                        type = "donut",
                        unit = "people",
                        total = event.capacity.toDouble(),
                        data = listOf(
                            ChartSlice("Registered", event.registered.toDouble()),
                            ChartSlice("Available", seats.toDouble())
                        )
                    )
                )
            }
            add(
                EventChart(
                    id = "categoryMix",
                    title = "${event.category} vs other categories",
                    type = "bar",
                    unit = "events",
                    total = events.size.toDouble(),
                    data = listOf(
                        ChartSlice(event.category, stats.categoryEventCount.toDouble()),
                        ChartSlice(
                            "Other categories",
                            (events.size - stats.categoryEventCount).toDouble()
                        )
                    )
                )
            )
        }

        val venue = EventVenue(
            id = event.venueId,
            name = event.venue,
            navigationNode = event.venueId,
            neighbors = offlineGraph[event.venueId].orEmpty().keys.toList()
        )

        val navigation = nav?.let {
            EventNavigation(
                from = from?.takeIf { f -> f.isNotBlank() } ?: "entrance",
                nodes = it.nodes,
                steps = it.steps,
                totalDistanceM = it.totalDistanceM
            )
        }

        return EventDetails(
            event = event,
            venue = venue,
            statistics = stats,
            charts = charts,
            announcements = scoped,
            navigation = navigation
        )
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    private fun daysUntil(date: String): Int? = runCatching {
        java.time.temporal.ChronoUnit.DAYS.between(
            java.time.LocalDate.now(),
            java.time.LocalDate.parse(date)
        ).toInt()
    }.getOrNull()

    // ---- Navigation (mirrors backend/src/navigation/graph.js) ----

    fun path(from: String, to: String): PathResponse {
        val g = offlineGraph
        if (!g.containsKey(from)) throw IllegalArgumentException("Unknown venue: $from")
        if (!g.containsKey(to)) throw IllegalArgumentException("Unknown venue: $to")

        val dist = mutableMapOf<String, Double>()
        val prev = mutableMapOf<String, String?>()
        val visited = mutableSetOf<String>()

        g.keys.forEach { dist[it] = Double.POSITIVE_INFINITY; prev[it] = null }
        dist[from] = 0.0

        while (visited.size < g.size) {
            var u: String? = null
            var best = Double.POSITIVE_INFINITY
            g.keys.forEach { v ->
                if (v !in visited && dist[v]!! < best) {
                    best = dist[v]!!; u = v
                }
            }
            if (u == null) break
            visited.add(u)
            if (u == to) break
            g[u]!!.forEach { (n, w) ->
                if (n in visited) return@forEach
                val alt = dist[u]!! + w
                if (alt < dist[n]!!) {
                    dist[n] = alt
                    prev[n] = u
                }
            }
        }

        if (dist[to]!!.isInfinite()) throw IllegalStateException("No path between $from and $to")

        val nodeIds = mutableListOf<String>()
        var cur: String? = to
        while (cur != null) {
            nodeIds.add(0, cur)
            cur = prev[cur]
        }

        val steps = nodeIds.mapIndexed { i, id ->
            PathStep(
                node = id,
                instruction = instructionFor(nodeIds, i),
                distanceM = if (i == 0) 0 else dist[id]!!.toInt(),
                name = offlineVenueNames[id].orEmpty()
            )
        }

        return PathResponse(
            nodes = nodeIds.map { PathNode(id = it, name = offlineVenueNames[it].orEmpty()) },
            steps = steps,
            totalDistanceM = dist[to]!!.toInt()
        )
    }

    private fun instructionFor(nodeIds: List<String>, i: Int): String {
        if (nodeIds.isEmpty()) return ""
        if (i == 0) return "Start at ${offlineVenueNames[nodeIds[0]]}"
        if (i == nodeIds.lastIndex) return "Arrive at ${offlineVenueNames[nodeIds[i]]}"

        val cur = nodeIds[i]
        val prevId = nodeIds[i - 1]
        val nextId = nodeIds[i + 1]
        val incoming = bearingFromTo(prevId, cur)
        val outgoing = bearingFromTo(cur, nextId)
        val turn = turnName(incoming, outgoing)
        return "$turn, arrive at ${offlineVenueNames[nextId]}"
    }

    private val ORDER = offlineVenueOrder

    private fun bearingFromTo(a: String, b: String): Int {
        val ai = ORDER.indexOf(a)
        val bi = ORDER.indexOf(b)
        val dx = bi - ai
        val dy = (ai * 7 + bi * 3) % 5 - 2
        return if (Math.abs(dx) >= Math.abs(dy)) if (dx > 0) 1 else 3
        else if (dy > 0) 2 else 0
    }

    private fun turnName(incoming: Int, outgoing: Int): String {
        val delta = ((outgoing - incoming) % 4 + 4) % 4
        if (delta == 1) return "Turn right"
        if (delta == 3) return "Turn left"
        if (delta == 2) return "Go back and head"
        return "Go straight"
    }
}