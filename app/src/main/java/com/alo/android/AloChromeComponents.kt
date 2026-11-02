package com.alo.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
internal fun SearchField(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AloNativeLayout.compactControlHeight),
        shape = RoundedCornerShape(AloNativeLayout.radiusMedium),
        color = AloNativeAppSurfaceRaised,
        border = BorderStroke(1.dp, AloNativeAppBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = AloNativeAppMuted,
                modifier = Modifier.size(20.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = AloNativeAppText, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(AloNativeAppAccentStrong),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.titleMedium,
                                color = AloNativeAppMuted
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
internal fun MessagesSearchField(
    modifier: Modifier = Modifier,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AloNativeLayout.compactControlHeight),
        shape = RoundedCornerShape(AloNativeLayout.radiusMedium),
        color = AloNativeAppSurfaceRaised,
        border = BorderStroke(1.dp, AloNativeAppBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 17.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = AloNativeAppMuted,
                modifier = Modifier.size(20.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = AloNativeAppText, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(AloNativeAppAccentStrong),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.titleMedium,
                                color = AloNativeAppMuted
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
internal fun CompactTitleBar(
    title: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 26.sp
            ),
            color = AloNativeAppText
        )
    }
}

@Composable
internal fun AloBottomBar(
    modifier: Modifier = Modifier,
    activeTab: AloAppTab,
    onSelect: (AloAppTab) -> Unit
) {
    val tabs = listOf(
        Triple(AloAppTab.Feed, Icons.Rounded.Home, "Лента"),
        Triple(AloAppTab.Search, Icons.Rounded.Search, "Поиск"),
        Triple(AloAppTab.Messages, Icons.Rounded.ChatBubbleOutline, "Чаты"),
        Triple(AloAppTab.Alerts, Icons.Rounded.NotificationsNone, "Уведы"),
        Triple(AloAppTab.Profile, Icons.Rounded.PersonOutline, "Профиль")
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 6.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // iOS's floating glass tab bar (MainTabView): a 60dp capsule, 16dp from the edges,
        // one white highlight travelling between the tabs. The feed shows through, blurred.
        val barShape = RoundedCornerShape(percent = 50)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .height(60.dp)
                .aloLiquidGlass(shape = barShape, tint = AloGlassOverShadeTint)
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            val selectedIndex = tabs.indexOfFirst { it.first == activeTab }.coerceAtLeast(0)
            val itemWidth = maxWidth / tabs.size
            val indicatorOffset = animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f),
                label = "bottomBarSelectionOffset"
            ).value

            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .fillMaxHeight()
                    .clip(barShape)
                    // Telegram's GlassTabView: the selected tab sits on its accent at 9%, in both themes.
                    .background(AloNativeAppAccent.copy(alpha = 0.09f))
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { (tab, icon, label) ->
                    AloBottomBarItem(
                        modifier = Modifier.weight(1f),
                        selected = activeTab == tab,
                        icon = icon,
                        label = label,
                        onClick = { onSelect(tab) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AloBottomBarItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val contentColor = animateColorAsState(
        targetValue = if (selected) AloNativeAppAccent else AloNativeAppMuted,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "bottomBarContentColor"
    ).value
    val iconScale = animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = spring(stiffness = 460f, dampingRatio = 0.92f),
        label = "bottomBarIconScale"
    ).value

    Box(
        modifier = modifier.height(52.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    // Material glyphs sit inside a padded 24dp box; 26dp matches the visible size of iOS's 22pt symbols.
                    modifier = Modifier
                        .size(26.dp)
                        .scale(iconScale)
                )
            }
            Text(
                text = label,
                modifier = Modifier.padding(top = 2.dp),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}
