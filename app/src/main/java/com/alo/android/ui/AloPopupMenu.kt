package com.alo.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/** The one look of every popup menu: Telegram's rounded popup (ActionBarPopupWindow) with a soft shadow, 48dp rows. */
internal object AloPopupMenuMetrics {
    val Radius = 18.dp
    val MinWidth = 196.dp
    val Shadow = 14.dp
}

/**
 * Material's DropdownMenu with the app's shape, colour, outline and shadow, so a menu looks the same wherever it opens
 * (its default is a 4dp-cornered plate). Rows are `DropdownMenuItem`s, 48dp tall by default.
 */
@Composable
internal fun AloDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = AloSquircle(AloPopupMenuMetrics.Radius),
    containerColor: Color = AloNativeAppSurface,
    content: @Composable ColumnScope.() -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = AloPopupMenuMetrics.MinWidth),
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = AloPopupMenuMetrics.Shadow,
        border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.10f)),
        content = content
    )
}
