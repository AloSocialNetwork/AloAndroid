package com.alo.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun ChatAppearanceScreen(
    currentAppearance: AloChatAppearance,
    onDismiss: () -> Unit,
    onApply: (AloChatAppearance) -> Unit
) {
    // Back is the swipe of the page around it (AloSwipeBackPage in the chat's details), which follows the finger.
    var wallpaper by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.wallpaper) }
    var palette by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.palette) }
    var gradientStyle by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.gradientStyle) }
    var patternStyle by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.patternStyle) }
    var wallpaperHue by rememberSaveable(currentAppearance) {
        mutableStateOf(currentAppearance.wallpaperHue.takeIf { it >= 0f } ?: defaultWallpaperHueFor(wallpaper))
    }
    var bubbleHue by rememberSaveable(currentAppearance) {
        mutableStateOf(currentAppearance.bubbleHue.takeIf { it >= 0f } ?: defaultBubbleHueFor(palette))
    }
    var wallpaperBrightness by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.wallpaperBrightness) }
    var outgoingBrightness by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.outgoingBrightness) }
    var incomingBrightness by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.incomingBrightness) }
    var patternIntensity by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.patternIntensity) }
    var compactSpacing by rememberSaveable(currentAppearance) { mutableStateOf(currentAppearance.compactSpacing) }
    val previewAppearance = remember(
        wallpaper,
        palette,
        gradientStyle,
        patternStyle,
        wallpaperHue,
        bubbleHue,
        wallpaperBrightness,
        outgoingBrightness,
        incomingBrightness,
        patternIntensity,
        compactSpacing
    ) {
        AloChatAppearance(
            wallpaper = wallpaper,
            palette = palette,
            gradientStyle = gradientStyle,
            patternStyle = patternStyle,
            wallpaperHue = wallpaperHue,
            bubbleHue = bubbleHue,
            wallpaperBrightness = wallpaperBrightness,
            outgoingBrightness = outgoingBrightness,
            incomingBrightness = incomingBrightness,
            patternIntensity = patternIntensity,
            compactSpacing = compactSpacing
        )
    }

    CompositionLocalProvider(LocalAloNativeChatAppearance provides previewAppearance) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AloNativeAppBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    // iOS ChatAppearanceSheet: a cross on a 38 pt glass circle, 17 pt.
                    AloGlassCircleButton(
                        onClick = onDismiss,
                        description = "Отмена",
                        modifier = Modifier.align(Alignment.CenterStart),
                        close = true,
                        diameter = 38.dp,
                        iconPointSize = 17.dp
                    )
                    Text(
                        text = "Оформление чатов",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AloNativeAppText
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 10.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                        ChatAppearancePreviewCard(
                            appearance = previewAppearance
                        )
                        Text(
                            text = "Темы",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = AloNativeAppText
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(chatAppearancePresets, key = { it.id }) { preset ->
                                ChatAppearanceThemeChip(
                                    preset = preset,
                                    selected = wallpaper == preset.appearance.wallpaper &&
                                        palette == preset.appearance.palette &&
                                        wallpaperHue == preset.appearance.wallpaperHue &&
                                        bubbleHue == preset.appearance.bubbleHue &&
                                        wallpaperBrightness == preset.appearance.wallpaperBrightness &&
                                        outgoingBrightness == preset.appearance.outgoingBrightness &&
                                        incomingBrightness == preset.appearance.incomingBrightness,
                                    onClick = {
                                        wallpaper = preset.appearance.wallpaper
                                        palette = preset.appearance.palette
                                        gradientStyle = preset.appearance.gradientStyle
                                        patternStyle = preset.appearance.patternStyle
                                        wallpaperHue = preset.appearance.wallpaperHue
                                        bubbleHue = preset.appearance.bubbleHue
                                        wallpaperBrightness = preset.appearance.wallpaperBrightness
                                        outgoingBrightness = preset.appearance.outgoingBrightness
                                        incomingBrightness = preset.appearance.incomingBrightness
                                        patternIntensity = preset.appearance.patternIntensity
                                        compactSpacing = preset.appearance.compactSpacing
                                    }
                                )
                            }
                        }
                        Text(
                            text = "Обои",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = AloNativeAppText
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(listOf("classic", "black", "aurora", "sunset", "linen"), key = { it }) { value ->
                                ChatAppearanceWallpaperChip(
                                    value = value,
                                    selected = wallpaper == value,
                                    onClick = {
                                        wallpaper = value
                                        wallpaperHue = defaultWallpaperHueFor(value)
                                        wallpaperBrightness = defaultWallpaperBrightnessFor(value)
                                    }
                                )
                            }
                        }
                        ChatAppearanceOptionRow(
                            title = "Градиент",
                            options = listOf(
                                "diagonal" to "Диагональ",
                                "vertical" to "Сверху",
                                "glow" to "Сияние"
                            ),
                            selected = gradientStyle,
                            onSelect = { gradientStyle = it }
                        )
                        ChatAppearanceOptionRow(
                            title = "Узор",
                            options = listOf(
                                "none" to "Без узора",
                                "line" to "Линии",
                                "ink" to "Эскиз"
                            ),
                            selected = patternStyle,
                            onSelect = { patternStyle = it }
                        )
                        ChatAppearanceHueSlider(
                            title = "Оттенок фона",
                            value = wallpaperHue,
                            onValueChange = { wallpaperHue = it }
                        )
                        ChatAppearanceLevelSlider(
                            title = "Яркость фона",
                            value = wallpaperBrightness,
                            range = 0.02f..0.55f,
                            onValueChange = { wallpaperBrightness = it }
                        )
                        Text(
                            text = "Палитра сообщений",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = AloNativeAppText
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(listOf("blue", "black", "emerald", "violet", "sunset", "graphite"), key = { it }) { value ->
                                ChatAppearancePaletteChip(
                                    value = value,
                                    selected = palette == value,
                                    onClick = {
                                        palette = value
                                        bubbleHue = defaultBubbleHueFor(value)
                                        if (value == "black") {
                                            outgoingBrightness = 0.82f
                                            incomingBrightness = 0.075f
                                        }
                                    }
                                )
                            }
                        }
                        ChatAppearanceHueSlider(
                            title = "Оттенок пузырей",
                            value = bubbleHue,
                            onValueChange = { bubbleHue = it }
                        )
                        ChatAppearanceLevelSlider(
                            title = "Яркость исходящих",
                            value = outgoingBrightness,
                            range = 0.35f..1f,
                            onValueChange = { outgoingBrightness = it }
                        )
                        ChatAppearanceLevelSlider(
                            title = "Яркость входящих",
                            value = incomingBrightness,
                            range = 0.05f..0.42f,
                            onValueChange = { incomingBrightness = it }
                        )
                        if (patternStyle != "none") Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Интенсивность узора",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = AloNativeAppText
                                )
                                Text(
                                    text = "${(patternIntensity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AloNativeAppMuted
                                )
                            }
                            Slider(
                                value = patternIntensity,
                                onValueChange = { patternIntensity = it },
                                valueRange = 0.03f..0.18f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = AloNativeChatAccentBlue,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                                )
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AloSquircle(16.dp))
                                .background(AloNativeAppSurfaceRaised.copy(alpha = 0.72f))
                                .clickable { compactSpacing = !compactSpacing }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Компактные интервалы",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = AloNativeAppText
                                )
                                Text(
                                    text = "Уменьшает расстояние между сообщениями",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AloNativeAppMuted
                                )
                            }
                            Switch(
                                checked = compactSpacing,
                                onCheckedChange = { compactSpacing = it }
                            )
                        }
                        Button(
                            onClick = {
                                val reset = AloChatAppearance()
                                wallpaper = reset.wallpaper
                                palette = reset.palette
                                gradientStyle = reset.gradientStyle
                                patternStyle = reset.patternStyle
                                wallpaperHue = defaultWallpaperHueFor(reset.wallpaper)
                                bubbleHue = defaultBubbleHueFor(reset.palette)
                                wallpaperBrightness = reset.wallpaperBrightness
                                outgoingBrightness = reset.outgoingBrightness
                                incomingBrightness = reset.incomingBrightness
                                patternIntensity = reset.patternIntensity
                                compactSpacing = reset.compactSpacing
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = AloSquircle(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AloNativeAppSurfaceRaised,
                                contentColor = AloNativeAppText
                            )
                        ) {
                            Text("Сбросить оформление")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AloNativeAppSurfaceRaised,
                                    contentColor = AloNativeAppText
                                )
                            ) {
                                Text("Отмена")
                            }
                            Button(
                                onClick = { onApply(previewAppearance) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(999.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AloNativeAppAccent,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Применить")
                            }
                        }
                }
            }
        }
    }
}

private data class ChatAppearancePreset(
    val id: String,
    val title: String,
    val appearance: AloChatAppearance
)

private val chatAppearancePresets = listOf(
    ChatAppearancePreset(
        id = "classic",
        title = "Классическая",
            appearance = AloChatAppearance(
                wallpaper = "classic",
                palette = "blue",
                wallpaperHue = 220f,
                bubbleHue = 214f,
                wallpaperBrightness = 0.02f,
                patternStyle = "ink",
                patternIntensity = 0.055f
            )
    ),
    ChatAppearancePreset(
        id = "black",
        title = "Чёрная",
        appearance = AloChatAppearance(
            wallpaper = "black",
            palette = "black",
            wallpaperHue = 220f,
            bubbleHue = 214f,
            wallpaperBrightness = 0.025f,
                outgoingBrightness = 0.82f,
                incomingBrightness = 0.075f,
                patternStyle = "ink",
                patternIntensity = 0.055f
        )
    ),
    ChatAppearancePreset(
        id = "ocean",
        title = "Океан",
        appearance = AloChatAppearance(
            wallpaper = "classic",
            palette = "blue",
            wallpaperHue = 196f,
            bubbleHue = 202f,
            patternStyle = "line",
            patternIntensity = 0.12f
        )
    ),
    ChatAppearancePreset(
        id = "north",
        title = "Север",
        appearance = AloChatAppearance(
            wallpaper = "aurora",
            palette = "emerald",
            wallpaperHue = 170f,
            bubbleHue = 162f,
            patternStyle = "line",
            patternIntensity = 0.10f
        )
    ),
    ChatAppearancePreset(
        id = "graphite",
        title = "Графит",
        appearance = AloChatAppearance(
            wallpaper = "linen",
            palette = "graphite",
            wallpaperHue = 220f,
            bubbleHue = 214f,
            patternStyle = "ink",
            patternIntensity = 0.08f
        )
    )
)

@Composable
private fun ChatAppearanceThemeChip(
    preset: ChatAppearancePreset,
    selected: Boolean,
    onClick: () -> Unit
) {
    val wallpaperHue = preset.appearance.wallpaperHue
    val bubbleHue = preset.appearance.bubbleHue
    val wallpaperBrightness = preset.appearance.wallpaperBrightness
    Box(
        modifier = Modifier
            .width(132.dp)
            .height(94.dp)
            .clip(AloSquircle(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.hsv(wallpaperHue, 0.72f, wallpaperBrightness),
                        Color.hsv((wallpaperHue + 18f) % 360f, 0.62f, (wallpaperBrightness + 0.16f).coerceAtMost(1f))
                    )
                )
            )
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.10f),
                AloSquircle(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(58.dp)
                .height(20.dp),
            shape = AloSquircle(10.dp),
            color = Color.hsv(bubbleHue, 0.78f, preset.appearance.outgoingBrightness)
        ) {}
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(48.dp)
                .height(18.dp),
            shape = AloSquircle(9.dp),
            color = Color.hsv(bubbleHue, 0.10f, preset.appearance.incomingBrightness)
        ) {}
        Text(
            text = preset.title,
            modifier = Modifier.align(Alignment.BottomStart),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White
        )
    }
}

@Composable
private fun ChatAppearancePreviewCard(
    appearance: AloChatAppearance
) {
    CompositionLocalProvider(LocalAloNativeChatAppearance provides appearance) {
        val outgoingColor = AloNativeChatOutgoing
        val outgoingBorder = AloNativeChatOutgoingBorder.copy(alpha = 0.34f)
        val incomingColor = AloNativeChatIncoming
        val incomingBorder = AloNativeChatIncomingBorder.copy(alpha = 0.16f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(184.dp)
                .clip(AloSquircle(18.dp))
        ) {
            ChatWallpaperBackground(modifier = Modifier.matchParentSize())
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = AloSquircle(16.dp),
                        color = incomingColor.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, incomingBorder)
                    ) {
                        Text(
                            text = "Это пример входящего сообщения",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AloNativeAppText
                        )
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.End),
                        shape = AloSquircle(16.dp),
                        color = outgoingColor.copy(alpha = 0.96f),
                        border = BorderStroke(1.dp, outgoingBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text(
                                text = "Подпись под фото",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                text = "19:29",
                                modifier = Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.84f)
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = AloNativeAppSurface.copy(alpha = 0.76f),
                    border = BorderStroke(1.dp, AloNativeAppBorder.copy(alpha = 0.05f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = AloNativeAppSurfaceRaised.copy(alpha = 0.92f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = null,
                                    tint = AloNativeAppMuted,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(AloNativeAppSurfaceRaised.copy(alpha = 0.92f))
                        )
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = AloNativeChatAccentBlue
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun defaultWallpaperHueFor(value: String): Float {
    return when (value.trim().lowercase(Locale.getDefault())) {
        "aurora" -> 170f
        "sunset" -> 18f
        "linen" -> 220f
        "black" -> 220f
        "classic" -> 220f
        else -> 210f
    }
}

private fun defaultWallpaperBrightnessFor(value: String): Float {
    return when (value.trim().lowercase(Locale.getDefault())) {
        "classic" -> 0.02f
        "black" -> 0.025f
        "linen" -> 0.035f
        else -> 0.34f
    }
}

private fun defaultBubbleHueFor(value: String): Float {
    return when (value.trim().lowercase(Locale.getDefault())) {
        "emerald" -> 162f
        "violet" -> 248f
        "sunset" -> 24f
        "graphite" -> 214f
        "black" -> 214f
        else -> 214f
    }
}

@Composable
private fun ChatAppearanceOptionRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = AloNativeAppText
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            options.forEach { (value, label) ->
                val isSelected = selected == value
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelect(value) },
                    shape = RoundedCornerShape(999.dp),
                    color = if (isSelected) AloNativeAppAccent else AloNativeAppSurfaceRaised,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) AloNativeAppAccent else AloNativeAppBorder.copy(alpha = 0.08f)
                    )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isSelected) Color.White else AloNativeAppMuted,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatAppearanceLevelSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppText
            )
            Text(
                text = "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = AloNativeAppMuted
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = AloNativeChatAccentBlue,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
            )
        )
    }
}

@Composable
private fun ChatAppearanceHueSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = AloNativeAppText
            )
            Text(
                text = value.toInt().toString(),
                style = MaterialTheme.typography.labelSmall,
                color = AloNativeAppMuted
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = List(7) { index ->
                            Color.hsv(index * 60f, 0.78f, 0.96f)
                        }
                    )
                )
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.18f),
                inactiveTrackColor = Color.White.copy(alpha = 0.08f)
            )
        )
    }
}

@Composable
private fun ChatAppearanceWallpaperChip(
    value: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val hue = defaultWallpaperHueFor(value)
    val brightness = defaultWallpaperBrightnessFor(value)
    val previewBrush = remember(value) {
        Brush.linearGradient(
            colors = listOf(
                Color.hsv(hue, 0.72f, brightness),
                Color.hsv((hue + 16f) % 360f, 0.68f, (brightness + 0.10f).coerceAtMost(1f)),
                Color.hsv((hue + 24f) % 360f, 0.56f, (brightness + 0.18f).coerceAtMost(1f))
            )
        )
    }
    Box(
        modifier = Modifier
            .width(116.dp)
            .height(86.dp)
            .clip(AloSquircle(18.dp))
            .background(previewBrush)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.9f) else AloNativeAppBorder.copy(alpha = 0.08f),
                shape = AloSquircle(18.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.08f))
        )
        Text(
            text = wallpaperTitleFor(value),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White
        )
    }
}

private fun wallpaperTitleFor(value: String): String {
    return when (value.trim().lowercase(Locale.getDefault())) {
        "classic" -> "Классика"
        "black" -> "Чёрная"
        "aurora" -> "Север"
        "sunset" -> "Закат"
        "linen" -> "Графит"
        else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}

@Composable
private fun ChatAppearancePaletteChip(
    value: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val outgoingColor = when (value) {
        "black" -> Color(0xFF145DB8)
        "emerald" -> Color(0xFF2D6B5F)
        "violet" -> Color(0xFF5960A8)
        "sunset" -> Color(0xFFD06D2D)
        "graphite" -> Color(0xFF3A495A)
        else -> Color(0xFF4A78B8)
    }
    Box(
        modifier = Modifier
            .width(116.dp)
            .height(72.dp)
            .clip(AloSquircle(18.dp))
            .background(AloNativeAppSurface.copy(alpha = 0.82f))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color.White.copy(alpha = 0.9f) else AloNativeAppBorder.copy(alpha = 0.08f),
                shape = AloSquircle(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .width(58.dp)
                .height(18.dp),
            shape = AloSquircle(10.dp),
            color = outgoingColor,
            border = BorderStroke(1.dp, outgoingColor.copy(alpha = 0.4f))
        ) {}
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(44.dp)
                .height(16.dp),
            shape = AloSquircle(10.dp),
            color = AloNativeChatIncoming,
            border = BorderStroke(1.dp, AloNativeChatIncomingBorder.copy(alpha = 0.12f))
        ) {}
        Text(
            text = paletteTitleFor(value),
            modifier = Modifier.align(Alignment.BottomStart),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = AloNativeAppText
        )
    }
}

private fun paletteTitleFor(value: String): String {
    return when (value.trim().lowercase(Locale.getDefault())) {
        "blue" -> "Синяя"
        "black" -> "Ночная"
        "emerald" -> "Изумруд"
        "violet" -> "Фиолет"
        "sunset" -> "Закат"
        "graphite" -> "Графит"
        else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
