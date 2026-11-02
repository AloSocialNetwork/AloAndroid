package com.alo.android

import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Telegram Android 13.0.3's AlertDialog (`ActionBar/AlertDialog.java`) in place of Material's: 20dp corners, a window as wide as the
 * screen less 56dp but at most 356dp, a 20sp title 24dp from the sides (19dp from the top, 10dp above the text), 16sp text with 20dp
 * below it, and 40dp buttons of 16sp text 8dp apart, in a row 8dp from the edges. Same parameters as `androidx.compose.material3.AlertDialog`
 * (the ones Alo uses), so a call site only drops that import.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = AloNativeAppBackgroundSecondary,
    titleContentColor: Color = AloNativeAppText,
    textContentColor: Color = AloNativeAppMuted
) {
    val metrics = AloTelegramMetrics.Sheets
    val maxWidth = (LocalConfiguration.current.screenWidthDp.dp - metrics.DialogWindowMargin).coerceAtMost(metrics.DialogMaxWidth)
    // Buttons are 16sp medium whatever the Material button style says.
    val typography = MaterialTheme.typography.copy(
        labelLarge = MaterialTheme.typography.labelLarge.copy(fontSize = metrics.DialogButtonSize, fontWeight = FontWeight.Medium)
    )
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // By default the window only slides up for the keyboard, and its buttons stay under the keys. It has to fit above the
        // keyboard instead: then a tall body gives way (below) and the buttons stay on screen. Edge to edge Android ignores
        // adjustResize for this, so from Android 11 the window asks for it directly.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dialogWindow?.attributes = dialogWindow?.attributes?.apply {
                    fitInsetsTypes = fitInsetsTypes or WindowInsets.Type.ime()
                }
            }
        }
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = typography, shapes = MaterialTheme.shapes) {
            Surface(
                modifier = modifier.widthIn(max = maxWidth).fillMaxWidth(),
                shape = RoundedCornerShape(metrics.DialogRadius),
                color = containerColor
            ) {
                Column {
                    if (title != null) {
                        Box(Modifier.padding(start = metrics.DialogTextInset, end = metrics.DialogTextInset, top = metrics.DialogTitleTop, bottom = metrics.DialogTitleBottom)) {
                            CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                                ProvideTextStyle(TextStyle(fontSize = metrics.TitleSize, fontWeight = FontWeight.Medium, lineHeight = 26.sp), title)
                            }
                        }
                    }
                    if (text != null) {
                        // A tall body gives way, so the buttons stay on screen above the keyboard.
                        Box(
                            Modifier.weight(1f, fill = false).padding(
                                start = metrics.DialogTextInset, end = metrics.DialogTextInset,
                                top = if (title == null) metrics.DialogTitleTop else 0.dp,
                                bottom = metrics.DialogTextBottom
                            )
                        ) {
                            CompositionLocalProvider(LocalContentColor provides textContentColor) {
                                ProvideTextStyle(TextStyle(fontSize = metrics.DialogTextSize, lineHeight = 22.sp), text)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = metrics.DialogButtonGap, end = metrics.DialogButtonGap, bottom = metrics.DialogButtonGap),
                        horizontalArrangement = Arrangement.spacedBy(metrics.DialogButtonGap, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}
