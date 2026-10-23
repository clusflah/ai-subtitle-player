package com.aisub.player.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** 朗读引擎。 */
enum class TtsEngineType(val label: String, val hint: String) {
    AZURE("Azure 神经语音", "音色自然，可在线获取各语言的全部语音；需要 Azure 语音资源 Key"),
    SYSTEM("系统 TTS", "免费、可离线，音色取决于手机安装的语音引擎"),
}

/** 一个可选的语音。gender 为 male / female / unknown。 */
data class TtsVoice(
    val id: String,
    val name: String,
    val locale: String,
    val gender: String,
    val detail: String = "",
)

interface TtsEngine {
    /** 支持某语言（如 zh-CN、en）的语音；locale 为 null 时返回全部。 */
    suspend fun voices(locale: String?): List<TtsVoice>

    /** 合成一句话，写入 out。voice 为 null 时用该语言的默认语音。 */
    suspend fun synthesize(text: String, voice: String?, locale: String?, out: File)

    fun close() {}
}

/** 语音的 locale 是否属于目标语言：zh-CN 要求完全一致，en 匹配所有 en-xx。 */
internal fun localeMatches(voiceLocale: String, target: String): Boolean {
    val v = voiceLocale.replace('_', '-')
    return if (target.contains('-')) v.equals(target, ignoreCase = true)
    else v.equals(target, ignoreCase = true) || v.startsWith("$target-", ignoreCase = true)
}

/**
 * Azure 语音 REST 接口（与输入法项目的做法一致，不依赖 SDK）：
 * voices/list 拉取语音列表，cognitiveservices/v1 用 SSML 合成 MP3。
 */
class AzureTts(private val key: String, region: String, endpoint: String) : TtsEngine {

    private val base = endpoint.trim().trimEnd('/').ifEmpty { "https://${region.trim()}.tts.speech.microsoft.com" }
        .removeSuffix("/cognitiveservices/v1")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var all: List<TtsVoice>? = null

    private fun checkKey() {
        if (key.isBlank()) throw IOException("请先在设置里填写 Azure 语音 Key（朗读或语音识别里的都可以）")
    }

    override suspend fun voices(locale: String?): List<TtsVoice> = withContext(Dispatchers.IO) {
        checkKey()
        val list = all ?: fetchAll().also { all = it }
        if (locale == null) list else list.filter { v ->
            localeMatches(v.locale, locale) || v.detail.split(',').any { localeMatches(it.trim(), locale) }
        }.sortedBy { if (localeMatches(it.locale, locale)) 0 else 1 }
    }

    private fun fetchAll(): List<TtsVoice> {
        val req = Request.Builder().url("$base/cognitiveservices/voices/list")
            .header("Ocp-Apim-Subscription-Key", key).build()
        val text = http.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("Azure 语音 Key 无效，或与区域不匹配（401）")
            if (!resp.isSuccessful) throw IOException("获取语音列表失败 HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val secondary = o.optJSONArray("SecondaryLocaleList")
            TtsVoice(
                id = o.getString("ShortName"),
                name = o.optString("LocalName").ifEmpty { o.optString("DisplayName") },
                locale = o.optString("Locale"),
                gender = o.optString("Gender").lowercase().let { if (it == "male" || it == "female") it else "unknown" },
                // 多语言语音还能说其他语言，放在 detail 里供筛选
                detail = secondary?.let { s -> (0 until s.length()).joinToString(",") { s.getString(it) } }.orEmpty(),
            )
        }
    }

    override suspend fun synthesize(text: String, voice: String?, locale: String?, out: File) = withContext(Dispatchers.IO) {
        checkKey()
        val name = voice ?: voices(locale).firstOrNull()?.id ?: throw IOException("没有找到 $locale 的语音")
        val lang = name.split('-').take(2).joinToString("-")
        val ssml = "<speak version='1.0' xml:lang='$lang'><voice name='${xml(name)}'>${xml(text)}</voice></speak>"
        val req = Request.Builder().url("$base/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key)
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .header("User-Agent", "Voxtitle")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.code == 401 -> throw IOException("Azure 语音 Key 无效，或与区域不匹配（401）")
                resp.code == 429 -> throw IOException("Azure 朗读请求太频繁（429）")
                !resp.isSuccessful -> throw IOException("Azure 朗读失败 HTTP ${resp.code}")
            }
            val tmp = File(out.path + ".tmp")
            resp.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(out)
        }
        Unit
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/** 系统自带的 TextToSpeech：合成到 WAV 文件。语音列表来自手机里安装的引擎。 */
class SystemTts(context: Context) : TtsEngine {

    private val app = context.applicationContext
    private val lock = Mutex()
    private var tts: TextToSpeech? = null
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private suspend fun ready(): TextToSpeech {
        tts?.let { return it }
        val t = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<TextToSpeech?> { cont ->
                var engine: TextToSpeech? = null
                engine = TextToSpeech(app) { status ->
                    if (status == TextToSpeech.SUCCESS) cont.resume(engine)
                    else cont.resume(null)
                }
            }
        } ?: throw IOException("手机上没有可用的 TTS 引擎，可以在系统设置里安装（如 Google 文字转语音）")
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { waiting.remove(utteranceId)?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { waiting.remove(utteranceId)?.complete(false) }
            override fun onError(utteranceId: String, errorCode: Int) { waiting.remove(utteranceId)?.complete(false) }
        })
        tts = t
        return t
    }

    override suspend fun voices(locale: String?): List<TtsVoice> = lock.withLock {
        val t = ready()
        (t.voices ?: emptySet())
            .filter { v -> TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features }
            .filter { v -> locale == null || localeMatches(v.locale.toLanguageTag(), locale) }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            .map { v ->
                val name = v.name.lowercase()
                TtsVoice(
                    id = v.name,
                    name = v.name,
                    locale = v.locale.toLanguageTag(),
                    gender = when {
                        "female" in name -> "female"
                        "male" in name -> "male"
                        else -> "unknown"
                    },
                    detail = if (v.isNetworkConnectionRequired) "需联网" else "离线",
                )
            }
    }

    override suspend fun synthesize(text: String, voice: String?, locale: String?, out: File) = lock.withLock {
        val t = ready()
        val v = voice?.let { id -> t.voices?.firstOrNull { it.name == id } }
        if (v != null) t.voice = v else if (locale != null) t.language = Locale.forLanguageTag(locale)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        waiting[id] = done
        if (t.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) {
            waiting.remove(id)
            throw IOException("系统 TTS 合成失败")
        }
        val ok = withTimeout(30_000) { done.await() }
        if (!ok || !out.exists()) throw IOException("系统 TTS 合成失败")
    }

    override fun close() {
        tts?.shutdown()
        tts = null
    }
}

/**
 * 把设置里的目标语言（如「简体中文」「English」）换成语音的 locale。
 * 认不出来时返回 null，语音列表不做筛选。
 */
fun ttsLocaleFor(targetLanguage: String): String? {
    val t = targetLanguage.trim().lowercase()
    val table = listOf(
        listOf("繁體", "繁体", "正體", "台湾", "臺灣", "zh-tw", "zh-hant") to "zh-TW",
        listOf("粤语", "粵語", "廣東話", "广东话", "cantonese", "zh-hk") to "zh-HK",
        listOf("中文", "汉语", "漢語", "简体", "普通话", "chinese", "mandarin", "zh") to "zh-CN",
        listOf("english", "英语", "英文", "en") to "en",
        listOf("日本", "日语", "日文", "japanese", "ja") to "ja",
        listOf("한국", "韩语", "韩文", "korean", "ko") to "ko",
        listOf("français", "french", "法语", "法文", "fr") to "fr",
        listOf("deutsch", "german", "德语", "德文", "de") to "de",
        listOf("español", "spanish", "西班牙", "es") to "es",
        listOf("русский", "russian", "俄语", "俄文", "ru") to "ru",
        listOf("português", "portuguese", "葡萄牙", "pt") to "pt",
        listOf("italiano", "italian", "意大利", "it") to "it",
        listOf("tiếng việt", "vietnamese", "越南", "vi") to "vi",
        listOf("ไทย", "thai", "泰语", "th") to "th",
    )
    for ((keys, locale) in table) {
        // 两个字母的语言代码只按完整代码匹配，避免 "it" 误中 "british"
        if (keys.any { k -> if (k.length <= 2 && k.all { it in 'a'..'z' }) t == k || t.startsWith("$k-") else k in t }) return locale
    }
    return null
}
