package com.alo.android

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * A call's video drawn into a TextureView, as Telegram does (TextureViewRenderer). A SurfaceView sits outside the view
 * tree: it ignores Compose's clip, so the small video showed square corners inside its rounded border, and it cannot
 * move smoothly with the finger. A TextureView is an ordinary view, it is clipped, moved and faded like any other.
 * Same calls as org.webrtc.SurfaceViewRenderer; the picture always fills the view (cropped, not letterboxed).
 */
class AloCallRenderer(context: Context) : TextureView(context), TextureView.SurfaceTextureListener, VideoSink {
    private val eglRenderer = EglRenderer("alo-call-video")

    init {
        surfaceTextureListener = this
    }

    fun init(sharedContext: EglBase.Context?, @Suppress("UNUSED_PARAMETER") events: RendererCommon.RendererEvents?) {
        eglRenderer.init(sharedContext, EglBase.CONFIG_PLAIN, GlRectDrawer())
        surfaceTexture?.let { eglRenderer.createEglSurface(it) }
        updateAspectRatio()
    }

    fun setEnableHardwareScaler(@Suppress("UNUSED_PARAMETER") enabled: Boolean) = Unit

    fun setScalingType(@Suppress("UNUSED_PARAMETER") type: RendererCommon.ScalingType) = Unit

    fun setMirror(mirror: Boolean) = eglRenderer.setMirror(mirror)

    fun clearImage() = eglRenderer.clearImage()

    fun release() = eglRenderer.release()

    override fun onFrame(frame: VideoFrame) = eglRenderer.onFrame(frame)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateAspectRatio()
    }

    // EglRenderer crops the frame to this shape, which is the same as SCALE_ASPECT_FILL.
    private fun updateAspectRatio() {
        if (width > 0 && height > 0) eglRenderer.setLayoutAspectRatio(width.toFloat() / height)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        eglRenderer.createEglSurface(surface)
        updateAspectRatio()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = updateAspectRatio()

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        val released = CountDownLatch(1)
        eglRenderer.releaseEglSurface { released.countDown() }
        released.await()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
