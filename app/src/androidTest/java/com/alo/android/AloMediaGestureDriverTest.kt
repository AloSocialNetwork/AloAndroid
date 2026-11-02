package com.alo.android

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real app with real multi-touch events (`adb input` has one finger only). The steps come from the
 * `script` argument, for example `wait:20;tap:540,2240;pinch:540,1200,120,400;dtap:540,1200;drag:540,900,540,1900;shot:a`.
 * Screenshots land in the app's external files folder, `media-gestures/`. Needs a running stand (AloDebugServer).
 */
@RunWith(AndroidJUnit4::class)
class AloMediaGestureDriverTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation get() = instrumentation.uiAutomation

    private fun event(down: Long, action: Int, points: List<Pair<Float, Float>>): MotionEvent {
        val properties = Array(points.size) { index ->
            MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(points.size) { index ->
            MotionEvent.PointerCoords().apply { x = points[index].first; y = points[index].second; pressure = 1f; size = 1f }
        }
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun inject(event: MotionEvent) {
        automation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 16) {
        val down = SystemClock.uptimeMillis()
        inject(event(down, MotionEvent.ACTION_DOWN, listOf(x0 to y0)))
        for (i in 1..steps) {
            val f = i / steps.toFloat()
            inject(event(down, MotionEvent.ACTION_MOVE, listOf(x0 + (x1 - x0) * f to y0 + (y1 - y0) * f)))
            SystemClock.sleep(16)
        }
        inject(event(down, MotionEvent.ACTION_UP, listOf(x1 to y1)))
    }

    private fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        inject(event(down, MotionEvent.ACTION_DOWN, listOf(x to y)))
        inject(event(down, MotionEvent.ACTION_UP, listOf(x to y)))
    }

    private fun pinch(cx: Float, cy: Float, from: Float, to: Float, steps: Int = 16) {
        val down = SystemClock.uptimeMillis()
        val pair = { d: Float -> listOf(cx - d to cy, cx + d to cy) }
        inject(event(down, MotionEvent.ACTION_DOWN, listOf(cx - from to cy)))
        inject(event(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pair(from)))
        for (i in 1..steps) {
            inject(event(down, MotionEvent.ACTION_MOVE, pair(from + (to - from) * i / steps)))
            SystemClock.sleep(16)
        }
        inject(event(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), pair(to)))
        inject(event(down, MotionEvent.ACTION_UP, listOf(cx - to to cy)))
    }

    private fun shot(name: String) {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "media-gestures").apply { mkdirs() }
        val bitmap = automation.takeScreenshot() ?: return
        File(folder, "$name.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    @Test
    fun run() {
        val script = InstrumentationRegistry.getArguments().getString("script").orEmpty()
        val context = instrumentation.targetContext
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        for (step in script.split(';').filter { it.isNotBlank() }) {
            val (name, raw) = step.split(':', limit = 2)
            val numbers = raw.split(',').mapNotNull { it.toFloatOrNull() }
            when (name) {
                "wait" -> SystemClock.sleep((numbers[0] * 1000).toLong())
                "tap" -> tap(numbers[0], numbers[1])
                "dtap" -> { tap(numbers[0], numbers[1]); SystemClock.sleep(90); tap(numbers[0], numbers[1]) }
                "drag" -> line(numbers[0], numbers[1], numbers[2], numbers[3])
                "pinch" -> pinch(numbers[0], numbers[1], numbers[2], numbers[3])
                "shot" -> shot(raw)
            }
        }
    }
}
