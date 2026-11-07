package com.alo.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// The secret chat's composer records like an ordinary chat (Telegram): a tap switches voice / circle, a hold records,
// sliding up locks, the locked pause keeps a draft, the green orb sends, sliding left cancels. Real touches; the app's
// actions are answered by a fake that only flips the recording state.
@RunWith(AndroidJUnit4::class)
class AloSecretComposerGestureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<AloNativeAppAction>()
    private var state by mutableStateOf(
        AloNativeAppUiState(
            secretChats = listOf(AloSecretChat(id = 1, status = "active", role = "started", peerUserId = 2, peerName = "Тест Друг")),
            activeSecretChatId = 1
        )
    )

    private fun host() {
        compose.setContent {
            AloNativeSecretChatScreen(state = state) { action ->
                actions += action
                state = when (action) {
                    is AloNativeAppAction.StartSecretRecording ->
                        state.copy(secretCaptureState = AloChatCaptureState.Recording, secretRecorderMode = action.mode)
                    is AloNativeAppAction.StopSecretRecording -> state.copy(secretCaptureState = AloChatCaptureState.Idle)
                    else -> state
                }
            }
        }
    }

    private fun snap(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("shots")!!
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun holdLockPauseAndSend() {
        host()
        val mic = compose.onNodeWithTag("alo.secret.record")
        mic.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(AloNativeAppAction.StartSecretRecording(AloChatRecordMode.Voice), actions.last())
        compose.onNodeWithContentDescription("Зафиксировать запись").assertExists()
        snap("secret-held")
        // Up by more than Telegram's 57dp locks, and letting go keeps recording.
        mic.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -80.dp.toPx())) }
        mic.performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithText("ОТМЕНА").assertExists()
        compose.onNodeWithContentDescription("Завершить и прослушать запись").assertExists()
        snap("secret-locked")
        compose.onNodeWithContentDescription("Отправить запись").performClick()
        compose.waitForIdle()
        assertEquals(AloNativeAppAction.StopSecretRecording(cancel = false, send = true), actions.last())
        // Locked again, the pause keeps the clip as a draft instead of sending it.
        mic.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        mic.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, -80.dp.toPx())); up() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Завершить и прослушать запись").performClick()
        compose.waitForIdle()
        assertEquals(AloNativeAppAction.StopSecretRecording(cancel = false), actions.last())
    }

    @Test
    fun releaseSendsSlideCancelsTapSwitchesToCircle() {
        host()
        val mic = compose.onNodeWithTag("alo.secret.record")
        mic.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        mic.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(AloNativeAppAction.StopSecretRecording(cancel = false, send = true), actions.last())
        mic.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        // Telegram: 35 % of the width, at most 140dp, to the left cancels.
        mic.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-160.dp.toPx(), 0f)) }
        compose.waitForIdle()
        mic.performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(AloNativeAppAction.StopSecretRecording(cancel = true), actions.last())
        // A tap switches the button to the round camera; a hold then starts a circle.
        mic.performClick()
        compose.waitForIdle()
        assertTrue(state.secretCaptureState == AloChatCaptureState.Idle)
        mic.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertTrue(actions.contains(AloNativeAppAction.StartSecretRecording(AloChatRecordMode.VideoCircle)))
        snap("secret-circle-held")
        mic.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(-160.dp.toPx(), 0f)); up() }
        compose.waitForIdle()
    }
}
