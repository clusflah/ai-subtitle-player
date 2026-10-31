package com.aisub.player.backup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.aisub.player.ui.PhoneFormServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

// 手机把备份直接发到电视：电视（或另一台手机）打开「接收备份」后在局域网里开一个临时端口，
// · 手机上的译幕通过局域网服务发现（NSD / mDNS）找到它，选中后直接发过去；找不到时可以手动输入电视上显示的地址
// · 也可以用手机扫电视上的二维码，在浏览器里选导出的备份文件上传
// 收到的备份不会自动写入，要在接收端确认导入。

private const val SERVICE_TYPE = "_voxtitle._tcp."
private const val MAX_BYTES = 8 * 1024 * 1024

/** 接收端：开端口、在局域网里广播、等一份备份。 */
class BackupReceiver(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val socket = ServerSocket(0)
    private val token = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val lan = PhoneFormServer.lanAddress()

    /** 手机浏览器扫码打开的上传页；没连局域网时为 null */
    val uploadUrl: String? = lan?.let { "http://$it:${socket.localPort}/$token" }

    /** 手动输入用的地址（IP:端口） */
    val address: String? = lan?.let { "$it:${socket.localPort}" }

    /** 在别的设备的发送列表里显示的名字 */
    val name = "译幕 · ${Build.MODEL}"

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }
    private var registered = false

    init {
        if (lan != null) runCatching {
            val info = NsdServiceInfo().apply { serviceName = name; serviceType = SERVICE_TYPE; port = socket.localPort }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            registered = true
        }
    }

    /** 阻塞等待一份有效的备份，返回原文。close() 后抛出 IOException。 */
    fun await(): String {
        while (true) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                throw IOException("已取消")
            }
            client.use { c -> runCatching { handle(c) }.getOrNull()?.let { return it } }
        }
    }

    private fun handle(c: Socket): String? {
        c.soTimeout = 30_000
        val input = c.getInputStream().buffered()
        val requestLine = readLine(input) ?: return null
        val headers = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
        val method = requestLine.substringBefore(' ')
        val path = requestLine.substringAfter(' ').substringBefore(' ').substringBefore('?').trimEnd('/')
        val fromApp = path == "/push"
        if (!fromApp && path != "/$token") {
            respond(c, 404, "text/plain", "请重新扫描接收端上的二维码")
            return null
        }
        if (method != "POST") {
            respond(c, 200, "text/html", uploadPage())
            return null
        }
        val length = headers.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BYTES) {
            respond(c, 413, "text/plain", "备份文件太大或为空")
            return null
        }
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        val text = bytes.copyOf(read).toString(Charsets.UTF_8)
        val error = runCatching { Backup.parse(text) }.exceptionOrNull()
        if (error != null) {
            respond(c, 400, "text/plain", error.message ?: "备份文件无法读取")
            return null
        }
        respond(c, 200, "text/plain", "已发送到「$name」，请在那边确认导入")
        return text
    }

    fun close() {
        if (registered) runCatching { nsd.unregisterService(registration) }
        registered = false
        runCatching { socket.close() }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(b.toChar())
        }
    }

    private fun respond(c: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 $code OK\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun uploadPage(): String =
        "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>发送备份</title><style>body{font-family:sans-serif;margin:0;padding:20px;background:#f7f7f7;color:#292929;line-height:1.5}" +
            "h1{font-size:20px}input{display:block;width:100%;box-sizing:border-box;margin-top:12px;font-size:16px}" +
            "button{display:block;width:100%;margin-top:22px;padding:14px;font-size:16px;border:0;border-radius:12px;background:#f28c00;color:#fff}" +
            "#m{margin-top:16px}</style></head><body><h1>发送备份到「${PhoneFormServer.esc(name)}」</h1>" +
            "<p>在手机上的译幕里「设置 → 备份与迁移 → 导出备份文件」，再在这里选择那个文件。手机上装了译幕的话，用「发送到电视」更省事。</p>" +
            "<input type='file' id='f' accept='.json,application/json,text/plain'><button id='b'>发送</button><p id='m'></p>" +
            "<script>var m=document.getElementById('m');document.getElementById('b').onclick=function(){" +
            "var f=document.getElementById('f').files[0];if(!f){m.textContent='请先选择备份文件';return}m.textContent='正在发送…';" +
            "f.text().then(function(t){return fetch(location.pathname,{method:'POST',body:t})}).then(function(r){return r.text()})" +
            ".then(function(t){m.textContent=t},function(e){m.textContent='发送失败：'+e})}</script></body></html>"
}

/** 局域网里找到的接收端 */
data class FoundReceiver(val name: String, val address: String)

/** 发送端：在局域网里找打开了「接收备份」的设备。start() 后结果在 [found] 里，用完 stop()。 */
class ReceiverFinder(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _found = MutableStateFlow<List<FoundReceiver>>(emptyList())
    val found: StateFlow<List<FoundReceiver>> = _found.asStateFlow()

    // 旧接口一次只能解析一个服务，排队依次解析
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) = synchronized(pending) {
            pending.addLast(info)
            next()
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            _found.update { list -> list.filter { it.name != info.serviceName } }
        }
    }

    fun start() {
        if (running) return
        running = true
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.onFailure { running = false }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress
                if (host != null && ':' !in host) {
                    val r = FoundReceiver(info.serviceName, "$host:${info.port}")
                    _found.update { list -> list.filter { it.name != r.name } + r }
                }
                done()
            }

            private fun done() = synchronized(pending) {
                resolving = false
                next()
            }
        })
    }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

        /** 把备份发给接收端（address 是 IP:端口，也可以是接收端显示的整个网址）。返回对方的回复。 */
        suspend fun send(address: String, backup: String): String = withContext(Dispatchers.IO) {
            val hostPort = address.trim().removePrefix("http://").substringBefore('/')
            if (hostPort.isBlank() || ':' !in hostPort) throw IOException("地址要写成 IP:端口，例如 192.168.1.5:41234")
            val req = Request.Builder().url("http://$hostPort/push")
                .post(backup.toByteArray().toRequestBody("application/json".toMediaType())).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException(text.ifBlank { "对方返回 HTTP ${resp.code}" })
                text
            }
        }
    }
}
