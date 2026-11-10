package com.aisub.player.translate

import android.content.Context

/**
 * 离线翻译（Android Offline Translator App）：
 * Windows 桌面端不具备 Android Binder IPC 环境，提示用户使用本地模型（Ollama/LM Studio）或云端引擎。
 */
class OfflineTranslator(context: Context, source: String) : Translator {

    override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> {
        throw TranslationException("Windows 桌面版不支持 Android 离线翻译 App，请在设置中选择「本地模型」（如 Ollama/LM Studio）或云端翻译引擎")
    }

    override suspend fun chat(system: String, user: String): String =
        throw TranslationException("Windows 桌面版不支持 Android 离线翻译 App")

    companion object {
        const val PACKAGE = "dev.davidv.translator"

        /** 设置里的目标语言（名字或代码）→ Offline Translator 的语言代码 */
        private val TARGETS = mapOf(
            "简体中文" to "zh", "中文" to "zh", "汉语" to "zh", "繁體中文" to "zh_hant", "繁体中文" to "zh_hant",
            "english" to "en", "英语" to "en", "英文" to "en", "日本語" to "ja", "日语" to "ja", "日文" to "ja",
            "한국어" to "ko", "韩语" to "ko", "français" to "fr", "法语" to "fr", "deutsch" to "de", "德语" to "de",
            "español" to "es", "西班牙语" to "es", "русский" to "ru", "俄语" to "ru", "português" to "pt", "葡萄牙语" to "pt",
            "italiano" to "it", "意大利语" to "it", "tiếng việt" to "vi", "越南语" to "vi", "ไทย" to "th", "泰语" to "th",
            "bahasa indonesia" to "id", "印尼语" to "id", "العربية" to "ar", "阿拉伯语" to "ar",
        )
        private val CODE = Regex("""^[a-z]{2,3}(_[a-z]+)?$""")

        /** Windows 桌面端不支持 Android 专用的离线翻译 APK */
        fun isInstalled(context: Context): Boolean = false

        fun targetCode(target: String): String? {
            val t = target.trim().lowercase()
            TARGETS[t]?.let { return it }
            if (t.startsWith("zh-hant") || t == "zh-tw" || t == "zh-hk") return "zh_hant"
            if (t.startsWith("zh")) return "zh"
            return t.replace('-', '_').takeIf { CODE.matches(it) }
        }

        /** 识别设置里的视频语言 → 原文语言代码；"" 表示让那个 App 自己判断 */
        fun sourceCode(asrLanguage: String): String {
            val primary = asrLanguage.substringBefore('-').lowercase()
            return when {
                primary == "auto" || primary.isEmpty() -> ""
                primary == "zh" || primary == "yue" -> "zh"
                else -> primary
            }
        }
    }
}
