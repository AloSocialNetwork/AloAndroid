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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The settings rows of iOS's ProfileView: cards of 18pt corners with 14pt insets, 16pt titles over
// 13pt grey subtitles, tinted icon tiles, menus with ⌃⌄ and the iOS 26 switch.

internal val settingsRowInset = 14.dp

private val isDarkSettings: Boolean
    @Composable get() = AloNativeAppBackground.luminance() < 0.3f

/** A card's rows each end with a hairline; the card cuts the last one off, as iOS lists only separate. */
@Composable
internal fun SettingsDivider() {
    Box(Modifier.padding(horizontal = settingsRowInset).fillMaxWidth().height(0.5.dp).background(AloNativeAppSeparator))
}

private fun Modifier.trimLastHairline(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trim = 0.5.dp.roundToPx().coerceAtLeast(1)
    layout(placeable.width, (placeable.height - trim).coerceAtLeast(0)) { placeable.place(0, 0) }
}

@Composable
internal fun SettingsSection(
    title: String,
    /** The security page's smaller 14pt card titles. */
    compactTitle: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title.isNotBlank()) {
            Text(
                text = title,
                modifier = Modifier.padding(start = 2.dp),
                color = AloNativeAppMuted,
                fontSize = if (compactTitle) 14.sp else 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(AloSquircle(18.dp)).background(AloNativeAppSurface).trimLastHairline(),
            content = content
        )
    }
}

/** The root page's rows: a plain 17pt symbol and title, no chevron. */
@Composable
internal fun RootSettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    /** Shown at the right end, as iOS's «Любимая реакция» shows its emoji. */
    value: String? = null,
    /** Grey text before the chevron, as «Оформление» shows the current theme. */
    detail: String? = null
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = settingsRowInset),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = AloNativeAppText, modifier = Modifier.size(20.dp))
            }
            Text(title, color = AloNativeAppText, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (value != null) Text(value, fontSize = 24.sp)
            if (detail != null) Text(detail, color = AloNativeAppMuted, fontSize = 16.sp)
        }
        SettingsDivider()
    }
}

/** iOS's infoRow: a title and grey text on their own card. */
@Composable
internal fun SettingsInfoRow(
    title: String,
    value: String
) {
    Column(
        Modifier.fillMaxWidth().clip(AloSquircle(16.dp)).background(AloNativeAppSurface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(value.ifBlank { "—" }, color = AloNativeAppMuted, fontSize = 13.5.sp)
    }
}

@Composable
internal fun SettingsChevron(size: Int = 18) {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = AloNativeAppMuted, modifier = Modifier.size(size.dp))
}

/** iOS's settingsActionRow: a 34pt tinted tile, a 16pt title, a grey subtitle and a chevron. */
@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    iconTint: Color = AloNativeAppAccent,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .heightIn(min = 66.dp)
                .padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).clip(AloSquircle(10.dp)).background(iconTint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SettingsChevron()
        }
        SettingsDivider()
    }
}

/** iOS's AloSecurityRow: the symbol on a faint tile of its own colour. */
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
                .heightIn(min = 66.dp)
                .padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).clip(AloSquircle(10.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = AloNativeAppText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotEmpty()) Text(subtitle, color = AloNativeAppMuted, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Text(trailing, color = AloNativeAppAccent, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            if (showsChevron) SettingsChevron(17)
        }
        SettingsDivider()
    }
}

/** iOS's plain accent button inside a card, such as «Настроить ключ доступа». */
@Composable
internal fun SettingsTextButtonRow(title: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 42.dp).padding(horizontal = settingsRowInset),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                title,
                color = if (!enabled) AloNativeAppMuted else if (destructive) Color(0xFFFF5C68) else AloNativeAppAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
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
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = AloNativeAppText, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(value, color = AloNativeAppMuted, fontSize = 16.sp)
        }
        SettingsDivider()
    }
}

/** Grey text inside a card, such as «Сеанс появится здесь…». */
@Composable
internal fun SettingsNoteRow(text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(text, color = AloNativeAppMuted, fontSize = 13.5.sp, modifier = Modifier.padding(horizontal = settingsRowInset, vertical = 11.dp))
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
                .heightIn(min = if (icon == null) 62.dp else 68.dp)
                .padding(horizontal = settingsRowInset, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(Modifier.size(30.dp).clip(AloSquircle(8.dp)).background(iconTint), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(17.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = AloNativeAppText, fontSize = 16.sp)
                if (subtitle.isNotEmpty()) Text(subtitle, color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(3.dp))
            AloSwitch(checked = checked, onCheckedChange = null)
        }
        SettingsDivider()
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
                    .heightIn(min = if (compact) 56.dp else 64.dp)
                    .padding(horizontal = settingsRowInset, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = AloNativeAppText, fontSize = if (compact) 16.sp else 17.sp)
                    if (subtitle.isNotEmpty()) Text(subtitle, color = AloNativeAppMuted, fontSize = 13.sp, maxLines = 2)
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

/** iOS's privacySelectionCard: a menu row on its own card. */
@Composable
internal fun ReferencePrivacyChoiceCard(
    title: String,
    subtitle: String,
    selected: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    SettingsSection(title = "") {
        SettingsMenuRow(title = title, subtitle = subtitle, options = options, selected = selected, compact = false, onSelect = onSelect)
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
    SettingsSection(title = "") {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 62.dp).padding(horizontal = settingsRowInset),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = AloNativeAppText, modifier = Modifier.size(25.dp))
            }
            Text(title, modifier = Modifier.weight(1f), color = AloNativeAppText, fontSize = 18.sp)
            SettingsChevron(22)
        }
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
