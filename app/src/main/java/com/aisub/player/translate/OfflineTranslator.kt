package com.aisub.player.translate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 离线翻译：调用另外安装的开源 App「Offline Translator」（dev.davidv.translator，GPL-3.0），
 * 它在手机上运行 Mozilla Firefox 翻译用的 Bergamot 模型，字幕不会离开手机。
 * 和输入法修改版（fcitx5-android-mod）里的离线翻译是同一种接法。
 *
 * 那个 App 公开了一个绑定服务，这里没有复制它的 AIDL，按公开的接口直接用 Parcel / Binder 调用：
 *   ITranslationService.translate(String text, String from, String to, ITranslationCallback cb)
 *   oneway ITranslationCallback.onTranslationResult(String) / onTranslationError(TranslationError)
 *
 * @param source 视频语言（识别设置里的代码）；"auto" 时让那个 App 自己判断
 */
class OfflineTranslator(context: Context, source: String) : Translator {

    private val app = context.applicationContext
    private val from = sourceCode(source)

    override suspend fun translate(lines: List<String>, context: List<String>, targetLanguage: String): List<String?> {
        if (!isInstalled(app)) throw TranslationException("没有安装 Offline Translator（离线翻译 App），先到它的 GitHub 或 F-Droid 下载安装")
        val to = targetCode(targetLanguage) ?: throw TranslationException("离线翻译不支持翻译成「$targetLanguage」")
        // 一批字幕共用一次连接，逐句翻译（这个引擎按句翻译，不需要上下文）
        return withService { service ->
            lines.map { line ->
                if (line.isBlank()) line
                else translateOne(service, line, to).ifEmpty { null }
            }
        }
    }

    override suspend fun chat(system: String, user: String): String =
        throw TranslationException("离线翻译只能翻译，不能识别角色，请换用通用大模型")

    private suspend fun <T> withService(block: suspend (IBinder) -> T): T {
        val connected = CompletableDeferred<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent(SERVICE_ACTION).setPackage(PACKAGE)
        if (!app.bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
            throw TranslationException("无法连接 Offline Translator，请先打开一次这个 App")
        }
        try {
            val service = try {
                withTimeout(15_000) { connected.await() }
            } catch (e: TimeoutCancellationException) {
                throw TranslationException("连接 Offline Translator 超时，请先打开一次这个 App")
            }
            return block(service)
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }

    private suspend fun translateOne(service: IBinder, text: String, to: String): String {
        val result = CompletableDeferred<String>()
        withContext(Dispatchers.IO) { call(service, text, from, to, Callback(result)) }
        return try {
            // 第一次翻译要加载模型，多等一会儿
            withTimeout(60_000) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw TranslationException("离线翻译超时。请打开 Offline Translator，确认已下载对应的语言包")
        }
    }

    /** ITranslationService.translate：接口里第一个（也是唯一一个）方法 */
    private fun call(service: IBinder, text: String, from: String, to: String, callback: Binder) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR)
            data.writeString(text)
            data.writeString(from)
            data.writeString(to)
            data.writeStrongBinder(callback)
            service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** ITranslationCallback：1 = onTranslationResult(String)，2 = onTranslationError(TranslationError) */
    private class Callback(private val result: CompletableDeferred<String>) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code != FIRST_CALL_TRANSACTION && code != FIRST_CALL_TRANSACTION + 1) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            if (code == FIRST_CALL_TRANSACTION) {
                result.complete(data.readString().orEmpty().trim())
            } else {
                result.completeExceptionally(TranslationException(readError(data)))
            }
            return true
        }

        /** TranslationError { ErrorType type; String language; String message }（尽量读出来） */
        private fun readError(data: Parcel): String = runCatching {
            if (data.readInt() == 0) return@runCatching null
            data.readInt() // Parcelable 的长度头
            val type = data.readByte().toInt()
            val language = data.readString()
            val message = data.readString()
            when (type) {
                0 -> "离线翻译认不出原文的语言，可以在「语音识别」里选定视频语言"
                1 -> "Offline Translator 里还没有下载「${language ?: "原文语言"}」的语言包"
                else -> message
            }
        }.getOrNull() ?: "离线翻译失败"
    }

    companion object {
        const val PACKAGE = "dev.davidv.translator"
        private const val SERVICE_ACTION = "dev.davidv.translator.ITranslationService"
        private const val SERVICE_DESCRIPTOR = "dev.davidv.translator.ITranslationService"
        private const val CALLBACK_DESCRIPTOR = "dev.davidv.translator.ITranslationCallback"

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

        fun isInstalled(context: Context): Boolean =
            runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

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
