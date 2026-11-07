package com.example.eventmanagementhub.ui.screens

import com.example.eventmanagementhub.data.model.Announcement
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.SupportTicket
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One day-bucket of the Events list ("Today", "Tomorrow", "Sat 9 Nov"). */
data class DayGroup(val label: String, val events: List<Event>)

/** Upcoming events grouped by day, plus the events that have already ended. */
data class EventSections(val upcoming: List<DayGroup>, val past: List<Event>)

/** Pure presentation rules for Home / Events / Detail / Profile (unit tested in EventLogicTest). */
object EventLogic {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val weekdayFmt = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun parseDate(date: String): LocalDate? = runCatching { LocalDate.parse(date.trim()) }.getOrNull()

    private fun parseTime(time: String): LocalTime =
        runCatching { LocalTime.parse(time.trim()) }.getOrDefault(LocalTime.MIN)

    fun start(e: Event): LocalDateTime? = parseDate(e.date)?.atTime(parseTime(e.time))

    fun end(e: Event): LocalDateTime? = start(e)?.plusMinutes(e.durationMin.coerceAtLeast(0).toLong())

    /** An event is past once it has ended; unparseable dates are never "past" (kept visible). */
    fun isPast(e: Event, now: LocalDateTime): Boolean = end(e)?.isBefore(now) == true

    private fun order(): Comparator<Event> = compareBy<Event>({ it.date }, { it.time }, { it.title })

    /** `Today`, `Tomorrow`, otherwise `Sat 9 Nov`. Unparseable input is returned unchanged. */
    fun dayLabel(date: String, today: LocalDate): String {
        val d = parseDate(date) ?: return date
        return when (d) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> d.format(dayFmt)
        }
    }

    /** Splits into upcoming day groups (soonest first) and past events (most recent first). */
    fun sections(events: List<Event>, now: LocalDateTime): EventSections {
        val (past, upcoming) = events.partition { isPast(it, now) }
        val groups = upcoming.sortedWith(order())
            .groupBy { it.date }
            .map { (date, list) -> DayGroup(dayLabel(date, now.toLocalDate()), list) }
        return EventSections(groups, past.sortedWith(order()).reversed())
    }

    /** Next event the user is registered for that has not ended yet; null when none. */
    fun nextUp(events: List<Event>, registeredIds: Collection<String>, now: LocalDateTime): Event? {
        val ids = registeredIds.toSet()
        return events.filter { it.id in ids && !isPast(it, now) && start(it) != null }
            .minWithOrNull(order())
    }

    /** Up to [max] upcoming events within the next 7 days (today included), soonest first. */
    fun upcomingThisWeek(events: List<Event>, now: LocalDateTime, max: Int = 5): List<Event> {
        val limit = now.toLocalDate().plusDays(7)
        return events.filter { e ->
            val d = parseDate(e.date)
            d != null && !isPast(e, now) && d.isBefore(limit)
        }.sortedWith(order()).take(max)
    }

    /** Distinct non-blank categories, alphabetical; "All" is added by the UI. */
    fun categories(events: List<Event>): List<String> =
        events.map { it.category.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ENGLISH) }
            .sortedBy { it.lowercase(Locale.ENGLISH) }

    fun filterByCategory(events: List<Event>, category: String?): List<Event> =
        if (category == null) events else events.filter { it.category.trim().equals(category, ignoreCase = true) }

    /** Registered share of capacity, 0..100 (capacity <= 0 gives 0). */
    fun capacityPercent(registered: Int, capacity: Int): Int =
        if (capacity <= 0) 0 else (registered.coerceAtLeast(0) * 100 / capacity).coerceIn(0, 100)

    /** Fraction 0..1 for the capacity bar. */
    fun capacityFraction(registered: Int, capacity: Int): Float =
        if (capacity <= 0) 0f else (registered.coerceAtLeast(0).toFloat() / capacity).coerceIn(0f, 1f)

    fun seatsLeft(registered: Int, capacity: Int): Int = (capacity - registered).coerceAtLeast(0)

    /** `Hi, Alice` from a display name; `Hi` when unknown. */
    fun greeting(name: String?): String {
        val first = name.orEmpty().trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (first.isEmpty()) "Hi" else "Hi, $first"
    }

    /** `Sat 7 Nov`. */
    fun todayLabel(today: LocalDate): String = today.format(dayFmt)

    fun weekday(date: String): String = parseDate(date)?.format(weekdayFmt) ?: ""

    fun dayNumber(date: String): String = parseDate(date)?.dayOfMonth?.toString() ?: ""

    /** `14:30 · Main hall`, skipping blanks. */
    fun timeVenue(e: Event): String = listOf(e.time, e.venue).filter { it.isNotBlank() }.joinToString(" · ")

    /** Human duration: `45 min`, `2 h`, `1 h 30 min`; blank for non-positive. */
    fun duration(min: Int): String = when {
        min <= 0 -> ""
        min < 60 -> "$min min"
        min % 60 == 0 -> "${min / 60} h"
        else -> "${min / 60} h ${min % 60} min"
    }

    // ---- relative time / chat helpers ----------------------------------------------

    /** `Just now`, `5 min ago`, `3 h ago`, `Yesterday`, else `6 Nov`. Blank for unparseable input. */
    fun relativeTime(iso: String?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val ms = SupportLogic.parseMillis(iso) ?: return ""
        val then = Instant.ofEpochMilli(ms)
        val mins = Duration.between(then, now).toMinutes()
        if (mins < 1) return "Just now"
        if (mins < 60) return "$mins min ago"
        val thenDay = then.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            thenDay == today -> "${mins / 60} h ago"
            thenDay == today.minusDays(1) -> "Yesterday"
            else -> thenDay.format(shortFmt)
        }
    }

    /** Day separator label ("Today", "Yesterday", "Sat 9 Nov") for a message timestamp. */
    fun separatorLabel(iso: String?, now: Instant, zone: ZoneId = ZoneId.systemDefault()): String? {
        val ms = SupportLogic.parseMillis(iso) ?: return null
        val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(dayFmt)
        }
    }

    /** True when [curIso] starts a new calendar day compared with [prevIso] (or is the first row). */
    fun startsNewDay(prevIso: String?, curIso: String?, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val cur = SupportLogic.parseMillis(curIso) ?: return false
        val prev = SupportLogic.parseMillis(prevIso) ?: return true
        return Instant.ofEpochMilli(cur).atZone(zone).toLocalDate() !=
            Instant.ofEpochMilli(prev).atZone(zone).toLocalDate()
    }

    /** Latest announcement timestamp per event id (event-scoped announcements only). */
    fun latestAnnouncementByEvent(list: List<Announcement>): Map<String, String> =
        list.filter { !it.eventId.isNullOrBlank() && it.at.isNotBlank() }
            .groupBy { it.eventId!! }
            .mapValues { (_, v) -> v.maxBy { SupportLogic.parseMillis(it.at) ?: Long.MIN_VALUE }.at }
}

/** In-memory "last viewed" times for help chats (lost on process death; enough for a dot). */
object ChatReadState {
    private val viewed = ConcurrentHashMap<String, Long>()

    fun markViewed(ticketId: String, nowMs: Long = System.currentTimeMillis()) { viewed[ticketId] = nowMs }

    /** Unread = latest message is from the event team and newer than the user's last view. */
    fun isUnread(lastFromTeam: Boolean, lastMessageMs: Long?, lastViewedMs: Long?): Boolean =
        lastFromTeam && lastMessageMs != null && (lastViewedMs == null || lastMessageMs > lastViewedMs)

    fun isUnread(t: SupportTicket): Boolean {
        val last = t.lastMessage ?: return false
        val ms = SupportLogic.parseMillis(last.createdAt.ifBlank { t.lastMessageAt })
        return isUnread(last.senderRole == "manager", ms, viewed[t.id])
    }
}
