package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.AiLogic
import com.example.eventmanagementhub.data.AiRepository
import com.example.eventmanagementhub.data.Result
import com.example.eventmanagementhub.data.model.AiStatus
import com.example.eventmanagementhub.data.model.AssistantResponse
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.data.model.ScheduleResponse
import com.example.eventmanagementhub.data.model.SimplifyResponse
import com.example.eventmanagementhub.data.model.SummaryResponse
import com.example.eventmanagementhub.data.model.TranslationResponse
import com.example.eventmanagementhub.ui.components.ChipKind
import com.example.eventmanagementhub.ui.components.HubCard
import com.example.eventmanagementhub.ui.components.HubChip
import com.example.eventmanagementhub.ui.components.HubFilterChip
import com.example.eventmanagementhub.ui.components.HubTopBar
import com.example.eventmanagementhub.ui.components.LabeledField
import com.example.eventmanagementhub.ui.components.PrimaryButton
import com.example.eventmanagementhub.ui.components.SecondaryButton
import com.example.eventmanagementhub.ui.components.hubFieldColors
import com.example.eventmanagementhub.ui.theme.Ink
import com.example.eventmanagementhub.ui.theme.Ink2
import com.example.eventmanagementhub.ui.theme.Ink3
import com.example.eventmanagementhub.ui.theme.Paper
import kotlinx.coroutines.launch

/**
 * Every AI helper in the app lives here. Two rules hold for all of them:
 *  1. They are hidden unless the server says AI is on ([rememberAiStatus]); the normal screens work exactly as before.
 *  2. They only SUGGEST. Originals stay visible, nothing is sent, saved or published for the user.
 */

/** Asks the server once per screen whether AI is on. `enabled=false` until it positively answers. */
@Composable
fun rememberAiStatus(repository: AiRepository = remember { AiRepository() }): AiStatus {
    var status by remember { mutableStateOf(AiStatus()) }
    LaunchedEffect(Unit) { status = repository.status() }
    return status
}

@Composable
private fun AiNote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ink3)

@Composable
private fun AiError(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai-error"))

/* ------------------------------------------------------------------------------------------- event details */

/**
 * Price line (always) plus, when AI is on, "Explain simply" and "Check my schedule".
 * [savedIds] are the events this app knows the user saved; the schedule check works from that list only.
 */
@Composable
fun AiEventHelp(event: Event, savedIds: List<String>, repository: AiRepository = remember { AiRepository() }) {
    val status = rememberAiStatus(repository)
    val scope = rememberCoroutineScope()
    var simple by remember { mutableStateOf<SimplifyResponse?>(null) }
    var schedule by remember { mutableStateOf<ScheduleResponse?>(null) }
    var scheduleNote by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().testTag("ai-event-help")) {
        Text(
            "Price: " + (AiLogic.priceLabel(event.price) ?: "Not stated by the organizer"),
            style = MaterialTheme.typography.bodyMedium,
            color = Ink2,
            modifier = Modifier.testTag("price-line")
        )
        if (!status.enabled) return@Column

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = "Explain simply",
                enabled = !busy && event.description.isNotBlank(),
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        when (val r = repository.simplify(event.id)) {
                            is Result.Success -> simple = r.data
                            is Result.Error -> error = r.message
                        }
                        busy = false
                    }
                }
            )
            SecondaryButton(
                text = "Check my schedule",
                enabled = !busy,
                onClick = {
                    error = null; scheduleNote = null; schedule = null
                    if (savedIds.isEmpty()) {
                        scheduleNote = "You haven't saved any events on this device yet, so there is nothing to compare."
                        return@SecondaryButton
                    }
                    busy = true
                    scope.launch {
                        when (val r = repository.checkSchedule(savedIds)) {
                            is Result.Success -> schedule = r.data
                            is Result.Error -> error = r.message
                        }
                        busy = false
                    }
                }
            )
        }
        error?.let { Spacer(Modifier.height(8.dp)); AiError(it) }
        scheduleNote?.let { Spacer(Modifier.height(8.dp)); AiNote(it) }

        simple?.let { s ->
            Spacer(Modifier.height(12.dp))
            HubCard(Modifier.fillMaxWidth().testTag("ai-simple")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HubChip("AI-written, check the original below", ChipKind.Info)
                    Text(s.plain, style = MaterialTheme.typography.bodyMedium, color = Ink)
                    AiAccessibility(s)
                    Text("Original", style = MaterialTheme.typography.labelSmall, color = Ink3)
                    Text(s.original, style = MaterialTheme.typography.bodySmall, color = Ink2)
                }
            }
        }

        schedule?.let { s ->
            Spacer(Modifier.height(12.dp))
            HubCard(Modifier.fillMaxWidth().testTag("ai-schedule")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.hasConflicts) {
                        Text("No overlaps among your ${s.basedOn.eventCount} saved events.", style = MaterialTheme.typography.bodyMedium, color = Ink)
                    }
                    s.conflicts.forEach { c ->
                        Text(
                            "${c.a.title} overlaps ${c.b.title} by ${c.overlapMinutes} min (${c.a.date}).",
                            style = MaterialTheme.typography.bodyMedium, color = Ink
                        )
                    }
                    s.alternatives.forEach { alt ->
                        if (alt.options.isNotEmpty()) {
                            Text(
                                "Instead of ${s.conflicts.firstOrNull { it.b.id == alt.forEventId }?.b?.title ?: "that event"}: " +
                                    alt.options.joinToString { "${it.title} (${it.date} ${it.time})" },
                                style = MaterialTheme.typography.bodySmall, color = Ink2
                            )
                        }
                    }
                    AiNote(s.limitation)
                }
            }
        }
    }
}

@Composable
private fun AiAccessibility(s: SimplifyResponse) {
    val lines = AiLogic.accessibilityLines(s.accessibility)
    if (lines.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Access", style = MaterialTheme.typography.labelSmall, color = Ink3)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink2) }
    }
}

/* ------------------------------------------------------------------------------------------- announcements */

/** Summarize / Translate for one announcement. The original text is shown by the caller and is never replaced. */
@Composable
fun AiAnnouncementTools(announcementId: Long, repository: AiRepository = remember { AiRepository() }) {
    val status = rememberAiStatus(repository)
    if (!status.enabled) return
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<SummaryResponse?>(null) }
    var translation by remember { mutableStateOf<TranslationResponse?>(null) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val id = announcementId.toString()

    Column(Modifier.fillMaxWidth().testTag("ai-announcement-$id")) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(text = "Summarize", enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    when (val r = repository.summarize(id)) {
                        is Result.Success -> summary = r.data
                        is Result.Error -> error = r.message
                    }
                    busy = false
                }
            })
            if (status.languages.isNotEmpty()) {
                SecondaryButton(text = "Translate", enabled = !busy, onClick = { picking = !picking })
            }
        }
        if (picking) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(status.languages, key = { it.code }) { lang ->
                    HubFilterChip(lang.name, selected = translation?.language == lang.code, onClick = {
                        busy = true; error = null
                        scope.launch {
                            when (val r = repository.translate(id, lang.code)) {
                                is Result.Success -> { translation = r.data; picking = false }
                                is Result.Error -> error = r.message
                            }
                            busy = false
                        }
                    })
                }
            }
        }
        error?.let { Spacer(Modifier.height(6.dp)); AiError(it) }
        summary?.let {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.testTag("ai-summary")) {
                AiNote(if (it.aiGenerated) "AI summary" else "Short version (not AI)")
                Text(it.summary, style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
        translation?.let {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.testTag("ai-translation")) {
                AiNote("AI translation (${status.languages.firstOrNull { l -> l.code == it.language }?.name ?: it.language}). The original is above.")
                Text(it.translated, style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------- help desk */

/**
 * "Suggest rewrite" under a help-chat text box. The suggestion is only offered: the user taps "Use this" to put it in the
 * box, then reviews it and sends it themselves. This composable never sends anything.
 */
@Composable
fun AiRewriteSuggestion(
    text: String,
    kind: String,
    onUse: (String) -> Unit,
    repository: AiRepository = remember { AiRepository() }
) {
    val status = rememberAiStatus(repository)
    if (!status.enabled) return
    val scope = rememberCoroutineScope()
    var suggestion by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().testTag("ai-rewrite")) {
        SecondaryButton(
            text = if (busy) "Thinking..." else "Suggest rewrite",
            enabled = !busy && text.isNotBlank(),
            onClick = {
                busy = true; error = null
                scope.launch {
                    when (val r = repository.rewrite(text, kind)) {
                        is Result.Success -> suggestion = r.data.suggestion
                        is Result.Error -> error = r.message
                    }
                    busy = false
                }
            }
        )
        error?.let { Spacer(Modifier.height(6.dp)); AiError(it) }
        suggestion?.let { s ->
            Spacer(Modifier.height(8.dp))
            HubCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AiNote("Suggestion. Nothing is sent until you press send.")
                    Text(s, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.testTag("ai-rewrite-text"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(text = "Use this", onClick = { onUse(s); suggestion = null })
                        SecondaryButton(text = "Dismiss", onClick = { suggestion = null })
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------------------------------- assistant */

/** Campus assistant: answers only from current events, venues, announcements and app help, and lists its sources. */
@Composable
fun AiAssistantScreen(
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    repository: AiRepository = remember { AiRepository() }
) {
    val status = rememberAiStatus(repository)
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<AssistantResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Paper)) {
        HubTopBar(title = "Campus assistant", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!status.enabled) {
                AiNote("The assistant is not available right now. Everything else in the app works as usual.")
                return@Column
            }
            LabeledField("Your question") {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(300) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("assistant-question"),
                    colors = hubFieldColors(),
                    enabled = !busy
                )
            }
            PrimaryButton(
                text = "Ask",
                loading = busy,
                enabled = question.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; error = null; reply = null
                    scope.launch {
                        when (val r = repository.ask(question)) {
                            is Result.Success -> reply = r.data
                            is Result.Error -> error = r.message
                        }
                        busy = false
                    }
                }
            )
            error?.let { AiError(it) }
            reply?.let { r ->
                HubCard(Modifier.fillMaxWidth().testTag("assistant-reply")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            r.available && r.answer != null -> Text(r.answer, style = MaterialTheme.typography.bodyMedium, color = Ink)
                            else -> Text(
                                r.message ?: "I don't have information about that.",
                                style = MaterialTheme.typography.bodyMedium, color = Ink
                            )
                        }
                        if (r.sources.isNotEmpty()) {
                            Text(if (r.available) "Sources" else "Related", style = MaterialTheme.typography.labelSmall, color = Ink3)
                            r.sources.forEach { s ->
                                val eventId = if (s.type == "event") s.refId else s.eventId
                                if (eventId != null) {
                                    SecondaryButton(text = s.title, onClick = { onOpenEvent(eventId) })
                                } else {
                                    Text("• ${s.title}", style = MaterialTheme.typography.bodySmall, color = Ink2)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
