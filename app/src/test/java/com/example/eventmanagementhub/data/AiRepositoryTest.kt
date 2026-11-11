package com.example.eventmanagementhub.data

import com.example.eventmanagementhub.data.api.AiApi
import com.example.eventmanagementhub.data.model.AccessibilitySummary
import com.example.eventmanagementhub.data.model.AiSearchFilters
import com.example.eventmanagementhub.data.model.AiStatus
import com.example.eventmanagementhub.data.model.AssistantRequest
import com.example.eventmanagementhub.data.model.AssistantResponse
import com.example.eventmanagementhub.data.model.EventPrice
import com.example.eventmanagementhub.data.model.RewriteRequest
import com.example.eventmanagementhub.data.model.RewriteResponse
import com.example.eventmanagementhub.data.model.ScheduleRequest
import com.example.eventmanagementhub.data.model.ScheduleResponse
import com.example.eventmanagementhub.data.model.SimplifyResponse
import com.example.eventmanagementhub.data.model.SummaryResponse
import com.example.eventmanagementhub.data.model.TranslateRequest
import com.example.eventmanagementhub.data.model.TranslationResponse
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/** The AI client against a scripted fake: success, server errors, offline, and the "hidden unless enabled" rule. */
class AiRepositoryTest {

    private fun <T> err(code: Int, body: String = ""): Response<T> = Response.error(code, body.toResponseBody(null))

    private class FakeAi(
        var status: () -> Response<AiStatus> = { Response.success(AiStatus(enabled = true)) },
        var assistant: () -> Response<AssistantResponse> = { Response.success(AssistantResponse()) },
        var schedule: (ScheduleRequest) -> Response<ScheduleResponse> = { Response.success(ScheduleResponse()) },
        var translate: () -> Response<TranslationResponse> = { Response.success(TranslationResponse()) }
    ) : AiApi {
        var calls = 0
        var lastSchedule: ScheduleRequest? = null
        override suspend fun status() = status.invoke()
        override suspend fun assistant(body: AssistantRequest): Response<AssistantResponse> { calls++; return assistant.invoke() }
        override suspend fun summary(id: String): Response<SummaryResponse> = Response.success(SummaryResponse())
        override suspend fun translate(id: String, body: TranslateRequest): Response<TranslationResponse> { calls++; return translate.invoke() }
        override suspend fun rewrite(body: RewriteRequest): Response<RewriteResponse> = Response.success(RewriteResponse())
        override suspend fun simplify(id: String): Response<SimplifyResponse> = Response.success(SimplifyResponse())
        override suspend fun schedule(body: ScheduleRequest): Response<ScheduleResponse> { lastSchedule = body; return schedule.invoke(body) }
    }

    @Test fun `status hides AI unless the server positively says enabled`() = runTest {
        assertTrue(AiRepository(FakeAi()).status().enabled)
        assertFalse(AiRepository(FakeAi(status = { Response.success(AiStatus(enabled = false)) })).status().enabled)
        assertFalse(AiRepository(FakeAi(status = { err(500) })).status().enabled)
        assertFalse(AiRepository(FakeAi(status = { throw IOException("offline") })).status().enabled)
    }

    @Test fun `assistant success is passed through untouched`() = runTest {
        val r = AiRepository(FakeAi(assistant = { Response.success(AssistantResponse(available = true, answer = "Free, in Room 101")) }))
        val res = r.ask("is it free?") as Result.Success
        assertEquals("Free, in Room 101", res.data.answer)
    }

    @Test fun `provider failures become calm messages, never raw json`() = runTest {
        val fake = FakeAi(assistant = { err(503, """{"error":"The AI service is unavailable right now","code":"AI_UNAVAILABLE"}""") })
        val res = AiRepository(fake).ask("hi") as Result.Error
        assertEquals("The AI service is not responding. Please try again later.", res.message)
        assertEquals("AI_UNAVAILABLE", res.code)
        assertEquals(1, fake.calls) // no automatic retry: a second attempt is the user's choice
    }

    @Test fun `disabled, rate limited and bad responses each get their own message`() {
        assertEquals("AI helpers are not turned on for this server.", AiLogic.friendlyError("AI_DISABLED", 503, "x"))
        assertEquals("AI is busy. Please wait a minute and try again.", AiLogic.friendlyError(null, 429, "x"))
        assertTrue(AiLogic.friendlyError("AI_BAD_RESPONSE", 502, "x").contains("could not be trusted"))
        assertEquals("That language is not supported.", AiLogic.friendlyError("UNSUPPORTED_LANGUAGE", 400, "x"))
        assertEquals("fallback", AiLogic.friendlyError(null, 500, "fallback"))
    }

    @Test fun `unsupported translation is reported, not shown`() = runTest {
        val fake = FakeAi(translate = { err(400, """{"error":"nope","code":"UNSUPPORTED_LANGUAGE"}""") })
        val res = AiRepository(fake).translate("1", "te") as Result.Error
        assertEquals("That language is not supported.", res.message)
    }

    @Test fun `offline is a plain error`() = runTest {
        val res = AiRepository(FakeAi(assistant = { throw IOException("down") })).ask("hi") as Result.Error
        assertEquals(AiLogic.OFFLINE_MESSAGE, res.message)
        assertNull(res.httpCode)
    }

    @Test fun `schedule check sends only the ids given, deduplicated and capped`() = runTest {
        val fake = FakeAi()
        val ids = (1..40).map { "e$it" } + "e1"
        AiRepository(fake).checkSchedule(ids)
        assertEquals(30, fake.lastSchedule!!.eventIds.size)
        assertEquals(fake.lastSchedule!!.eventIds.distinct(), fake.lastSchedule!!.eventIds)
    }

    @Test fun `server responses with numeric announcement ids decode`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString<SummaryResponse>("""{"announcementId":6,"summary":"x","original":"y","aiGenerated":true,"link":{"announcementId":6}}""")
        assertEquals("6", s.announcementId)
        val t = json.decodeFromString<TranslationResponse>("""{"announcementId":"6","original":"y","translated":"z","language":"hi"}""")
        assertEquals("6", t.announcementId)
    }

    // ---- pure logic ----------------------------------------------------------------------------------------

    @Test fun `chips become explicit filters and unknown stays unset`() {
        val f = AiLogic.filtersFor(setOf(AiLogic.Chip.FREE, AiLogic.Chip.STEP_FREE), LocalDate.parse("2026-11-11"))
        assertEquals(true, f.free)
        assertEquals(listOf("stepFree"), f.accessibility)
        assertNull(f.dateFrom)
        assertTrue(AiLogic.filtersFor(emptySet(), LocalDate.now()).isEmpty())
    }

    @Test fun `this weekend is saturday and sunday, or just today on a sunday`() {
        val wed = AiLogic.filtersFor(setOf(AiLogic.Chip.THIS_WEEKEND), LocalDate.parse("2026-11-11"))
        assertEquals("2026-11-14" to "2026-11-15", wed.dateFrom to wed.dateTo)
        val sun = AiLogic.filtersFor(setOf(AiLogic.Chip.THIS_WEEKEND), LocalDate.parse("2026-11-15"))
        assertEquals("2026-11-15" to "2026-11-15", sun.dateFrom to sun.dateTo)
    }

    @Test fun `search text falls back to the chip words when nothing is typed`() {
        assertEquals("free step-free", AiLogic.queryFor("  ", setOf(AiLogic.Chip.FREE, AiLogic.Chip.STEP_FREE)))
        assertEquals("robots", AiLogic.queryFor("robots", setOf(AiLogic.Chip.FREE)))
    }

    @Test fun `an unstated price is never shown as free`() {
        assertNull(AiLogic.priceLabel(null))
        assertEquals("Free", AiLogic.priceLabel(EventPrice("free")))
        assertEquals("INR 250", AiLogic.priceLabel(EventPrice("paid", 25000, "INR")))
        assertEquals("INR 250.50", AiLogic.priceLabel(EventPrice("paid", 25050, "INR")))
    }

    @Test fun `accessibility text separates offered, not offered and not stated`() {
        val lines = AiLogic.accessibilityLines(AccessibilitySummary(listOf("Step-free access"), listOf("Captions"), listOf("Hearing loop")))
        assertEquals(
            listOf("Offered: Step-free access", "Not offered: Captions", "Not stated by the organizer: Hearing loop"),
            lines
        )
        assertTrue(AiLogic.accessibilityLines(AccessibilitySummary()).isEmpty())
    }

    @Test fun `hidden-event note only appears when something was hidden`() {
        assertNull(AiLogic.unknownNote(0))
        assertTrue(AiLogic.unknownNote(1)!!.startsWith("1 event not shown"))
        assertTrue(AiLogic.unknownNote(5)!!.startsWith("5 events not shown"))
        assertTrue(AiSearchFilters(free = true).isEmpty().not())
    }
}
