package com.example.eventmanagementhub.ui.theme

import com.example.eventmanagementhub.ui.screens.SupportLogic
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class HubFormatTest {

    @Test
    fun dayUsesWeekdayDayMonth() {
        assertEquals("Thu 6 Nov", HubFormat.day("2025-11-06"))
    }

    @Test
    fun dateTimeUses24hClockWithoutWeekday() {
        assertEquals("6 Nov, 14:30", HubFormat.dateTime("2025-11-06", "14:30"))
        assertEquals("15 Oct, 10:00", HubFormat.dateTime("2026-10-15", "10:00"))
    }

    @Test
    fun dayTimeCombinesBoth() {
        assertEquals("Thu 6 Nov, 14:30", HubFormat.dayTime("2025-11-06", "14:30"))
        assertEquals("Thu 6 Nov", HubFormat.dayTime("2025-11-06", ""))
    }

    @Test
    fun unparseableDatesFallBackToRawText() {
        assertEquals("soon", HubFormat.day("soon"))
        assertEquals("soon 10:00", HubFormat.dateTime("soon", "10:00"))
    }

    @Test
    fun initialsTakeUpToTwoWords() {
        assertEquals("AS", HubFormat.initials("alice smith jones"))
        assertEquals("A", HubFormat.initials("alice"))
        assertEquals("", HubFormat.initials("  "))
        assertEquals("", HubFormat.initials(null))
    }

    @Test
    fun categoryColoursFollowTheSpec() {
        assertEquals(CategoryTech, HubFormat.categoryColor("Tech"))
        assertEquals(CategoryCultural, HubFormat.categoryColor("Arts"))
        assertEquals(CategoryEntertainment, HubFormat.categoryColor("entertainment"))
        assertEquals(CategoryWellness, HubFormat.categoryColor("Wellness"))
        assertEquals(CategoryCareer, HubFormat.categoryColor("Career"))
        assertEquals(CategorySports, HubFormat.categoryColor("Sports"))
        assertEquals(CategoryOther, HubFormat.categoryColor("Robotics"))
        assertEquals(CategoryOther, HubFormat.categoryColor(""))
    }

    @Test
    fun supportTimestampsAreDayMonthTwentyFourHour() {
        assertEquals(
            "6 Nov, 14:30",
            SupportLogic.formatTime("2025-11-06T14:30:00Z", ZoneOffset.UTC)
        )
    }
}
