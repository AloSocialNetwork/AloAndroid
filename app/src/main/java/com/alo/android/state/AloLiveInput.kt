package com.alo.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What changes with every typed letter or, while recording, ten times a second. The real values stay in
 * [AloNativeAppUiState] for the app's logic, but the screens read them only from here, deep down, in the composer that shows
 * them: if the whole state changed each time, the entire app (feed, chat list, every visible message) would recompose.
 * MainActivity's `appState` setter keeps this in step and leaves the drawn state alone when nothing else changed.
 */
@Stable
internal class AloLiveInput {
    var messageText by mutableStateOf("")
    var commentText by mutableStateOf("")
    var postText by mutableStateOf("")
    var messageRecordingSeconds by mutableIntStateOf(0)
    var messageRecordingLevels by mutableStateOf(emptyList<Float>())
    var secretRecordingSeconds by mutableIntStateOf(0)
    var secretRecordingLevels by mutableStateOf(emptyList<Float>())

    fun update(state: AloNativeAppUiState) {
        messageText = state.messageComposerText
        commentText = state.commentsComposerText
        postText = state.composeText
        messageRecordingSeconds = state.messageRecordingDurationSec
        messageRecordingLevels = state.messageRecordingLevels
        secretRecordingSeconds = state.secretRecordingDurationSec
        secretRecordingLevels = state.secretRecordingLevels
    }
}

/** [this] with the fast-changing fields of [other]: equal to [other] when only those differ. */
internal fun AloNativeAppUiState.withLiveInputOf(other: AloNativeAppUiState) = copy(
    messageComposerText = other.messageComposerText,
    commentsComposerText = other.commentsComposerText,
    composeText = other.composeText,
    messageRecordingDurationSec = other.messageRecordingDurationSec,
    messageRecordingLevels = other.messageRecordingLevels,
    secretRecordingDurationSec = other.secretRecordingDurationSec,
    secretRecordingLevels = other.secretRecordingLevels
)

internal val LocalAloLiveInput = staticCompositionLocalOf { AloLiveInput() }

/** A recomposition boundary: what reads the value here recomposes alone, not the screen around it. */
@Composable
internal fun AloLiveText(text: () -> String, content: @Composable (String) -> Unit) = content(text())
