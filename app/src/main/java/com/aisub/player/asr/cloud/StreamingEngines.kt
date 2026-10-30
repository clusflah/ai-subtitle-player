package com.aisub.player.asr.cloud

import com.aisub.player.asr.SpeechEngine
import com.aisub.player.subtitle.Cue
import com.aisub.player.subtitle.CueSplitter
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 流式识别的公共部分：通过 WebSocket 边发音频边收结果，一两秒就出字，不用等一段攒满。
 *
 * - 音频按 100 毫秒一包发送，最快 [PACE] 倍速（各家流式接口按实时音频设计，发太快会被拒）；
 * - 每一路连接的时间都从 0 开始，结果加上这路连接开始前已发的音频长度，就是整段音频里的时间；
 * - 连接断了（网络抖动、服务端单次时长上限）下一包音频时自动重连；一直连不上、或者鉴权失败时报错；
 * - 结果按句交出，有逐词时间时按词切成字幕，和分段识别的切法一样。
 *
 * 子类只负责一种协议：开连接、发音频、收结果（见 [Session]）。
 */
abstract class StreamingCloudEngine : SpeechEngine {

    protected val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /** 一路连接。所有回调都在 OkHttp 的线程上。 */
    protected abstract inner class Session {
        /** 发一包 16kHz 单声道 16bit 小端 PCM */
        abstract fun send(pcm: ByteArray)

        /** 音频发完了，请服务端把剩下的结果都给出来 */
        abstract fun finish()

        abstract fun close()
    }

    /** 新开一路连接；结果通过 [Events] 交回，时间相对这路连接的开头。 */
    protected abstract fun openSession(events: Events): Session

    /** 连接上收到的事件 */
    protected inner class Events internal constructor(private val baseMs: Long) {
        @Volatile
        internal var ended = false

        /** 这路连接出过结果 */
        @Volatile
        internal var any = false
        internal val done = CountDownLatch(1)

        fun result(items: List<Recognized>) {
            if (items.isEmpty()) return
            any = true
            failures = 0
            items.forEach { results.add(it.shifted(baseMs)) }
        }

        /** [fatal]：Key 错、服务没开通这类重连也没用的错误 */
        fun failed(message: String, fatal: Boolean = false) {
            lastError = message
            if (fatal) fatalError = message
            end()
        }

        /** 这路连接结束了（服务端处理完、或连接断开） */
        fun end() {
            ended = true
            done.countDown()
        }
    }

    private val results = ConcurrentLinkedQueue<Recognized>()
    private var session: Session? = null
    private var events: Events? = null
    private var nextId = 1

    @Volatile
    private var failures = 0

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var fatalError: String? = null

    @Volatile
    protected var cancelled = false

    /** 已经发出去的样本数 */
    private var sent = 0L
    private val packet = ByteBuffer.allocate(PACKET_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
    private var pacingStart = 0L
    private var pacingFrom = 0L

    override fun accept(samples: FloatArray, onCue: (Cue) -> Unit) {
        for (s in samples) {
            packet.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            if (!packet.hasRemaining()) flushPacket()
        }
        drain(onCue)
    }

    override fun finish(onCue: (Cue) -> Unit) {
        if (packet.position() > 0) flushPacket()
        val e = events
        session?.finish()
        // 等服务端把最后几句给完
        if (e != null && !e.ended) e.done.await(10, TimeUnit.SECONDS)
        session?.close()
        session = null
        drain(onCue)
        // Key 错、没开通，或者这路连接一句没出就出错：说出来，不要悄悄地没有字幕
        fatalError?.let { throw IOException(it) }
        if (e != null && !e.any && !cancelled) lastError?.let { throw IOException(it) }
    }

    override fun cancel() {
        cancelled = true
        session?.close()
        session = null
    }

    override fun close() {
        session?.close()
        session = null
    }

    private fun flushPacket() {
        val bytes = packet.array().copyOf(packet.position())
        packet.clear()
        fatalError?.let { throw IOException(it) }
        if (cancelled) throw IOException("已取消")
        var e = events
        if (session == null || e == null || e.ended) {
            session?.close()
            // 接连几路连接都没出结果就断了：多半是配置或网络有问题，别一直重连
            if (e != null && !e.any && ++failures >= MAX_FAILURES) {
                throw IOException(lastError ?: "流式识别连接失败")
            }
            if (e != null) Thread.sleep(minOf(500L * failures, 3_000L))
            e = Events(sent * 1000 / SAMPLE_RATE)
            events = e
            session = openSession(e)
            pacingStart = System.currentTimeMillis()
            pacingFrom = sent
        }
        pace()
        session?.send(bytes)
        sent += bytes.size / 2
    }

    /** 不比 [PACE] 倍速更快地发（开头允许先冲几秒，第一句字幕早点出来） */
    private fun pace() {
        val audioMs = (sent - pacingFrom) * 1000 / SAMPLE_RATE
        val allowedMs = (System.currentTimeMillis() - pacingStart) * PACE + BURST_MS
        var wait = ((audioMs - allowedMs) / PACE).toLong()
        while (wait > 0 && !cancelled) {
            Thread.sleep(minOf(wait, 100L))
            wait -= 100
        }
    }

    private fun drain(onCue: (Cue) -> Unit) {
        while (true) {
            val r = results.poll() ?: break
            val lines = when (r) {
                is Recognized.Words -> CueSplitter.split(r.tokens, r.endMs)
                is Recognized.Sentence -> CueSplitter.splitProportional(r.text, r.startMs, r.endMs)
                is Recognized.Translated -> listOf(Triple(r.startMs, r.endMs, r.text))
            }
            lines.filter { it.third.isNotBlank() }.forEach { (s, e, t) -> onCue(Cue(nextId++, s, e, t)) }
        }
    }

    private fun Recognized.shifted(ms: Long): Recognized = when (this) {
        is Recognized.Words -> Recognized.Words(tokens.map { it.copy(startMs = it.startMs + ms) }, endMs + ms)
        is Recognized.Sentence -> copy(startMs = startMs + ms, endMs = endMs + ms)
        is Recognized.Translated -> copy(startMs = startMs + ms, endMs = endMs + ms)
    }

    /** 统一的 WebSocket 监听：把握手失败转成好懂的提示 */
    protected abstract inner class Listener(private val events: Events, private val name: String) : WebSocketListener() {
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (cancelled) return events.end()
            val code = response?.code
            val body = runCatching { response?.body?.string() }.getOrNull().orEmpty().take(200)
            when (code) {
                401, 403 -> events.failed("$name 流式识别鉴权失败（HTTP $code）：检查 Key、区域，或者控制台里是否开通了流式（实时）语音识别。$body", fatal = true)
                null -> events.failed("$name 流式识别连接中断：${t.message}")
                else -> events.failed("$name 流式识别连接失败（HTTP $code）：${t.message} $body")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = events.end()

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (code != 1000 && reason.isNotBlank()) events.failed("$name 流式识别连接被关闭：$reason")
            webSocket.close(1000, null)
        }
    }

    companion object {
        const val SAMPLE_RATE = ChunkedCloudEngine.SAMPLE_RATE
        private const val PACKET_SAMPLES = SAMPLE_RATE / 10
        private const val PACE = 2f
        private const val BURST_MS = 3_000f
        private const val MAX_FAILURES = 3

        /** 逐词结果 → 带时间的 token；没有逐词时给整句 */
        internal fun sentence(text: String, start: Long, end: Long, words: List<Triple<String, Long, Long>>): Recognized =
            if (words.size > 1) {
                val texts = CueSplitter.attachPunctuation(words.map { it.first }, text)
                Recognized.Words(ChunkedCloudEngine.spacedTokens(words.mapIndexed { i, w -> Triple(texts[i], w.second, w.third) }).first, end)
            } else Recognized.Sentence(text, start, end)
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }

/** 16kHz 单声道 16bit WAV 头，数据长度填 0（流式，长度未知） */
private fun streamingWavHeader(): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
    put("RIFF".toByteArray()); putInt(0); put("WAVE".toByteArray())
    put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
    put("data".toByteArray()); putInt(0)
}.array()

/**
 * Azure 语音 · 实时语音识别（Speech SDK 用的 WebSocket 协议，会话模式，可以连续识别很长时间）。
 * 文本消息、二进制消息都是"头 + 空行 + 内容"；二进制消息前两个字节是头的长度（大端）。
 *
 * @param region 区域，如 eastasia；Azure 中国区用 [china]
 * @param locale 视频语言，如 ko-KR；流式识别必须指定
 */
class AzureStreamingTranscriber(
    private val apiKey: String,
    private val region: String,
    private val china: Boolean,
    private val locale: String,
    /** 测试用：替换掉 wss://区域.stt.speech… 这一段 */
    private val hostOverride: String? = null,
) : StreamingCloudEngine() {

    override fun openSession(events: Events): Session = AzureSession(events)

    private inner class AzureSession(private val events: Events) : Session() {
        private val requestId = UUID.randomUUID().toString().replace("-", "")
        private var headerSent = false
        private val socket: WebSocket

        init {
            val host = hostOverride ?: ("wss://" + if (china) "$region.stt.speech.azure.cn" else "$region.stt.speech.microsoft.com")
            val url = "$host/speech/recognition/conversation/cognitiveservices/v1?language=$locale&format=detailed&profanity=raw"
            socket = http.newWebSocket(
                Request.Builder().url(url)
                    .header("Ocp-Apim-Subscription-Key", apiKey)
                    .header("X-ConnectionId", UUID.randomUUID().toString().replace("-", ""))
                    .build(),
                object : Listener(events, "Azure") {
                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
                },
            )
            val config = JSONObject().put(
                "context",
                JSONObject()
                    .put("system", JSONObject().put("version", "1.0.00000"))
                    .put("os", JSONObject().put("platform", "Android").put("name", "Android").put("version", ""))
                    .put("device", JSONObject().put("manufacturer", "").put("model", "").put("version", "")),
            )
            socket.send(textMessage("speech.config", "application/json", config.toString()))
        }

        private fun timestamp() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

        private fun textMessage(path: String, type: String, body: String) =
            "Path: $path\r\nX-RequestId: $requestId\r\nX-Timestamp: ${timestamp()}\r\nContent-Type: $type\r\n\r\n$body"

        private fun audioMessage(payload: ByteArray): ByteString {
            val header = "Path: audio\r\nX-RequestId: $requestId\r\nX-Timestamp: ${timestamp()}\r\nContent-Type: audio/x-wav\r\n"
                .toByteArray()
            return ByteBuffer.allocate(2 + header.size + payload.size).order(ByteOrder.BIG_ENDIAN)
                .putShort(header.size.toShort()).put(header).put(payload).array().toByteString()
        }

        override fun send(pcm: ByteArray) {
            // 第一包带上 WAV 头，告诉服务端音频格式
            val payload = if (headerSent) pcm else streamingWavHeader() + pcm
            headerSent = true
            socket.send(audioMessage(payload))
        }

        override fun finish() {
            // 空的音频包表示音频结束
            socket.send(audioMessage(ByteArray(0)))
        }

        override fun close() {
            socket.cancel()
        }

        private fun handle(message: String) {
            val split = message.indexOf("\r\n\r\n")
            if (split < 0) return
            val path = message.substring(0, split).lineSequence()
                .firstOrNull { it.startsWith("Path:", ignoreCase = true) }?.substringAfter(':')?.trim()?.lowercase()
            val body = message.substring(split + 4)
            when (path) {
                "speech.phrase" -> {
                    val json = runCatching { JSONObject(body) }.getOrNull() ?: return
                    if (json.optString("RecognitionStatus") != "Success") return
                    val text = json.optJSONArray("NBest")?.optJSONObject(0)?.optString("Display")
                        ?.ifEmpty { null } ?: json.optString("DisplayText")
                    if (text.isBlank()) return
                    // 时间单位是 100 纳秒
                    val start = json.optLong("Offset") / 10_000
                    val end = start + json.optLong("Duration") / 10_000
                    events.result(listOf(Recognized.Sentence(text, start, end)))
                }
                "turn.end" -> events.end()
            }
        }
    }
}

/**
 * 火山引擎 · 豆包「大模型流式语音识别」（双向流式优化版，二进制协议）。
 * 每个包：4 字节头（版本、头长、消息类型、标志、序列化、压缩）+ 4 字节内容长度 + 内容。
 * 文档：docs.volcengine.com（DoubaoVoice / 大模型流式语音识别 API）。鉴权和录音文件识别一样。
 */
class VolcStreamingTranscriber(
    private val appKey: String,
    private val accessKey: String,
    private val url: String = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async",
) : StreamingCloudEngine() {

    override fun openSession(events: Events): Session = VolcSession(events)

    private inner class VolcSession(private val events: Events) : Session() {
        /** 已经交出去的句子（按开始时间），服务端每次返回全部结果 */
        private val emitted = HashSet<Long>()
        private val socket: WebSocket

        init {
            socket = http.newWebSocket(
                Request.Builder().url(url)
                    .apply {
                        if (accessKey.isBlank()) header("X-Api-Key", appKey)
                        else header("X-Api-App-Key", appKey).header("X-Api-Access-Key", accessKey)
                    }
                    .header("X-Api-Resource-Id", "volc.bigasr.sauc.duration")
                    .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                    .build(),
                object : Listener(events, "火山") {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.toByteArray())
                },
            )
            val request = JSONObject()
                .put("user", JSONObject().put("uid", appKey.take(32)))
                .put("audio", JSONObject().put("format", "pcm").put("codec", "raw").put("rate", 16_000).put("bits", 16).put("channel", 1))
                .put(
                    "request",
                    JSONObject().put("model_name", "bigmodel").put("enable_itn", true).put("enable_punc", true)
                        .put("show_utterances", true),
                )
            socket.send(packet(FULL_CLIENT_REQUEST, 0, JSON, request.toString().toByteArray()))
        }

        private fun packet(type: Int, flags: Int, serialization: Int, payload: ByteArray): ByteString =
            ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
                .put(0x11).put(((type shl 4) or flags).toByte()).put((serialization shl 4).toByte()).put(0)
                .putInt(payload.size).put(payload).array().toByteString()

        override fun send(pcm: ByteArray) {
            socket.send(packet(AUDIO_ONLY, 0, 0, pcm))
        }

        override fun finish() {
            socket.send(packet(AUDIO_ONLY, LAST_PACKET, 0, ByteArray(0)))
        }

        override fun close() {
            socket.cancel()
        }

        private fun handle(data: ByteArray) {
            if (data.size < 8) return
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val headerSize = (data[0].toInt() and 0x0f) * 4
            val type = (data[1].toInt() shr 4) and 0x0f
            val flags = data[1].toInt() and 0x0f
            val gzip = (data[2].toInt() and 0x0f) == 1
            buf.position(headerSize)
            if (type == SERVER_ERROR) {
                val code = buf.int
                val size = buf.int
                val message = String(data, buf.position(), minOf(size, data.size - buf.position()))
                // 4500xxxx 是请求或鉴权的问题，重连没用
                return events.failed("火山流式识别出错（$code）：$message", fatal = code in 45_000_000..45_999_999)
            }
            if (type != FULL_SERVER_RESPONSE) return
            if (flags and 0x01 != 0) buf.int // 序号
            val size = buf.int
            var payload = data.copyOfRange(buf.position(), minOf(buf.position() + size, data.size))
            if (gzip) payload = GZIPInputStream(ByteArrayInputStream(payload)).readBytes()
            val json = runCatching { JSONObject(String(payload)) }.getOrNull()
            json?.optJSONObject("result")?.optJSONArray("utterances").objects()
                .filter { it.optBoolean("definite") && it.optString("text").isNotBlank() }
                .forEach { u ->
                    val start = u.optLong("start_time")
                    if (!emitted.add(start)) return@forEach
                    val words = u.optJSONArray("words").objects().map { w -> Triple(w.optString("text"), w.optLong("start_time"), w.optLong("end_time")) }
                    events.result(listOf(sentence(u.optString("text"), start, u.optLong("end_time"), words)))
                }
            // 负序号 / 最后一包：这次识别结束
            if (flags and 0x02 != 0) events.end()
        }
    }

    private companion object {
        const val FULL_CLIENT_REQUEST = 0b0001
        const val AUDIO_ONLY = 0b0010
        const val FULL_SERVER_RESPONSE = 0b1001
        const val SERVER_ERROR = 0b1111
        const val LAST_PACKET = 0b0010
        const val JSON = 0b0001
    }
}

/**
 * 阿里云百炼 · 实时语音识别（DashScope WebSocket）：run-task → 二进制音频 → finish-task。
 * 中英日用 Fun-ASR 实时版，其他语言用支持更多语种的 Paraformer 实时版。
 * 文档：help.aliyun.com/zh/model-studio/websocket-for-paraformer-real-time-service
 *
 * @param endpoint 百炼接口地址（https://dashscope.aliyuncs.com，国际站 dashscope-intl）
 * @param language 语言代码；"auto" 自动
 */
class AliyunStreamingTranscriber(
    private val apiKey: String,
    endpoint: String,
    private val language: String,
) : StreamingCloudEngine() {

    private val url = endpoint.trim().trimEnd('/').ifEmpty { AliyunFunAsrTranscriber.DEFAULT_ENDPOINT }
        .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/api-ws/v1/inference"

    override fun openSession(events: Events): Session = AliyunSession(events)

    private inner class AliyunSession(private val events: Events) : Session() {
        private val taskId = UUID.randomUUID().toString().replace("-", "")

        /** 任务开始前的音频先攒着 */
        private val early = ArrayList<ByteArray>()

        @Volatile
        private var started = false
        private val startedLatch = CountDownLatch(1)
        private val socket: WebSocket

        init {
            socket = http.newWebSocket(
                Request.Builder().url(url).header("Authorization", "bearer $apiKey").build(),
                object : Listener(events, "阿里") {
                    override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
                },
            )
            val funAsr = language == "auto" || language in setOf("zh", "en", "ja", "yue")
            val parameters = JSONObject().put("format", "pcm").put("sample_rate", 16_000)
            if (!funAsr) parameters.put("language_hints", JSONArray().put(language))
            val run = JSONObject()
                .put("header", JSONObject().put("action", "run-task").put("task_id", taskId).put("streaming", "duplex"))
                .put(
                    "payload",
                    JSONObject().put("task_group", "audio").put("task", "asr").put("function", "recognition")
                        .put("model", if (funAsr) "fun-asr-realtime" else "paraformer-realtime-v2")
                        .put("parameters", parameters).put("input", JSONObject()),
                )
            socket.send(run.toString())
        }

        override fun send(pcm: ByteArray) {
            synchronized(early) {
                if (!started) {
                    early.add(pcm)
                    return
                }
            }
            socket.send(pcm.toByteString())
        }

        override fun finish() {
            // 音频很短时，可能发完了任务还没开始：等它开始，攒着的音频发出去后再结束
            if (!startedLatch.await(10, TimeUnit.SECONDS) || events.ended) return events.end()
            socket.send(
                JSONObject()
                    .put("header", JSONObject().put("action", "finish-task").put("task_id", taskId).put("streaming", "duplex"))
                    .put("payload", JSONObject().put("input", JSONObject()))
                    .toString(),
            )
        }

        override fun close() {
            socket.cancel()
        }

        private fun handle(text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            val header = json.optJSONObject("header") ?: return
            when (header.optString("event")) {
                "task-started" -> {
                    synchronized(early) {
                        started = true
                        early.forEach { socket.send(it.toByteString()) }
                        early.clear()
                    }
                    startedLatch.countDown()
                }
                "result-generated" -> {
                    val s = json.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence") ?: return
                    if (!s.optBoolean("sentence_end") || s.optString("text").isBlank()) return
                    val words = s.optJSONArray("words").objects().map { w ->
                        Triple(w.optString("text"), w.optLong("begin_time"), w.optLong("end_time"))
                    }
                    events.result(listOf(sentence(s.optString("text"), s.optLong("begin_time"), s.optLong("end_time"), words)))
                }
                "task-finished" -> events.end()
                "task-failed" -> {
                    val code = header.optString("error_code")
                    val auth = code.contains("Auth", ignoreCase = true) || code.contains("Access", ignoreCase = true) ||
                        code.contains("Model", ignoreCase = true)
                    events.failed("阿里实时识别出错（$code）：${header.optString("error_message")}", fatal = auth)
                }
            }
        }
    }
}
