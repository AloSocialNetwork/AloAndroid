package com.alo.android

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The settings rows of the website (web/css/base.css .group/.cell, views.css .settings-me): cards of 22 corners
// 20 from the screen edge, uppercase 13 grey group titles, 52 rows with 16 insets, 30 colour tiles of 8 corners,
// 17 titles over 14 grey subtitles, hairlines from 54 (16 without a tile), a faint chevron, the iOS 26 switch.

internal val settingsRowInset = 16.dp
/** The website's --gutter: cards keep this distance from the screen edges. */
internal val settingsPageGutter = 20.dp
/** Where a hairline starts under a row with a tile (16 inset + 30 tile + 8). */
internal val settingsTileDividerStart = 54.dp

/** The website's --text-3: chevrons and the faintest text. */
internal val AloSettingsFaint: Color
    @Composable get() = AloNativeAppMuted.copy(alpha = AloNativeAppMuted.alpha * 0.68f)

private val isDarkSettings: Boolean
    @Composable get() = AloNativeAppBackground.luminance() < 0.3f

/** A card's rows each end with a hairline; the card cuts the last one off, as iOS lists only separate. */
@Composable
internal fun SettingsDivider(start: Dp = settingsRowInset) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
}

private fun Modifier.trimLastHairline(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trim = 0.5.dp.roundToPx().coerceAtLeast(1)
    layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, 0) }
}

@Composable
internal fun SettingsSection(
    title: String,
    /** Kept for older callers: every group title now has the website's one size. */
    compactTitle: Boolean = false,
    /** The website's .group-note: grey text under the card. */
    note: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Text(
                text = title.uppercase(),
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp, top = 4.dp),
                color = AloNativeAppMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.26.sp
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(AloSquircle(22.dp)).background(AloNativeAppSurface).trimLastHairline(),
            content = content
        )
        if (note.isNotBlank()) {
            Text(
                text = note,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                color = AloNativeAppMuted,
                fontSize = 13.sp,
                lineHeight = 17.5.sp
            )
        }
    }
}

/** The website's .cell-icon: a 30 tile of 8 corners in the row's colour, a white 18 symbol. */
@Composable
internal fun SettingsIconTile(icon: ImageVector, tint: Color, brush: Brush? = null) {
    Box(
        Modifier.size(30.dp).clip(AloSquircle(8.dp)).then(if (brush != null) Modifier.background(brush) else Modifier.background(tint)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * The website's .cell: an optional tile, a 17 title over a 14 grey subtitle, an optional value and a faint chevron.
 * Rows of one card share the hairline between them, from 54 under a tile and from 16 without.
 */
@Composable
internal fun SettingsCell(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    iconTint: Color = AloNativeAppAccent,
    iconBrush: Brush? = null,
    /** Grey text before the chevron (the current theme). */
    detail: String? = null,
    /** Larger text at the end (the favourite reaction's emoji). */
    value: String? = null,
    titleColor: Color = AloNativeAppText,
    centered: Boolean = false,
    showsChevron: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)?
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                .heightIn(min = 52.dp)
                .padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) SettingsIconTile(icon, iconTint, iconBrush)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    color = if (enabled) titleColor else AloNativeAppMuted,
                    fontSize = 17.sp,
                    fontWeight = if (centered) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = AloNativeAppMuted, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (value != null) Text(value, fontSize = 22.sp)
            if (detail != null) Text(detail, color = AloNativeAppMuted, fontSize = 16.sp)
            if (showsChevron && onClick != null && !centered) SettingsChevron()
        }
        SettingsDivider(if (icon != null) settingsTileDividerStart else settingsRowInset)
    }
}

/** The root page's rows, as the website's settings: a colour tile, a title, a grey subtitle and a chevron. */
@Composable
internal fun RootSettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    iconTint: Color = AloNativeAppAccent,
    subtitle: String = "",
    /** Shown at the right end, as «Любимая реакция» shows its emoji. */
    value: String? = null,
    /** Grey text before the chevron, as «Оформление» shows the current theme. */
    detail: String? = null,
    iconBrush: Brush? = null
) {
    SettingsCell(
        title = title, subtitle = subtitle, icon = icon, iconTint = iconTint, iconBrush = iconBrush,
        detail = detail, value = value, onClick = onClick
    )
}

/** iOS's infoRow: a title and grey text on their own card. */
@Composable
internal fun SettingsInfoRow(
    title: String,
    value: String
) {
    Column(
        Modifier.fillMaxWidth().clip(AloSquircle(22.dp)).background(AloNativeAppSurface).padding(horizontal = settingsRowInset, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, color = AloNativeAppText, fontSize = 17.sp)
        Text(value.ifBlank { "—" }, color = AloNativeAppMuted, fontSize = 14.sp)
    }
}

@Composable
internal fun SettingsChevron(size: Int = 20) {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloSettingsFaint, modifier = Modifier.size(size.dp))
}

/** A tile, a title, a grey subtitle and a chevron: the website's settings row. */
@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    iconTint: Color = AloNativeAppAccent,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    SettingsCell(title = title, subtitle = subtitle, icon = icon, iconTint = iconTint, enabled = enabled, onClick = onClick)
}

/** The security page's rows, as on the website: a colour tile, title, subtitle, an optional badge and a chevron. */
@Composable
internal fun SettingsSecurityRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: Color = AloNativeAppAccent,
    showsChevron: Boolean = true,
    trailing: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
                .heightIn(min = 52.dp)
                .padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconTile(icon, tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = if (enabled) AloNativeAppText else AloNativeAppMuted, fontSize = 17.sp)
                if (subtitle.isNotEmpty()) Text(subtitle, color = AloNativeAppMuted, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Text(trailing, color = AloNativeAppAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (showsChevron) SettingsChevron()
        }
        SettingsDivider(settingsTileDividerStart)
    }
}

/** iOS's plain accent button inside a card, such as «Настроить ключ доступа». */
@Composable
internal fun SettingsTextButtonRow(title: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = settingsRowInset),
            contentAlignment = if (destructive) Alignment.Center else Alignment.CenterStart
        ) {
            Text(
                title,
                color = if (!enabled) AloNativeAppMuted else if (destructive) Color(0xFFFF4D5F) else AloNativeAppAccent,
                fontSize = 17.sp,
                fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        SettingsDivider()
    }
}

/** A title on the left and a grey value on the right, inside a card (iOS's LabeledContent). */
@Composable
internal fun SettingsInfoValueRow(title: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = AloNativeAppText, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(value, color = AloNativeAppMuted, fontSize = 17.sp)
        }
        SettingsDivider()
    }
}

/** Grey text inside a card, such as «Сеанс появится здесь…». */
@Composable
internal fun SettingsNoteRow(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, color = AloNativeAppMuted, fontSize = 15.sp, modifier = Modifier.padding(horizontal = settingsRowInset, vertical = 15.dp))
        SettingsDivider()
    }
}

/** iOS 26's switch: a 63×28 track with a 36×24 capsule thumb. */
@Composable
internal fun AloSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onColor: Color = Color(0xFF30D158)
) {
    val dark = isDarkSettings
    val track by animateColorAsState(
        if (checked) onColor else if (dark) Color(0xFF5A5A5F) else Color(0xFFE9E9EA),
        label = "switchTrack"
    )
    val thumbOffset by animateDpAsState(if (checked) 25.dp else 2.dp, spring(dampingRatio = 0.72f, stiffness = 600f), label = "switchThumb")
    Box(
        modifier
            .size(63.dp, 28.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (enabled) track else track.copy(alpha = 0.5f))
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                } else Modifier
            )
    ) {
        Box(
            Modifier
                .offset(x = thumbOffset, y = 2.dp)
                .size(36.dp, 24.dp)
                .shadow(2.dp, RoundedCornerShape(percent = 50))
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White)
        )
    }
}

/** iOS's settingToggle: an optional 30pt tile, a 16pt title over a 13pt subtitle, and the switch. */
@Composable
internal fun SettingsToggleRow(
    icon: ImageVector? = null,
    iconTint: Color = AloNativeAppAccent,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = 52.dp)
                .padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) SettingsIconTile(icon, iconTint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = AloNativeAppText, fontSize = 17.sp)
                if (subtitle.isNotEmpty()) Text(subtitle, color = AloNativeAppMuted, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            AloSwitch(checked = checked, onCheckedChange = null)
        }
        SettingsDivider(if (icon != null) settingsTileDividerStart else settingsRowInset)
    }
}

/** iOS's privacy Menu: a title (and subtitle), the chosen value and ⌃⌄; the choices in a menu. */
@Composable
internal fun SettingsMenuRow(
    title: String,
    subtitle: String = "",
    options: List<Pair<String, String>>,
    selected: String,
    compact: Boolean = subtitle.isEmpty(),
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = settingsRowInset, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = AloNativeAppText, fontSize = 17.sp)
                    if (subtitle.isNotEmpty()) Text(subtitle, color = AloNativeAppMuted, fontSize = 14.sp, maxLines = 3)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        options.firstOrNull { it.first == selected }?.second ?: "Все",
                        color = AloNativeAppMuted, fontSize = 17.sp, textAlign = TextAlign.End,
                        modifier = Modifier.widthInMax(150)
                    )
                    Icon(Icons.Rounded.UnfoldMore, null, tint = AloNativeAppMuted, modifier = Modifier.size(18.dp))
                }
            }
            AloDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = AloNativeAppSurface,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, color = AloNativeAppText, fontSize = 16.sp) },
                        leadingIcon = {
                            Box(Modifier.size(20.dp)) {
                                if (value == selected) Icon(Icons.Rounded.Check, null, tint = AloNativeAppText, modifier = Modifier.size(20.dp))
                            }
                        },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        }
                    )
                }
            }
        }
        SettingsDivider()
    }
}

private fun Modifier.widthInMax(max: Int): Modifier = layout { measurable, constraints ->
    // Never below minWidth: constraints with maxWidth < minWidth throw.
    val placeable = measurable.measure(constraints.copy(maxWidth = minOf(constraints.maxWidth, max.dp.roundToPx()).coerceAtLeast(constraints.minWidth)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

@Composable
internal fun SettingsChoiceCard(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    SettingsMenuRow(title = title, options = options, selected = selected, onSelect = onSelect)
}

/** The website's privacy choice: the group title, every option as a row with a check on the chosen one, a note under it. */
@Composable
internal fun ReferencePrivacyChoiceCard(
    title: String,
    subtitle: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    SettingsSection(title = title, note = subtitle) {
        options.forEach { (value, label) -> SettingsCheckRow(label, checked = value == selected) { onSelect(value) } }
    }
}

/** The website's .cell.choice: a title and an accent check when chosen. */
@Composable
internal fun SettingsCheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.RadioButton, onValueChange = { onClick() })
                .heightIn(min = 52.dp)
                .padding(horizontal = settingsRowInset),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = AloNativeAppText, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (checked) Icon(Icons.Rounded.Check, null, tint = AloNativeAppAccent, modifier = Modifier.size(20.dp))
        }
        SettingsDivider()
    }
}

@Composable
internal fun ReferencePrivacyToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsSection(title = "") {
        SettingsToggleRow(title = title, subtitle = subtitle, checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** iOS's blacklistRow: a 23pt symbol, an 18pt title and a chevron on its own card. */
@Composable
internal fun ReferencePrivacyActionCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    // The website's «Чёрный список»: a plain row on a card of its own.
    SettingsSection(title = "") {
        SettingsCell(title = title, onClick = onClick)
    }
}

/** The web's «Тема» row: three cards with a small picture of each theme; the chosen one gets an accent ring. */
@Composable
internal fun SettingsThemeCards(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            val ring by animateColorAsState(if (on) AloNativeAppAccent else Color.Transparent, label = "themeRing")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(AloSquircle(18.dp))
                    .background(AloNativeAppSurface)
                    .border(2.dp, ring, AloSquircle(18.dp))
                    .toggleable(value = on, role = Role.RadioButton, onValueChange = { onSelect(id) })
                    .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.2f)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, AloNativeAppSeparator, RoundedCornerShape(12.dp))
                        .drawBehind { drawThemeArt(id) }
                )
                Text(
                    label,
                    color = AloNativeAppText,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The web's .theme-art: a page over a bar for dark and light, and a diagonal half-and-half for the system theme. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThemeArt(id: String) {
    when (id) {
        AloThemeMode.Light.name -> {
            drawRect(Color.White)
            drawRect(Color(0xFFE5ECF6), topLeft = Offset(0f, size.height * 0.6f), size = Size(size.width, size.height * 0.4f))
        }
        AloThemeMode.Dark.name -> {
            drawRect(Color.Black)
            drawRect(Color(0xFF1E2128), topLeft = Offset(0f, size.height * 0.6f), size = Size(size.width, size.height * 0.4f))
        }
        else -> {
            drawRect(Color.Black)
            val tilt = size.height * 0.09f
            drawPath(
                Path().apply {
                    moveTo(0f, 0f); lineTo(size.width / 2 + tilt, 0f); lineTo(size.width / 2 - tilt, size.height); lineTo(0f, size.height); close()
                },
                Color.White
            )
        }
    }
}
