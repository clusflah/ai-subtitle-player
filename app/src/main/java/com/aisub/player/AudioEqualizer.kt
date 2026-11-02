package com.aisub.player

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * 应用内均衡器：挂在默认内核的音频会话上，开关、预设、各频段增益都记住，下次打开接着用。
 * 很多手机没有系统均衡器面板（点了没反应），所以不再依赖它。
 */
@UnstableApi
class AudioEqualizer(context: Context, private val player: ExoPlayer) {

    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private var eq: Equalizer? = null

    var enabled: Boolean = prefs.getBoolean("enabled", false)
        private set

    /** 选中的系统预设；-1 表示自定义 */
    var preset: Int = prefs.getInt("preset", -1)
        private set

    init {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                release()
                if (enabled) attach()
            }
        })
        if (enabled) attach()
    }

    /** 拿到（必要时创建）均衡器；设备不支持时返回 null */
    fun attach(): Equalizer? {
        eq?.let { return it }
        val created = runCatching { Equalizer(0, player.audioSessionId) }.getOrNull() ?: return null
        eq = created
        runCatching {
            if (preset in 0 until created.numberOfPresets) created.usePreset(preset.toShort())
            else savedLevels()?.forEachIndexed { i, level ->
                if (i < created.numberOfBands) created.setBandLevel(i.toShort(), level)
            }
            created.enabled = enabled
        }
        return created
    }

    fun setEnabled(on: Boolean) {
        enabled = on
        prefs.edit().putBoolean("enabled", on).apply()
        runCatching { attach()?.enabled = on }
    }

    fun usePreset(index: Int) {
        val e = attach() ?: return
        runCatching { e.usePreset(index.toShort()) }
        preset = index
        prefs.edit().putInt("preset", index).apply()
        saveLevels(e)
    }

    fun setBandLevel(band: Int, level: Short) {
        val e = attach() ?: return
        runCatching { e.setBandLevel(band.toShort(), level) }
        preset = -1
        prefs.edit().putInt("preset", -1).apply()
        saveLevels(e)
    }

    fun reset() {
        val e = attach() ?: return
        runCatching { for (b in 0 until e.numberOfBands) e.setBandLevel(b.toShort(), 0) }
        preset = -1
        prefs.edit().putInt("preset", -1).apply()
        saveLevels(e)
    }

    private fun saveLevels(e: Equalizer) {
        val levels = runCatching { (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()) } }.getOrNull() ?: return
        prefs.edit().putString("levels", levels.joinToString(",")).apply()
    }

    private fun savedLevels(): List<Short>? =
        prefs.getString("levels", null)?.split(',')?.mapNotNull { it.toShortOrNull() }

    fun release() {
        eq?.let { runCatching { it.release() } }
        eq = null
    }
}
