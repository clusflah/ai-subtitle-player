package com.aisub.player.asr.cloud

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Google Gemini · Live 接口（BidiGenerateContent WebSocket）的语音转写：边发音频边收 inputTranscription。
 * 文档：ai.google.dev/api/live
 *
 * live 模型给的转写文字不一定带时间（startOffset / endOffset 是可选的）：带了就用；
 * 没带时按文字到达时已经发出的音频长度往回估，所以字幕时间只是大概。
 * Live 接口是语音对话用的，模型听完一句会回话：让它别说话，回过来的内容不用。
 *
 * @param language 视频里说的语言名（"自动识别"时为空）
 */
class GeminiStreamingTranscriber(
    private val apiKey: String,
    model: String,
    endpoint: String,
    private val language: String,
) : StreamingCloudEngine() {

    private val model = model.trim().ifEmpty { DEFAULT_MODEL }.removePrefix("models/")

    private val url = endpoint.trim().trimEnd('/').ifEmpty { GeminiTranscriber.DEFAULT_ENDPOINT }
        .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
        "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + URLEncoder.encode(apiKey, "UTF-8")

    override fun openSession(events: Events): Session = LiveSession(events)

    private inner class LiveSession(private val events: Events) : Session() {
        /** setupComplete 之前的音频先攒着 */
        private val early = ArrayList<ByteArray>()

        @Volatile
        private var ready = false
        private val readyLatch = java.util.concurrent.CountDownLatch(1)

        @Volatile
        private var finishing = false

        /** 还没凑成一句的转写 */
        private val pending = StringBuilder()
        private var pendingStart = -1L
        private var pendingEnd = -1L

        /** 上一句的结束时间（这路连接里的毫秒） */
        private var lastEnd = 0L
        private val socket: WebSocket

        init {
            socket = http.newWebSocket(
                Request.Builder().url(url).build(),
                object : Listener(events, "Gemini") {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        super.onOpen(webSocket, response)
                        webSocket.send(setup().toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                    // 服务端的 JSON 有时用二进制帧发
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        // 1007 / 1008：模型名、Key、参数不对，重连也没用
                        if (!ready && (code == 1007 || code == 1008)) {
                            events.failed("Gemini 流式识别连接被拒（$code）：$reason。检查 API Key 和 live 模型名", fatal = true)
                            webSocket.close(1000, null)
                        } else super.onClosing(webSocket, code, reason)
                    }
                },
            )
        }

        private fun setup(): JSONObject {
            val instruction = "你只负责听，不要回答、不要说话、不要输出任何内容。" +
                if (language.isNotEmpty()) "音频里说的是$language。" else ""
            return JSONObject().put(
                "setup",
                JSONObject()
                    .put("model", "models/$model")
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")))
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction))))
                    .put("inputAudioTranscription", JSONObject()),
            )
        }

        private fun audio(pcm: ByteArray) = JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", JSONObject().put("data", Base64.getEncoder().encodeToString(pcm)).put("mimeType", "audio/pcm;rate=16000")),
        ).toString()

        override fun send(pcm: ByteArray) {
            synchronized(early) {
                if (!ready) {
                    early.add(pcm)
                    return
                }
            }
            socket.send(audio(pcm))
        }

        override fun finish() {
            finishing = true
            // 音频很短时，可能发完了还没准备好：等准备好、攒着的音频发出去后再结束
            if (!readyLatch.await(10, java.util.concurrent.TimeUnit.SECONDS) || events.ended) return events.end()
            socket.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
        }

        override fun close() {
            socket.cancel()
        }

        private fun handle(text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            if (json.has("setupComplete")) {
                synchronized(early) {
                    ready = true
                    early.forEach { socket.send(audio(it)) }
                    early.clear()
                }
                readyLatch.countDown()
                return
            }
            val content = json.optJSONObject("serverContent") ?: return
            content.optJSONObject("inputTranscription")?.let { t ->
                val piece = t.optString("text")
                if (piece.isNotEmpty()) synchronized(pending) {
                    if (pending.isEmpty()) pendingStart = offsetMs(t.optString("startOffset"))
                    pending.append(piece)
                    offsetMs(t.optString("endOffset")).takeIf { it >= 0 }?.let { pendingEnd = it }
                    // 一句话说完了就先交出去，不等这一轮结束
                    if (piece.trimEnd().lastOrNull() in SENTENCE_END) emit()
                }
            }
            if (content.optBoolean("turnComplete")) {
                synchronized(pending) { emit() }
                if (finishing) events.end()
            }
        }

        /** 交出攒着的一句 */
        private fun emit() {
            val text = pending.toString().trim()
            pending.setLength(0)
            if (text.isEmpty()) return
            // 没给时间：按已经发出的音频往回估（服务端要停顿一会儿才断句）
            val end = (if (pendingEnd >= 0) pendingEnd else sessionAudioMs - LAG_MS).coerceAtLeast(lastEnd + 300)
            val start = (if (pendingStart >= 0) pendingStart else end - estimateMs(text)).coerceIn(lastEnd, end - 300)
            lastEnd = end
            pendingStart = -1
            pendingEnd = -1
            events.result(listOf(Recognized.Sentence(text, start, end)))
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-live"

        /** 文字到达时，已发出的音频比这句话的结尾大约多出的长度 */
        private const val LAG_MS = 1_500L
        private val SENTENCE_END = setOf('。', '！', '？', '.', '!', '?', '…')

        /** Duration 的 JSON 写法："1.500s" */
        internal fun offsetMs(value: String): Long =
            value.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() } ?: -1L

        /** 按字数估一句话说了多久：中日韩一个字约 0.22 秒，其他语言一个词约 0.35 秒 */
        internal fun estimateMs(text: String): Long {
            val cjk = text.count { Character.UnicodeScript.of(it.code) in CJK }
            val words = text.filterNot { Character.UnicodeScript.of(it.code) in CJK }.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
            return (cjk * 220L + words * 350L).coerceIn(600L, 10_000L)
        }

        private val CJK = setOf(
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
        )
    }
}
