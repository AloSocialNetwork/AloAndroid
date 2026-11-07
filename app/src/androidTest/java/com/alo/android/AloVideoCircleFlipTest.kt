package com.alo.android

import android.Manifest
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// Flipping the camera while a circle is being recorded must keep the same recording going (it used to end it and close
// the recorder). Needs a device or emulator with both a front and a back camera.
@RunWith(AndroidJUnit4::class)
class AloVideoCircleFlipTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun grantPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = instrumentation.targetContext.packageName
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(pkg, it)
        }
    }

    @Test
    fun flippingTheCameraKeepsRecording() {
        var cancelled = false
        var recorded: Pair<String, Int>? = null
        compose.setContent {
            VideoCircleRecorderOverlay(
                onCancel = { cancelled = true },
                onRecorded = { uri, seconds -> recorded = uri to seconds }
            )
        }
        Thread.sleep(2_500)
        repeat(3) {
            compose.onNodeWithContentDescription("Сменить камеру").performClick()
            Thread.sleep(2_000)
            compose.waitForIdle()
            assertTrue("recorder closed after flip ${it + 1}", !cancelled && recorded == null)
        }
        // Stop right after a flip, while CameraX is still building the new encoder: this used to crash the app.
        compose.onNodeWithContentDescription("Сменить камеру").performClick()
        compose.onNodeWithContentDescription("Отправить кружок").performClick()
        compose.waitUntil(timeoutMillis = 10_000) { recorded != null || cancelled }
        assertTrue("circle was cancelled instead of sent", !cancelled)
        val (uri, seconds) = recorded!!
        assertTrue("duration $seconds", seconds >= 6)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(Uri.parse(uri).path)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
        val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        assertNotNull(retriever.getFrameAtTime(millis * 1000 - 300_000))
        retriever.release()
        assertEquals("square circle", width, height)
        assertTrue("file duration $millis ms", millis >= 6_000)
        android.util.Log.i("AloVideoCircleFlipTest", "recorded $uri ${width}x$height ${millis}ms, reported $seconds s")
    }

    @Test
    fun cancellingRightAfterAFlipDoesNotCrash() {
        var cancelled = false
        var recorded: Pair<String, Int>? = null
        compose.setContent {
            VideoCircleRecorderOverlay(
                onCancel = { cancelled = true },
                onRecorded = { uri, seconds -> recorded = uri to seconds }
            )
        }
        Thread.sleep(2_500)
        compose.onNodeWithContentDescription("Сменить камеру").performClick()
        compose.onNodeWithContentDescription("Отменить кружок").performClick()
        compose.waitUntil(timeoutMillis = 12_000) { recorded != null || cancelled }
        assertTrue("cancel must not send", cancelled && recorded == null)
        Thread.sleep(2_000)
    }

    // The ordinary chat drives the circle from the composer's held mic (Telegram): release sends at once, even right
    // after a flip; the overlay then has no buttons of its own but the flip and the flash.
    @Test
    fun composerReleaseRightAfterAFlipSends() {
        var cancelled = false
        var previewed: Pair<String, Int>? = null
        var sent: Pair<String, Int>? = null
        val control = AloCircleRecordingControl()
        compose.setContent {
            VideoCircleRecorderOverlay(
                onCancel = { cancelled = true },
                onRecorded = { uri, seconds -> previewed = uri to seconds },
                onRecordedSend = { uri, seconds -> sent = uri to seconds },
                control = control
            )
        }
        Thread.sleep(3_500)
        assertEquals(0, compose.onAllNodesWithContentDescription("Отправить кружок").fetchSemanticsNodes().size)
        compose.onNodeWithContentDescription("Сменить камеру").performClick()
        compose.runOnIdle { control.send(AloCircleCommand.Send) }
        compose.waitUntil(timeoutMillis = 12_000) { sent != null || previewed != null || cancelled }
        assertTrue("release must send: cancelled=$cancelled previewed=$previewed", sent != null && !cancelled && previewed == null)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(Uri.parse(sent!!.first).path)
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
        retriever.release()
        assertEquals("square circle", width, height)
    }

    @Test
    fun composerCancelRightAfterAFlipDoesNotCrash() {
        var cancelled = false
        var recorded = false
        val control = AloCircleRecordingControl()
        compose.setContent {
            VideoCircleRecorderOverlay(
                onCancel = { cancelled = true },
                onRecorded = { _, _ -> recorded = true },
                onRecordedSend = { _, _ -> recorded = true },
                control = control
            )
        }
        Thread.sleep(2_500)
        compose.onNodeWithContentDescription("Сменить камеру").performClick()
        compose.runOnIdle { control.send(AloCircleCommand.Cancel) }
        compose.waitUntil(timeoutMillis = 12_000) { recorded || cancelled }
        assertTrue("cancel must not send", cancelled && !recorded)
        Thread.sleep(2_000)
    }
}
