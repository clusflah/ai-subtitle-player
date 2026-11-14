package com.aisub.player

import org.junit.Assert.assertFalse
import org.junit.Test

/** 验证多端默认配置：默认关闭液态玻璃，也不在未点击时自动启动 AI 字幕识别。 */
class SettingsDefaultsTest {
    @Test
    fun defaultsDisableLiquidGlassAndAutoAsr() {
        val defaults = AppSettings()
        assertFalse(defaults.liquidGlass)
        assertFalse(defaults.dlAutoAsr)
    }
}
