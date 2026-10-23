package com.aisub.player.tts

import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.Role
import com.aisub.player.translate.Translator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTest {

    @Test
    fun targetLanguageToLocale() {
        assertEquals("zh-CN", ttsLocaleFor("简体中文"))
        assertEquals("zh-TW", ttsLocaleFor("繁體中文"))
        assertEquals("en", ttsLocaleFor("English"))
        assertEquals("ja", ttsLocaleFor("日本語"))
        assertEquals("ko", ttsLocaleFor("한국어"))
        assertEquals("fr", ttsLocaleFor("fr"))
        // 两个字母的代码不能误中单词里的片段
        assertNull(ttsLocaleFor("British"))
        assertNull(ttsLocaleFor("克林贡语"))
    }

    @Test
    fun localeMatching() {
        assertTrue(localeMatches("zh-CN", "zh-CN"))
        assertFalse(localeMatches("zh-TW", "zh-CN"))
        assertTrue(localeMatches("en-GB", "en"))
        assertTrue(localeMatches("en_US", "en"))
        assertFalse(localeMatches("eu-ES", "en"))
    }

    private class FakeTranslator(private val replies: List<String>) : Translator {
        val prompts = ArrayList<String>()
        override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String) = lines.map { it }
        override suspend fun chat(system: String, user: String): String {
            prompts += user
            return replies[prompts.size - 1]
        }
    }

    @Test
    fun detectRolesKeepsIdsAcrossBatches() = runBlocking {
        val cues = (1..130).map { Cue(it, it * 1000L, it * 1000L + 900, "line $it") }
        val fake = FakeTranslator(
            listOf(
                """好的：{"roles":[{"id":"r1","name":"Tom","gender":"male"},{"id":"r2","name":"Ann","gender":"female"}],
                   "lines":[{"id":1,"role":"r1"},{"id":2,"role":"r2"},{"id":3,"role":null},{"id":999,"role":"r1"}]}""",
                """{"roles":[{"id":"r1","name":"Tom","gender":"male"},{"id":"r3","name":"店员","gender":"unknown"}],
                   "lines":[{"id":121,"role":"r1"},{"id":122,"role":"r3"},{"id":123,"role":"r9"}]}""",
            ),
        )
        val known = listOf(Role("r1", "旧名字", voice = "v-old", voiceEngine = "AZURE"))
        val (roles, assigned) = RoleDetector.detect(fake, cues, known) { _, _ -> }

        assertEquals(2, fake.prompts.size)
        // 第二批的请求里带着第一批识别出的角色表
        assertTrue(fake.prompts[1].contains("\"Ann\""))
        assertEquals(mapOf(1 to "r1", 2 to "r2", 121 to "r1", 122 to "r3"), assigned)
        assertEquals(listOf("r1", "r2", "r3"), roles.map { it.id })
        // 名字更新为最新判断，之前选的配音保留
        assertEquals("Tom", roles[0].name)
        assertEquals("v-old", roles[0].voice)
        assertEquals("female", roles[1].gender)
    }

    @Test
    fun autoAssignByGenderWithoutRepeats() {
        val voices = listOf(
            TtsVoice("f1", "F1", "zh-CN", "female"),
            TtsVoice("f2", "F2", "zh-CN", "female"),
            TtsVoice("m1", "M1", "zh-CN", "male"),
        )
        val roles = listOf(
            Role("r1", "A", "male"),
            Role("r2", "B", "female"),
            Role("r3", "C", "female"),
            Role("r4", "D", "male"),
            Role("r5", "E", "female", voice = "f1", voiceEngine = "AZURE"),
        )
        val out = RoleDetector.autoAssign(roles, voices, "AZURE").associate { it.id to it.voice }
        assertEquals("m1", out["r1"])
        // f1 已被 r5 选中，先用没人用的 f2
        assertEquals("f2", out["r2"])
        assertEquals("f1", out["r5"])
        // 语音不够时循环使用
        assertTrue(out["r3"] in setOf("f1", "f2"))
        assertEquals("m1", out["r4"])
    }
}
