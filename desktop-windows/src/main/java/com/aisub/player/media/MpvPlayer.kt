package com.aisub.player.media

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import com.aisub.player.desktop.DesktopBackendMode
import com.aisub.player.desktop.DesktopExoPlayer

/**
 * Windows 桌面端 MPV / `libplacebo` 色彩映射播放内核：
 * 底层驱动 `DesktopExoPlayer(backendMode = DesktopBackendMode.MPV_LIBPLACEBO)`，
 * 使用 Vulkan `libplacebo`（`apply_dolbyvision=true` + `bt.2390`）动态映射杜比视界 Profile 5 / 7 / 8 与 HDR10，
 * 彻底消除桌面端 VLC 3.x 播放杜比视界时的紫绿偏色问题。
 */
@UnstableApi
class MpvPlayer(
    context: Context,
    dataSourceFactory: DataSource.Factory?,
    private val engine: DesktopExoPlayer = DesktopExoPlayer(context, dataSourceFactory, DesktopBackendMode.MPV_LIBPLACEBO),
) : ExoPlayer by engine {

    var dolbyProfile5Hint: Boolean = false

    var userForceHwdec: Boolean? = null
        private set

    fun setHwdecPreference(forceHwdec: Boolean?) {
        userForceHwdec = forceHwdec
    }

    val detectedDolbyProfile: Int?
        get() = engine.detectedDolbyProfile

    val pipelineSummary: String
        get() = engine.activePipelineDescription.ifBlank { "libplacebo Vulkan · 动态色调映射管线" }

    fun chapters(): List<Chapter> = engine.chapters()
}
