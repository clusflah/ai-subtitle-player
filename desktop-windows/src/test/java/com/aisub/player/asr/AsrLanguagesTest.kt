package com.aisub.player.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsrLanguagesTest {

    @Test
    fun normalizeAcceptsCodesWithRegion() {
        assertEquals("pt-br", AsrLanguages.normalize(" pt_BR "))
        assertEquals("sv", AsrLanguages.normalize("SV"))
        assertEquals("fil", AsrLanguages.normalize("fil"))
        assertNull(AsrLanguages.normalize("俄语"))
        assertNull(AsrLanguages.normalize("r"))
    }

    @Test
    fun primaryDropsRegion() {
        assertEquals("pt", AsrLanguages.primary("pt-br"))
        assertEquals("ru", AsrLanguages.primary("ru"))
        assertEquals("auto", AsrLanguages.primary("auto"))
    }

    @Test
    fun azureLocales() {
        assertEquals("ru-RU", AsrLanguages.azureLocale("ru"))
        assertEquals("pt-PT", AsrLanguages.azureLocale("pt-pt"))
        assertEquals("zh-HK", AsrLanguages.azureLocale("yue"))
        assertEquals("zh-CN-sichuan", AsrLanguages.azureLocale(AsrLanguages.SICHUAN))
        // 其他引擎拿到 zh，按普通话识别
        assertEquals("zh", AsrLanguages.primary(AsrLanguages.SICHUAN))
        assertEquals(AsrLanguages.SICHUAN, AsrLanguages.normalize("zh_Sichuan"))
        assertNull(AsrLanguages.azureLocale("auto"))
        assertNull(AsrLanguages.azureLocale("xx"))
    }

    @Test
    fun labels() {
        assertEquals("俄语", AsrLanguages.label("ru"))
        assertEquals("四川话", AsrLanguages.label(AsrLanguages.SICHUAN))
        assertEquals("xq", AsrLanguages.label("xq"))
    }
}
