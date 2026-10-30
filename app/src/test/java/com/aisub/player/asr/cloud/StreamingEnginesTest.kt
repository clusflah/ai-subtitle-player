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
}
