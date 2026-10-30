package com.aisub.player.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineTranslatorTest {

    @Test
    fun targetLanguageNamesMapToCodes() {
        assertEquals("zh", OfflineTranslator.targetCode("简体中文"))
        assertEquals("zh_hant", OfflineTranslator.targetCode("繁體中文"))
        assertEquals("en", OfflineTranslator.targetCode("English"))
        assertEquals("ja", OfflineTranslator.targetCode("日本語"))
        assertEquals("ko", OfflineTranslator.targetCode("한국어"))
        assertEquals("zh_hant", OfflineTranslator.targetCode("zh-TW"))
        assertEquals("fr", OfflineTranslator.targetCode("fr"))
        assertNull(OfflineTranslator.targetCode("火星文"))
    }

    @Test
    fun videoLanguageMapsToSource() {
        assertEquals("", OfflineTranslator.sourceCode("auto"))
        assertEquals("zh", OfflineTranslator.sourceCode("zh-sichuan"))
        assertEquals("zh", OfflineTranslator.sourceCode("yue"))
        assertEquals("pt", OfflineTranslator.sourceCode("pt-br"))
        assertEquals("en", OfflineTranslator.sourceCode("en"))
    }
}
