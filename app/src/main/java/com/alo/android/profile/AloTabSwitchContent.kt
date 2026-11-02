package com.alo.android

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The body under a profile's «Посты / Нравится» switch. A lazy list clamps its scroll to the top as soon as its content gets shorter,
 * so swapping a long list for a short or still-empty one threw the page back to the top. This keeps the old height for a moment,
 * fades the two lists into each other and then lets the height settle smoothly instead of in one jump.
 */
@Composable
internal fun AloTabSwitchContent(tab: String, modifier: Modifier = Modifier, minHeight: Dp = 0.dp, content: @Composable (String) -> Unit) {
    var measuredPx by remember { mutableIntStateOf(0) }
    var heldPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab) {
        heldPx = measuredPx
        delay(420)
        heldPx = 0
    }
    val held = with(LocalDensity.current) { heldPx.toDp() }
    Box(
        modifier
            .animateContentSize(tween(260))
            .heightIn(min = maxOf(held, minHeight))
            .onSizeChanged { if (heldPx == 0) measuredPx = it.height }
    ) {
        Crossfade(targetState = tab, animationSpec = tween(220), label = "profileTabBody") { content(it) }
    }
}

/**
 * True once the profile's «Посты / Нравится» switcher (the list item at [tabsIndex], key [key]) has scrolled up past the top edge.
 * The switcher is then drawn again as liquid glass over the list: the glass cannot live inside the list it samples (the render node tree
 * would loop), so the list keeps a plain copy and this floating glass one takes over at the top.
 */
@Composable
internal fun rememberTabsPinned(listState: LazyListState, key: String, tabsIndex: Int): Boolean {
    val pinned by remember(listState) {
        derivedStateOf {
            val tabs = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            if (tabs != null) tabs.offset < 0 else listState.firstVisibleItemIndex > tabsIndex
        }
    }
    return pinned
}

/**
 * What a profile's «Посты / Нравится» switch needs so the page stays where it is: a floor under the body (so a short list never makes the page
 * clamp to its top and throw the header back into view) and a smooth scroll that parks the switcher at the top edge when the tab changes while
 * it is pinned there. [topPadding] and [bottomPadding] are the list's content padding.
 */
internal class AloProfileTabScroll(
    private val listState: LazyListState,
    private val tabsIndex: Int,
    private val topPaddingPx: Int,
    private val density: androidx.compose.ui.unit.Density,
    private val bottomPadding: Dp,
    private val tabsHeightPx: Int
) {
    /** The body is at least as tall as the room under the switcher, so the switcher can always reach the top of the page. */
    val minBody: Dp
        get() = with(density) { (listState.layoutInfo.viewportSize.height - tabsHeightPx).coerceAtLeast(0).toDp() } - bottomPadding

    suspend fun settle() {
        listState.animateScrollToItem(tabsIndex, scrollOffset = topPaddingPx)
    }
}

@Composable
internal fun rememberAloProfileTabScroll(
    listState: LazyListState,
    tabsIndex: Int,
    topPadding: Dp,
    bottomPadding: Dp,
    tabsHeightPx: Int
): AloProfileTabScroll {
    val density = LocalDensity.current
    val topPx = with(density) { topPadding.roundToPx() }
    return remember(listState, tabsIndex, topPx, density, bottomPadding, tabsHeightPx) {
        AloProfileTabScroll(listState, tabsIndex, topPx, density, bottomPadding, tabsHeightPx)
    }
}
