package com.alo.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The title of a bottom sheet: Telegram's `BottomSheet` title is 20sp, 48dp high, set in 21dp from the sides (padding 21/6/21/8). */
@Composable
internal fun AloSheetTitle(text: String, modifier: Modifier = Modifier, bleed: Dp = 0.dp) {
    Box(
        // [bleed] undoes the side padding of the column the title sits in, so it still starts 21dp from the sheet's edge.
        modifier.layout { measurable, constraints ->
            val extra = (bleed * 2).roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed.roundToPx(), 0) }
        }.fillMaxWidth().heightIn(min = AloTelegramMetrics.Sheets.ItemHeight)
            .padding(start = AloTelegramMetrics.Sheets.TitleStart, end = AloTelegramMetrics.Sheets.TitleStart, top = 6.dp, bottom = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = AloNativeAppText, fontSize = AloTelegramMetrics.Sheets.TitleSize, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A row of a bottom sheet (`BottomSheet.BottomSheetCell`): 48dp high, 16sp text, the icon in a 56dp box and the text starting at 72dp,
 * or 16dp from the edge without an icon. A subtitle (14sp, grey) makes it taller.
 */
@Composable
internal fun AloSheetItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = AloNativeAppAccent,
    titleColor: Color = AloNativeAppText,
    /** Undoes the side padding of the column the row sits in, so the row spans the sheet and its text starts 16dp from the edge. */
    bleed: Dp = 0.dp,
    onClick: () -> Unit
) {
    val metrics = AloTelegramMetrics.Sheets
    Row(
        modifier.layout { measurable, constraints ->
            val extra = (bleed * 2).roundToPx()
            val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed.roundToPx(), 0) }
        }.fillMaxWidth().clickable(onClick = onClick).heightIn(min = metrics.ItemHeight)
            .padding(vertical = if (subtitle != null) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Box(Modifier.width(metrics.ItemIconBox), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(metrics.ItemIconSize))
            }
        } else {
            Box(Modifier.width(metrics.ItemTextInset))
        }
        Column(Modifier.weight(1f).padding(end = metrics.ItemTextInset), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = titleColor, fontSize = metrics.ItemTextSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = AloNativeAppMuted, fontSize = metrics.ItemSubtitleSize, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
