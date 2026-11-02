package com.alo.android

import android.content.SharedPreferences

internal class AloChatAppearanceStore(
    private val prefs: SharedPreferences
) {
    private val globalKey = "chat_appearance_global"

    fun read(detail: AloConversationDetail?): AloChatAppearance {
        val globalRaw = prefs.getString(globalKey, null).orEmpty()
        val legacyRaw = detail?.let {
            prefs.getString("chat_appearance_${chatAppearanceKey(it)}", null).orEmpty()
        }.orEmpty()
        val raw = globalRaw.ifBlank { legacyRaw }
        if (raw.isBlank()) return AloChatAppearance()

        val parts = raw.split('|')
        var appearance = AloChatAppearance(
            wallpaper = normalizeWallpaperId(parts.getOrNull(0).orEmpty().ifBlank { AloChatAppearance().wallpaper }),
            palette = parts.getOrNull(1).orEmpty().ifBlank { AloChatAppearance().palette },
            wallpaperHue = parts.getOrNull(2)?.toFloatOrNull() ?: AloChatAppearance().wallpaperHue,
            bubbleHue = parts.getOrNull(3)?.toFloatOrNull() ?: AloChatAppearance().bubbleHue,
            patternIntensity = parts.getOrNull(4)?.toFloatOrNull()?.coerceIn(0f, 0.18f)
                ?: AloChatAppearance().patternIntensity,
            compactSpacing = parts.getOrNull(5)?.toBooleanStrictOrNull()
                ?: AloChatAppearance().compactSpacing,
            wallpaperBrightness = parts.getOrNull(6)?.toFloatOrNull()?.coerceIn(0.02f, 0.55f)
                ?: AloChatAppearance().wallpaperBrightness,
            outgoingBrightness = parts.getOrNull(7)?.toFloatOrNull()?.coerceIn(0.35f, 1f)
                ?: AloChatAppearance().outgoingBrightness,
            incomingBrightness = parts.getOrNull(8)?.toFloatOrNull()?.coerceIn(0.05f, 0.42f)
                ?: AloChatAppearance().incomingBrightness,
            gradientStyle = normalizeGradientStyle(parts.getOrNull(9).orEmpty()),
            patternStyle = normalizePatternStyle(parts.getOrNull(10).orEmpty())
        )
        if (appearance.wallpaper == "classic" && kotlin.math.abs(appearance.wallpaperBrightness - 0.34f) < 0.001f) {
            appearance = appearance.copy(wallpaperBrightness = 0.02f)
        }
        // Migrate only the previous stock look; user-customised chats remain intact.
        if (appearance.wallpaper == "classic" &&
            appearance.palette == "blue" &&
            kotlin.math.abs(appearance.outgoingBrightness - 0.86f) < 0.001f &&
            kotlin.math.abs(appearance.incomingBrightness - 0.10f) < 0.001f &&
            kotlin.math.abs(appearance.patternIntensity - 0.09f) < 0.001f
        ) {
            appearance = appearance.copy(
                outgoingBrightness = 0.78f,
                incomingBrightness = 0.16f,
                patternIntensity = 0.04f
            )
        }
        // The new stock look uses the supplied chat sketch at a restrained
        // opacity. Preserve deliberate custom settings and migrate only the
        // exact older default.
        if (appearance.wallpaper == "classic" &&
            appearance.palette == "blue" &&
            kotlin.math.abs(appearance.outgoingBrightness - 0.78f) < 0.001f &&
            kotlin.math.abs(appearance.incomingBrightness - 0.16f) < 0.001f &&
            (kotlin.math.abs(appearance.patternIntensity - 0.04f) < 0.001f ||
                kotlin.math.abs(appearance.patternIntensity) < 0.001f)
        ) {
            appearance = appearance.copy(patternStyle = "ink", patternIntensity = 0.055f)
        }
        if (globalRaw.isBlank()) save(detail, appearance)
        if (appearance.wallpaper == "classic" &&
            (parts.getOrNull(6)?.toFloatOrNull() == 0.34f ||
                parts.getOrNull(7)?.toFloatOrNull() == 0.86f)
        ) {
            save(detail, appearance)
        }
        return appearance
    }

    fun save(detail: AloConversationDetail?, appearance: AloChatAppearance) {
        val wallpaper = normalizeWallpaperId(appearance.wallpaper.trim().ifBlank { AloChatAppearance().wallpaper })
        val palette = appearance.palette.trim().ifBlank { AloChatAppearance().palette }
        prefs.edit()
            .putString(
                globalKey,
                "$wallpaper|$palette|${appearance.wallpaperHue}|${appearance.bubbleHue}|${appearance.patternIntensity}|${appearance.compactSpacing}|${appearance.wallpaperBrightness}|${appearance.outgoingBrightness}|${appearance.incomingBrightness}|${normalizeGradientStyle(appearance.gradientStyle)}|${normalizePatternStyle(appearance.patternStyle)}"
            )
            .apply()
    }

    private fun normalizeWallpaperId(value: String): String {
        val legacyClassicId = listOf("tele", "gram").joinToString(separator = "")
        return if (value == legacyClassicId) "classic" else value
    }

    private fun normalizeGradientStyle(value: String): String = when (value) {
        "vertical", "glow" -> value
        else -> "diagonal"
    }

    private fun normalizePatternStyle(value: String): String = when (value) {
        "none", "line", "ink" -> value
        else -> "ink"
    }
}
