package com.aisub.player.asr.cloud

import com.aisub.player.subtitle.Cue
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/** 用本地 WebSocket 服务器模拟三家的流式协议：检查发出去的包、解析回来的结果。 */
class StreamingEnginesTest {

    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    private fun serve(listener: WebSocketListener): String {
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        return "ws://${server.hostName}:${server.port}"
    }

    /** 送 1 秒音频再结束，收集字幕 */
    private fun run(engine: StreamingCloudEngine): List<Cue> {
        val cues = Collections.synchronizedList(ArrayList<Cue>())
        engine.accept(FloatArray(16_000) { (it % 50) / 100f }) { cues.add(it) }
        engine.finish { cues.add(it) }
        engine.close()
        return cues
    }

    @Test
    fun authFailureIsReported() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        server.start()
        val error = runCatching { run(VolcStreamingTranscriber("key", "", "ws://${server.hostName}:${server.port}")) }.exceptionOrNull()
        assertTrue(error is java.io.IOException && error.message!!.contains("鉴权失败"))
    }

    @Test
    fun volcBinaryProtocol() {
        val headers = Collections.synchronizedList(ArrayList<String>())
        var audioBytes = 0
        val url = serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                headers.add("%02x%02x%02x%02x".format(b[0], b[1], b[2], b[3]))
                val size = ByteBuffer.wrap(b, 4, 4).order(ByteOrder.BIG_ENDIAN).int
                if ((b[1].toInt() shr 4) == 0b0001) {
                    val req = JSONObject(String(b, 8, size))
                    assertEquals(16_000, req.getJSONObject("audio").getInt("rate"))
                    return
                }
                audioBytes += size
                if (b[1].toInt() and 0x02 != 0) {
                    val json = JSONObject().put(
                        "result",
                        JSONObject().put("text", "你好世界。").put(
                            "utterances",
                            org.json.JSONArray().put(
                                JSONObject().put("text", "你好世界。").put("start_time", 100).put("end_time", 900).put("definite", true),
                            ),
                        ),
                    ).toString().toByteArray()
                    // 最后一包：带负序号
                    val reply = ByteBuffer.allocate(12 + json.size).order(ByteOrder.BIG_ENDIAN)
                        .put(0x11).put(0x93.toByte()).put(0x10).put(0).putInt(-2).putInt(json.size).put(json).array()
                    webSocket.send(reply.toByteString())
                }
            }
        })
        val cues = run(VolcStreamingTranscriber("key", "", url))
        assertEquals("11101000", headers.first()) // 完整请求、JSON、不压缩
        assertTrue(headers.drop(1).dropLast(1).all { it == "11200000" }) // 纯音频
        assertEquals("11220000", headers.last()) // 最后一包
        assertEquals(32_000, audioBytes)
        assertEquals(listOf("你好世界。"), cues.map { it.text })
        assertEquals(100L, cues.first().startMs)
    }

    /** 火山假服务器：收到最后一包时给一句结果 */
    private fun volcServer(text: String) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val b = bytes.toByteArray()
            if (b[1].toInt() and 0x02 == 0) return
            // 流式输入模式的结果不一定带 definite
            val json = JSONObject().put(
                "result",
                JSONObject().put("utterances", org.json.JSONArray().put(JSONObject().put("text", text).put("start_time", 100).put("end_time", 900))),
            ).toString().toByteArray()
            webSocket.send(
                ByteBuffer.allocate(12 + json.size).order(ByteOrder.BIG_ENDIAN)
                    .put(0x11).put(0x93.toByte()).put(0x10).put(0).putInt(-2).putInt(json.size).put(json).array().toByteString(),
            )
        }
    }

    @Test
    fun volcFallsBackToV1WhenV2NotGranted() {
        // 第一路连接：资源没开通，服务端回错误帧
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val message = "requested resource not granted".toByteArray()
                    webSocket.send(
                        ByteBuffer.allocate(12 + message.size).order(ByteOrder.BIG_ENDIAN)
                            .put(0x11).put(0xf0.toByte()).put(0x10).put(0).putInt(45_000_030).putInt(message.size).put(message).array().toByteString(),
                    )
                }
            }),
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(volcServer("第二个资源。")))
        server.start()
        val engine = VolcStreamingTranscriber("fallback-key", "", "ws://${server.hostName}:${server.port}")
        val result = engine.recognizeAll(ByteArray(32_000)) { false }
        engine.close()
        assertEquals("volc.seedasr.sauc.duration", server.takeRequest().getHeader("X-Api-Resource-Id"))
        assertEquals("volc.bigasr.sauc.duration", server.takeRequest().getHeader("X-Api-Resource-Id"))
        assertEquals(listOf(Recognized.Sentence("第二个资源。", 100, 900)), result)
    }

    @Test
    fun volcNotGrantedAnywhereIsExplained() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(403).setHeader("X-Api-Status-Code", "45000030").setBody("not granted")) }
        server.start()
        val engine = VolcStreamingTranscriber("none-key", "", "ws://${server.hostName}:${server.port}")
        val error = runCatching { engine.recognizeAll(ByteArray(3_200)) { false } }.exceptionOrNull()
        assertTrue(error?.message, error is java.io.IOException && error.message!!.contains("流式语音识别 2.0"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun geminiLiveProtocol() {
        val kinds = Collections.synchronizedList(ArrayList<String>())
        var audioChunks = 0
        val url = serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when {
                    json.has("setup") -> {
                        val setup = json.getJSONObject("setup")
                        assertEquals("models/gemini-3.8-live", setup.getString("model"))
                        assertTrue(setup.has("inputAudioTranscription"))
                        kinds.add("setup")
                        // 服务端的 JSON 用二进制帧发
                        webSocket.send(JSONObject().put("setupComplete", JSONObject()).toString().toByteArray().toByteString())
                    }
                    json.getJSONObject("realtimeInput").optBoolean("audioStreamEnd") -> {
                        kinds.add("end")
                        fun transcript(text: String, extra: JSONObject.() -> Unit = {}) = webSocket.send(
                            JSONObject().put("serverContent", JSONObject().put("inputTranscription", JSONObject().put("text", text).apply(extra))).toString(),
                        )
                        transcript("你好，") { put("startOffset", "0.200s") }
                        transcript("世界。") { put("endOffset", "0.900s") }
                        transcript("第二句")
                        webSocket.send(JSONObject().put("serverContent", JSONObject().put("turnComplete", true)).toString())
                    }
                    else -> {
                        assertEquals("audio/pcm;rate=16000", json.getJSONObject("realtimeInput").getJSONObject("audio").getString("mimeType"))
                        audioChunks++
                    }
                }
            }
        })
        val cues = run(GeminiStreamingTranscriber("key", "", url.replace("ws://", "http://"), ""))
        assertEquals(listOf("setup", "end"), kinds)
        assertTrue(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.path!!.contains("BidiGenerateContent?key=key"))
        assertEquals(10, audioChunks)
        assertEquals(listOf("你好，世界。", "第二句"), cues.map { it.text })
        // 第一句用服务端给的时间；第二句没给，接在后面估
        assertEquals(200L, cues.first().startMs)
        assertTrue(cues[1].startMs >= 900L)
    }

    @Test
    fun azureSpeechProtocol() {
        val paths = Collections.synchronizedList(ArrayList<String>())
        var firstAudio: ByteArray? = null
        val url = serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                paths.add(text.lineSequence().first())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                val headerLen = ByteBuffer.wrap(b, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt()
                val payload = b.copyOfRange(2 + headerLen, b.size)
                paths.add(String(b, 2, headerLen).lineSequence().first())
                if (firstAudio == null) firstAudio = payload
                if (payload.isEmpty()) {
                    val phrase = JSONObject().put("RecognitionStatus", "Success").put("Offset", 5_000_000).put("Duration", 10_000_000)
                        .put("NBest", org.json.JSONArray().put(JSONObject().put("Display", "안녕하세요.")))
                    webSocket.send("Path: speech.phrase\r\nContent-Type: application/json\r\n\r\n$phrase")
                    webSocket.send("Path: turn.end\r\n\r\n{}")
                }
            }
        })
        val cues = run(AzureStreamingTranscriber("key", "eastasia", false, "ko-KR", hostOverride = url))
        assertTrue(server.takeRequest().path!!.contains("language=ko-KR"))
        assertEquals("Path: speech.config", paths.first())
        assertEquals("RIFF", String(firstAudio!!, 0, 4))
        assertEquals(listOf("안녕하세요."), cues.map { it.text })
        assertEquals(500L, cues.first().startMs)
        assertEquals(1_500L, cues.first().endMs)
    }

    @Test
    fun aliyunDashScopeProtocol() {
        val actions = Collections.synchronizedList(ArrayList<String>())
        var audioBytes = 0
        val url = serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                val header = json.getJSONObject("header")
                actions.add(header.getString("action"))
                val task = header.getString("task_id")
                fun event(name: String, payload: JSONObject = JSONObject()) = webSocket.send(
                    JSONObject().put("header", JSONObject().put("event", name).put("task_id", task)).put("payload", payload).toString(),
                )
                when (header.getString("action")) {
                    "run-task" -> {
                        assertEquals("fun-asr-realtime", json.getJSONObject("payload").getString("model"))
                        event("task-started")
                    }
                    "finish-task" -> {
                        val sentence = JSONObject().put("begin_time", 200).put("end_time", 1_200).put("text", "测试一下。").put("sentence_end", true)
                        event("result-generated", JSONObject().put("output", JSONObject().put("sentence", sentence)))
                        event("task-finished")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                audioBytes += bytes.size
            }
        })
        val cues = run(AliyunStreamingTranscriber("key", url.replace("ws://", "http://"), "zh"))
        assertEquals(listOf("run-task", "finish-task"), actions)
        assertEquals(32_000, audioBytes)
        assertEquals(listOf("测试一下。"), cues.map { it.text })
        assertEquals(200L, cues.first().startMs)
    }

    @Test
    fun geminiLiveStreamPeriodicFlushAndVadConfig() {
        var sawVadConfig = false
        var midStreamFlushes = 0
        val url = serve(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                when {
                    json.has("setup") -> {
                        val vad = json.getJSONObject("setup")
                            .getJSONObject("realtimeInputConfig")
                            .getJSONObject("automaticActivityDetection")
                        sawVadConfig = vad.getInt("silenceDurationMs") <= 500 &&
                            vad.getString("endOfSpeechSensitivity") == "END_SENSITIVITY_HIGH"
                        webSocket.send(JSONObject().put("setupComplete", JSONObject()).toString())
                    }
                    json.getJSONObject("realtimeInput").optBoolean("audioStreamEnd") -> {
                        midStreamFlushes++
                        webSocket.send(
                            JSONObject().put(
                                "serverContent",
                                JSONObject().put("inputTranscription", JSONObject().put("text", "直播背景音乐下的连续解说。")).put("turnComplete", true),
                            ).toString(),
                        )
                    }
                }
            }
        })
        val engine = GeminiStreamingTranscriber("key", "", url.replace("ws://", "http://"), "英语").apply {
            liveStream = true
        }
        val cues = Collections.synchronizedList(ArrayList<Cue>())
        // 先等 setupComplete 握手完成，再连续送入 6 秒音频触发 5.5 秒周期性 audioStreamEnd 催断句
        engine.accept(FloatArray(1_600) { 0.05f }) { cues.add(it) }
        Thread.sleep(150)
        repeat(60) {
            engine.accept(FloatArray(1_600) { 0.05f }) { cues.add(it) }
        }
        val deadline = System.currentTimeMillis() + 2_000L
        while (cues.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(30)
            engine.accept(FloatArray(1_600) { 0.01f }) { cues.add(it) }
        }
        engine.close()
        assertTrue(sawVadConfig)
        assertTrue(midStreamFlushes >= 1)
        assertEquals(listOf("直播背景音乐下的连续解说。"), cues.map { it.text })
    }

    @Test
    fun liveStreamThrowsAfterRepeatedLongFailuresInsteadOfLoopingForever() {
        var openedSessions = 0
        val engine = object : StreamingCloudEngine() {
            override fun openSession(events: Events): Session {
                openedSessions++
                return object : Session() {
                    private var packets = 0
                    override fun send(pcm: ByteArray) {
                        // 模拟每路连接发满 6 秒（> 5000ms）后因无响应或超时失败，验证不会卡在 failures=0 死循环
                        if (++packets >= 60) {
                            events.failed("模拟流式识别连续无响应")
                        }
                    }
                    override fun finish() = events.end()
                    override fun close() {}
                }
            }
        }.apply { liveStream = true }

        val err = runCatching {
            repeat(200) {
                engine.accept(FloatArray(1_600) { 0.05f }) {}
            }
        }.exceptionOrNull()
        engine.close()
        assertTrue("Expected IOException on repeated long failures, got $err", err is java.io.IOException)
        assertEquals(2, openedSessions)
    }

    @Test
    fun effectiveSubtitleDelayClampsNegativeInArrivalMode() {
        val arrivalNeg = com.aisub.player.UiState(
            liveClock = com.aisub.player.live.LiveClock.Arrival(1000L),
            subtitleDelayMs = -5000L,
        )
        val arrivalPos = arrivalNeg.copy(subtitleDelayMs = 2000L)
        val streamNeg = com.aisub.player.UiState(
            liveClock = com.aisub.player.live.LiveClock.Stream,
            subtitleDelayMs = -5000L,
        )
        assertEquals(0L, arrivalNeg.effectiveSubtitleDelayMs)
        assertEquals(2000L, arrivalPos.effectiveSubtitleDelayMs)
        assertEquals(-5000L, streamNeg.effectiveSubtitleDelayMs)
    }
}
