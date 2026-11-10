package org.videolan.libvlc.interfaces

import android.view.SurfaceView
import android.view.TextureView

interface IVLCVout {
    fun interface OnNewVideoLayoutListener {
        fun onNewVideoLayout(
            vout: IVLCVout,
            width: Int,
            height: Int,
            visibleWidth: Int,
            visibleHeight: Int,
            sarNum: Int,
            sarDen: Int,
        )
    }

    fun areViewsAttached(): Boolean = false
    fun detachViews() {}
    fun setVideoView(videoSurfaceView: SurfaceView) {}
    fun setVideoView(videoTextureView: TextureView) {}
    fun attachViews(listener: OnNewVideoLayoutListener?) {}
    fun setWindowSize(width: Int, height: Int) {}
}
