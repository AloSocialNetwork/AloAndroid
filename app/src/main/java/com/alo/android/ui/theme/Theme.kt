package com.alo.android.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import com.alo.android.AloPressIndication
import com.alo.android.AloThemeMode

private val AloColorScheme = lightColorScheme(
    primary = AloBlue,
    secondary = AloSurfaceSoft,
    tertiary = AloBlue,
    background = AloBackground,
    surface = AloSurface,
    surfaceVariant = AloSurfaceSoft,
    outline = AloBorder,
    error = AloDanger,
    onPrimary = AloBackground,
    onSecondary = AloText,
    onTertiary = AloBackground,
    onBackground = AloText,
    onSurface = AloText,
    onSurfaceVariant = AloMuted
)

private val AloDarkColorScheme = darkColorScheme(
    primary = AloDarkUiPalette.accent,
    secondary = AloDarkUiPalette.surface,
    tertiary = AloDarkUiPalette.accentStrong,
    background = AloDarkUiPalette.background,
    surface = AloDarkUiPalette.surface,
    surfaceVariant = AloDarkUiPalette.surfaceRaised,
    outline = AloDarkUiPalette.border,
    error = AloDarkUiPalette.danger,
    onPrimary = AloDarkUiPalette.buttonText,
    onSecondary = AloDarkUiPalette.text,
    onTertiary = AloDarkUiPalette.background,
    onBackground = AloDarkUiPalette.text,
    onSurface = AloDarkUiPalette.text,
    onSurfaceVariant = AloDarkUiPalette.muted
)

@Composable
fun AloAndroidTheme(
    themeMode: AloThemeMode = AloThemeMode.System,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        AloThemeMode.Dark -> true
        AloThemeMode.Light -> false
        AloThemeMode.System -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDarkTheme) AloDarkColorScheme else AloColorScheme
    val appPalette = if (useDarkTheme) AloDarkUiPalette else AloLightUiPalette
    // The status bar follows the app's theme, not the system's: light icons over the dark theme.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var context = view.context
            while (context is ContextWrapper && context !is Activity) context = context.baseContext
            val window = (context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !useDarkTheme
                isAppearanceLightNavigationBars = !useDarkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAloUiPalette provides appPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            // Pop-up menus use Material's extra-small shape: Telegram's ActionBarPopupWindow is 12dp.
            shapes = Shapes(extraSmall = RoundedCornerShape(22.dp))
        ) {
            // iOS's press feedback in place of Material's ripple, for every clickable that does not set its own.
            CompositionLocalProvider(LocalIndication provides AloPressIndication, content = content)
        }
    }
}
