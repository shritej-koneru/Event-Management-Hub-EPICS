package com.example.eventmanagementhub.ui.theme

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure presentation helpers shared by the screens (unit-tested in HubFormatTest). */
object HubFormat {
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    private fun parse(date: String): LocalDate? =
        runCatching { LocalDate.parse(date.trim()) }.getOrNull()

    /** `Thu 6 Nov`. Unparseable input is returned unchanged. */
    fun day(date: String): String = parse(date)?.format(dayFmt) ?: date

    /** `6 Nov, 14:30` (24 h). Unparseable date falls back to "<date> <time>". */
    fun dateTime(date: String, time: String): String {
        val d = parse(date) ?: return listOf(date, time).filter { it.isNotBlank() }.joinToString(" ")
        val t = time.trim()
        return if (t.isEmpty()) d.format(shortFmt) else "${d.format(shortFmt)}, $t"
    }

    /** `Thu 6 Nov, 14:30` for detail facts. */
    fun dayTime(date: String, time: String): String {
        val d = parse(date) ?: return dateTime(date, time)
        val t = time.trim()
        return if (t.isEmpty()) d.format(dayFmt) else "${d.format(dayFmt)}, $t"
    }

    /** Up to two upper-case initials from a display name; empty when blank. */
    fun initials(name: String?): String =
        name.orEmpty().split(" ").filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.first().uppercase() }

    /** Category dot colour per the design spec; unknown categories use the neutral dot. */
    fun categoryColor(category: String): Color = when (category.trim().lowercase(Locale.ENGLISH)) {
        "tech", "technology" -> CategoryTech
        "cultural", "culture", "arts", "art" -> CategoryCultural
        "entertainment", "music" -> CategoryEntertainment
        "wellness" -> CategoryWellness
        "career" -> CategoryCareer
        "sports", "sport", "competitive" -> CategorySports
        else -> CategoryOther
    }
}
