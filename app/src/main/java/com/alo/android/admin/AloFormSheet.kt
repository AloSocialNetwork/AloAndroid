package com.alo.android

import androidx.compose.ui.semantics.Role

import androidx.compose.foundation.selection.toggleable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// iOS's sheet with a Form: «Отмена», a title and an action on top, then grouped sections. Shared by
// the moderation decision, the ad campaign editor and the appeal.

private val isDarkForm: Boolean
    @Composable get() = AloNativeAppBackground.luminance() < 0.3f

/** Text at the font's own line height, as iOS sets it, instead of Material's roomy body style. */
@Composable
internal fun AloNaturalLineHeight(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified), content = content)
}

/** iOS's secondarySystemGroupedBackground on a sheet. */
internal val AloFormSectionColor: Color
    @Composable get() = if (isDarkForm) Color(0xFF2C2C2E) else Color.White

private val AloFormSeparatorColor: Color
    @Composable get() = if (isDarkForm) Color.White.copy(alpha = 0.09f) else AloNativeAppSeparator

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AloFormSheet(
    title: String,
    onDismiss: () -> Unit,
    /** null leaves the left slot empty, as a sheet whose only button is «Закрыть» on the right. */
    leading: String? = "Отмена",
    trailing: String? = null,
    trailingEnabled: Boolean = true,
    onTrailing: () -> Unit = {},
    fullHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    AloModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight),
        // A part-height sheet floats over the screen, so it is lifted off the black page.
        containerColor = if (fullHeight) AloNativeAppBackground else AloNativeAppSurface,
        shape = AloSheetShape,
        dragHandle = null
    ) {
        AloSheetSystemBars()
        AloNaturalLineHeight {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (fullHeight) Modifier.fillMaxHeight().windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp) else Modifier)
                .imePadding()
        ) {
            AloFormSheetHeader(title, leading, onDismiss, trailing, trailingEnabled, onTrailing)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) { content() }
        }
        }
    }
}

/**
 * A sheet is its own window, and Material colours its status bar icons by the system theme; they follow
 * the app's theme instead, so they stay visible on a dark sheet when the phone is light.
 */
@Composable
internal fun AloSheetSystemBars() {
    val view = LocalView.current
    val dark = isDarkForm
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/** Two glass capsules and a title centred between them, cut short when they leave no room. */
@Composable
private fun AloFormSheetHeader(
    title: String,
    leading: String?,
    onLeading: () -> Unit,
    trailing: String?,
    trailingEnabled: Boolean,
    onTrailing: () -> Unit
) {
    Layout(
        content = {
            Box(Modifier.layoutId("leading")) { if (leading != null) AloFormCapsule(leading, bold = false, enabled = true, onClick = onLeading) }
            Text(title, color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId("title"))
            Box(Modifier.layoutId("trailing")) { if (trailing != null) AloFormCapsule(trailing, bold = true, enabled = trailingEnabled, onClick = onTrailing) }
        },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val left = measurables.first { it.layoutId == "leading" }.measure(loose)
        val right: androidx.compose.ui.layout.Placeable? = measurables.first { it.layoutId == "trailing" }.measure(loose)
        val side = maxOf(left.width, right?.width ?: 0) + 12.dp.roundToPx()
        val titleWidth = (constraints.maxWidth - side * 2).coerceAtLeast(0)
        val center = measurables.first { it.layoutId == "title" }.measure(Constraints(maxWidth = titleWidth))
        val height = maxOf(left.height, right?.height ?: 0, center.height)
        layout(constraints.maxWidth, height) {
            left.place(0, (height - left.height) / 2)
            right?.place(constraints.maxWidth - right.width, (height - right.height) / 2)
            center.place((constraints.maxWidth - center.width) / 2, (height - center.height) / 2)
        }
    }
}

/**
 * Glass on a sheet. A sheet has nothing behind it to blur, so this is Telegram's glass without a
 * source: the 85% panel colour under its top and bottom lines.
 */
internal fun Modifier.aloFormGlass(shape: Shape): Modifier = composed {
    val dark = isDarkForm
    val fill = AloGlassFill
    this
        .clip(shape)
        .drawBehind {
            drawRect(fill)
            drawAloGlassEdge(shape, light = !dark)
        }
}

@Composable
private fun AloFormCapsule(title: String, bold: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .aloFormGlass(RoundedCornerShape(percent = 50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title, fontSize = AloTelegramMetrics.Sheets.DialogButtonSize, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
            color = if (enabled) AloNativeAppText else AloNativeAppMuted.copy(alpha = 0.6f), maxLines = 1
        )
    }
}

/** A Form section: a header, rows on a rounded card (separate them with [AloFormDivider]), and a footer. */
@Composable
internal fun AloFormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (header != null) 22.dp else 30.dp)) {
        if (header != null) {
            Text(
                header, color = AloNativeAppMuted, fontSize = AloTelegramMetrics.Section.HeaderSize, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
            )
        }
        Column(Modifier.fillMaxWidth().clip(AloSquircle(26.dp)).background(AloFormSectionColor), content = content)
        if (footer != null) {
            Text(footer, color = AloNativeAppMuted, fontSize = AloTelegramMetrics.Section.InfoSize, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        }
    }
}

@Composable
internal fun AloFormDivider() {
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.5.dp).background(AloFormSeparatorColor))
}

@Composable
internal fun AloFormValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.SettingsRow.Height).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AloNativeAppText, fontSize = AloTelegramMetrics.SettingsRow.TextSize)
        Spacer(Modifier.width(12.dp))
        Text(value, color = AloNativeAppMuted, fontSize = AloTelegramMetrics.SettingsRow.TextSize, textAlign = TextAlign.End, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AloFormTextRow(text: String, color: Color = AloNativeAppText) {
    Text(text, color = color, fontSize = AloTelegramMetrics.SettingsRow.TextSize, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp))
}

@Composable
internal fun AloFormCheckRow(title: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.SettingsRow.Height).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = AloNativeAppText, fontSize = AloTelegramMetrics.SettingsRow.TextSize, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Rounded.Check, null, tint = AloNativeAppAccent, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun AloFormButtonRow(title: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.SettingsRow.Height).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(title, color = if (enabled) color else color.copy(alpha = 0.45f), fontSize = AloTelegramMetrics.SettingsRow.TextSize, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun AloFormTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    minLines: Int = 1,
    maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.SettingsRow.Height).padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = AloNativeAppMuted.copy(alpha = 0.75f), fontSize = AloTelegramMetrics.SettingsRow.TextSize, minLines = minLines)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = maxLines == 1,
            minLines = minLines,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(color = AloNativeAppText, fontSize = AloTelegramMetrics.SettingsRow.TextSize),
            cursorBrush = SolidColor(AloNativeAppAccent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder }
        )
    }
}

/** A Form Toggle: the title and the iOS 26 switch. */
@Composable
internal fun AloFormSwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.SettingsRow.Height)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = if (enabled) AloNativeAppText else AloNativeAppMuted, fontSize = AloTelegramMetrics.SettingsRow.TextSize, modifier = Modifier.weight(1f))
        AloSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** iOS's menu Picker: the value with ⌃⌄, choices in a menu. */
@Composable
internal fun AloFormMenuRow(label: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.SettingsRow.Height).clickable { open = true }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = AloNativeAppText, fontSize = AloTelegramMetrics.SettingsRow.TextSize, modifier = Modifier.weight(1f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), color = AloNativeAppMuted, fontSize = AloTelegramMetrics.SettingsRow.TextSize)
                Icon(Icons.Rounded.UnfoldMore, null, tint = AloNativeAppMuted, modifier = Modifier.padding(start = 2.dp).size(18.dp))
            }
            AloDropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = AloNativeAppSurface) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(
                        text = { Text(title, color = AloNativeAppText, fontSize = 16.sp) },
                        leadingIcon = {
                            Box(Modifier.size(20.dp)) { if (value == selected) Icon(Icons.Rounded.Check, null, tint = AloNativeAppText, modifier = Modifier.size(20.dp)) }
                        },
                        onClick = { open = false; onSelect(value) }
                    )
                }
            }
        }
    }
}

/** iOS's Stepper: a label and a «− | +» capsule. */
@Composable
internal fun AloFormStepperRow(label: String, canDecrease: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = AloTelegramMetrics.SettingsRow.Height).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AloNativeAppText, fontSize = AloTelegramMetrics.SettingsRow.TextSize, modifier = Modifier.weight(1f))
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(percent = 50)).background(AloNativeAppText.copy(alpha = 0.10f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(46.dp).height(32.dp).clickable(enabled = canDecrease, onClick = onDecrease), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Remove, "Меньше", tint = if (canDecrease) AloNativeAppText else AloNativeAppMuted.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            }
            Box(Modifier.width(0.5.dp).height(18.dp).background(AloNativeAppMuted.copy(alpha = 0.5f)))
            Box(Modifier.width(46.dp).height(32.dp).clickable(onClick = onIncrease), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "Больше", tint = AloNativeAppText, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** iOS's ContentUnavailableView. */
@Composable
internal fun AloUnavailableView(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = AloNativeAppMuted, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(4.dp))
        Text(title, color = AloNativeAppText, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, color = AloNativeAppMuted, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}
