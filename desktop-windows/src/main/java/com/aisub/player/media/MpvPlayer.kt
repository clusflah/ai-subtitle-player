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
 * 默认结合 D3D11VA 硬件解码与高速单趟 Vulkan `libplacebo`（`apply_dolbyvision=true` + `bt.2390`）
 * 动态映射杜比视界 Profile 5 / 7 / 8 与 HDR10，兼顾准确色彩与高帧率流畅播放。
 */
@UnstableApi
class MpvPlayer(
    context: Context,
    dataSourceFactory: DataSource.Factory?,
    private val engine: DesktopExoPlayer = DesktopExoPlayer(context, dataSourceFactory, DesktopBackendMode.MPV_LIBPLACEBO),
) : ExoPlayer by engine {

    var dolbyProfile5Hint: Boolean = false

    val userForceHwdec: Boolean?
        get() = engine.hwdecPreference

    fun setHwdecPreference(forceHwdec: Boolean?) {
        engine.updateHwdecPreference(forceHwdec)
    }

    fun setEmbeddedSubtitlesSuppressed(suppressed: Boolean) {
        engine.setEmbeddedSubtitlesSuppressed(suppressed)
    }

    val detectedDolbyProfile: Int?
        get() = engine.detectedDolbyProfile

    val pipelineSummary: String
        get() = engine.activePipelineDescription.ifBlank { "D3D11VA 硬解 + libplacebo Vulkan · 动态色调映射" }

    fun chapters(): List<Chapter> = engine.chapters()
}
