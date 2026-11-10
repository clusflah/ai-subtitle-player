package android.media.audiofx

object AudioEffect {
    const val ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL = "android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL"
    const val EXTRA_AUDIO_SESSION = "android.media.extra.AUDIO_SESSION"
    const val EXTRA_PACKAGE_NAME = "android.media.extra.PACKAGE_NAME"
    const val EXTRA_CONTENT_TYPE = "android.media.extra.CONTENT_TYPE"
    const val CONTENT_TYPE_MOVIE = 1
}

class Equalizer(priority: Int, audioSession: Int) {
    private val centerFreqsMilliHz = intArrayOf(60_000, 230_000, 910_000, 3_600_000, 14_000_000)
    private val presetNames = arrayOf("古典", "舞曲", "民谣", "重金属", "嘻哈", "爵士", "流行", "摇滚", "人声增强")
    private val presetGains = arrayOf(
        shortArrayOf(500, 300, -200, 400, 400),
        shortArrayOf(600, 0, 200, 400, 100),
        shortArrayOf(300, 0, 0, 200, -100),
        shortArrayOf(400, 100, 900, 300, 0),
        shortArrayOf(500, 300, 0, 100, 300),
        shortArrayOf(400, 200, -200, 200, 500),
        shortArrayOf(-100, 200, 500, 100, -200),
        shortArrayOf(500, 300, -100, 300, 500),
        shortArrayOf(-200, 300, 600, 400, 0),
    )

    private val bandLevels = ShortArray(5)

    var enabled: Boolean = false
        set(value) {
            field = value
            com.aisub.player.desktop.DesktopPlayerEngine.applyEqualizer(value, bandLevels)
        }

    val numberOfBands: Short = 5
    val bandLevelRange: ShortArray = shortArrayOf(-1500, 1500)
    val numberOfPresets: Short = presetNames.size.toShort()

    fun getPresetName(preset: Short): String = presetNames.getOrElse(preset.toInt()) { "预设" }

    fun usePreset(preset: Short) {
        val gains = presetGains.getOrNull(preset.toInt()) ?: return
        for (i in bandLevels.indices) {
            bandLevels[i] = gains[i]
        }
        com.aisub.player.desktop.DesktopPlayerEngine.applyEqualizer(enabled, bandLevels)
    }

    fun getCenterFreq(band: Short): Int = centerFreqsMilliHz.getOrElse(band.toInt()) { 1000_000 }

    fun getBandLevel(band: Short): Short = bandLevels.getOrElse(band.toInt()) { 0 }

    fun setBandLevel(band: Short, level: Short) {
        val idx = band.toInt()
        if (idx in bandLevels.indices) {
            bandLevels[idx] = level.coerceIn(-1500, 1500)
            com.aisub.player.desktop.DesktopPlayerEngine.applyEqualizer(enabled, bandLevels)
        }
    }

    fun release() {}
}
