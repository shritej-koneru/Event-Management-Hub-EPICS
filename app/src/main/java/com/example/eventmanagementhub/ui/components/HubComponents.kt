package com.example.eventmanagementhub.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.data.model.Event
import com.example.eventmanagementhub.ui.theme.*

// Shared "Campus ops" building blocks (docs/DESIGN_SYSTEM.md). Flat, hairline-bordered,
// one accent. No elevation, gradients or emoji anywhere in here.

val ControlShape = RoundedCornerShape(6.dp)
val CardShape = RoundedCornerShape(8.dp)
val Hairline = BorderStroke(1.dp, Line)
val HairlineStrong = BorderStroke(1.dp, LineStrong)

/** Flat bordered panel (replaces elevated Cards). */
@Composable
fun HubCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = CardShape, color = HubSurface, border = Hairline) {
            Column(content = content)
        }
    } else {
        Surface(modifier = modifier, shape = CardShape, color = HubSurface, border = Hairline) {
            Column(content = content)
        }
    }
}

/** Hairline separator inset from the left by [inset] (list rows use 16dp). */
@Composable
fun HubDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    HorizontalDivider(modifier.padding(start = inset), thickness = 1.dp, color = Line)
}

/** Overline section label (table headers / section labels only). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = HubTypography.overline, color = Ink3, modifier = modifier)
}

/** Page header for the tab roots: Title + one-line description. No hero banners. */
@Composable
fun PageHeader(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) trailing()
    }
}

/** Flat top bar: Surface with a bottom hairline. */
@Composable
fun HubTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth().background(HubSurface)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (actions != null) actions()
        }
        HubDivider()
    }
}

enum class ChipKind(val fg: Color, val bg: Color) {
    Success(com.example.eventmanagementhub.ui.theme.Success, SuccessSoft),
    Warning(com.example.eventmanagementhub.ui.theme.Warning, WarningSoft),
    Danger(com.example.eventmanagementhub.ui.theme.Danger, DangerSoft),
    Info(com.example.eventmanagementhub.ui.theme.Info, InfoSoft),
    Accent(com.example.eventmanagementhub.ui.theme.Accent, AccentSoft),
    Neutral(Ink2, SurfaceSunken)
}

/** Status chip: soft background + strong text of the same colour, 6dp radius. */
@Composable
fun HubChip(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    Surface(shape = ControlShape, color = kind.bg, modifier = modifier) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = kind.fg,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/** Small colour dot + category text (replaces the emoji event icon). */
@Composable
fun CategoryTag(category: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(HubFormat.categoryColor(category)))
        Spacer(Modifier.width(6.dp))
        Text(category.ifBlank { "Other" }, style = MaterialTheme.typography.bodySmall, color = Ink2, maxLines = 1)
    }
}

/** Circle on SurfaceSunken with initials (32dp by default). */
@Composable
fun InitialsAvatar(name: String?, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(SurfaceSunken),
        contentAlignment = Alignment.Center
    ) {
        Text(
            HubFormat.initials(name).ifBlank { "U" },
            style = if (size >= 48.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall,
            color = Ink2
        )
    }
}

/** Generic list row: 72dp, leading slot, title Body-strong, meta Small Ink3, inset hairline. */
@Composable
fun HubListRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    meta: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = {
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Ink3, modifier = Modifier.size(20.dp))
    },
    showDivider: Boolean = true
) {
    Column(modifier.fillMaxWidth().background(HubSurface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta != null) meta()
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        if (showDivider) HubDivider(inset = 16.dp)
    }
}

@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = Ink3, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, modifier = modifier
    )
}

/** Event list row: category dot + category/date/venue meta. */
@Composable
fun EventRow(event: Event, onClick: () -> Unit, modifier: Modifier = Modifier, showDivider: Boolean = true) {
    HubListRow(
        title = event.title,
        modifier = modifier,
        onClick = onClick,
        showDivider = showDivider,
        leading = {
            Box(Modifier.size(10.dp).clip(CircleShape).background(HubFormat.categoryColor(event.category)))
        },
        meta = {
            MetaText(
                listOf(event.category, HubFormat.dateTime(event.date, event.time), event.venue)
                    .filter { it.isNotBlank() }.joinToString(" · ")
            )
        }
    )
}

// ---- buttons ---------------------------------------------------------------

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.heightIn(min = 48.dp),
        shape = ControlShape,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent, contentColor = OnAccent,
            disabledContainerColor = if (loading) Accent else SurfaceSunken,
            disabledContentColor = if (loading) OnAccent else Ink3
        )
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = OnAccent, strokeWidth = 2.dp)
        } else {
            if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (trailingIcon != null) { Spacer(Modifier.width(8.dp)); trailingIcon() }
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = ControlShape,
        border = HairlineStrong,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = HubSurface,
            contentColor = if (danger) Danger else Ink,
            disabledContainerColor = HubSurface, disabledContentColor = Ink3
        )
    ) {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Quiet button: text-only accent, 48dp target. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = ControlShape,
        colors = ButtonDefaults.textButtonColors(contentColor = Accent, disabledContentColor = Ink3)
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// ---- inputs ----------------------------------------------------------------

@Composable
fun hubFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = HubSurface,
    unfocusedContainerColor = HubSurface,
    disabledContainerColor = SurfaceSunken,
    focusedBorderColor = Accent,
    unfocusedBorderColor = LineStrong,
    disabledBorderColor = Line,
    errorBorderColor = Danger,
    focusedTextColor = Ink,
    unfocusedTextColor = Ink,
    disabledTextColor = Ink3,
    focusedPlaceholderColor = Ink3,
    unfocusedPlaceholderColor = Ink3,
    cursorColor = Accent
)

/** Label above the field (Small 500 Ink2), then the bordered input. */
@Composable
fun LabeledField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = Ink2)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** Banner for notices/errors: soft status background, 6dp radius. */
@Composable
fun NoticeBanner(text: String, kind: ChipKind, modifier: Modifier = Modifier) {
    Surface(shape = ControlShape, color = kind.bg, modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = kind.fg,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
    }
}

/** Filter chip in spec style: 6dp radius, hairline, accent-soft when selected. */
@Composable
fun HubFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) },
        shape = ControlShape,
        modifier = modifier.heightIn(min = 40.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = HubSurface,
            labelColor = Ink2,
            selectedContainerColor = AccentSoft,
            selectedLabelColor = Accent
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = LineStrong, selectedBorderColor = Accent
        )
    )
}
