package com.aisub.player.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom

// 电视上登录网盘：电视大多没有浏览器、用遥控器打字很慢，授权和输入都交给手机完成。
// · 二维码：把授权地址（OneDrive 设备码页面、Plex 授权页等）显示成二维码，手机扫码打开
// · 手机输入页：电视在局域网里开一个临时网页，手机扫码打开，填好客户端 ID、账号、授权码等提交回电视

/** 系统里有没有能打开网页的应用（很多电视没有浏览器，直接 startActivity 会闪退）。 */
internal fun canOpenBrowser(context: Context): Boolean =
    Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).resolveActivity(context.packageManager) != null

/** 二维码图片（白底黑码，四周留白，方便手机识别）。 */
@Composable
internal fun QrCode(content: String, size: Dp = 200.dp) {
    val bitmap = remember(content) {
        runCatching {
            val n = 512
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, n, n, mapOf(EncodeHintType.MARGIN to 2))
            val pixels = IntArray(n * n) { if (matrix[it % n, it / n]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap, contentDescription = "二维码", filterQuality = FilterQuality.None,
            modifier = Modifier.size(size).clip(MaterialTheme.shapes.small).background(Color.White),
        )
    }
}

/** 二维码 + 说明 + 地址（地址也写出来，扫不了时可以在手机浏览器里手动输入）。 */
@Composable
internal fun ScanWithPhone(url: String, caption: String, extra: (@Composable () -> Unit)? = null) {
    val texts: @Composable () -> Unit = {
        Text(caption, style = MaterialTheme.typography.bodyMedium)
        SelectionContainer {
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        extra?.invoke()
    }
    // 对话框够宽时二维码放左边、说明放右边；窄的时候上下排
    BoxWithConstraints {
        if (maxWidth >= 440.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(url, 180.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { texts() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QrCode(url, 160.dp)
                texts()
            }
        }
    }
}

/** 手机输入页上的一个输入框。htmlBefore 是放在输入框前面的说明或链接（HTML）。 */
internal data class PhoneField(
    val key: String,
    val label: String,
    val value: String = "",
    val secret: Boolean = false,
    val htmlBefore: String = "",
)

/**
 * 电视在局域网里开的临时网页：手机扫码打开，填完提交，内容回到电视。
 * 地址里带随机串，同一局域网的其他人猜不到；提交一次后关闭。
 */
internal class PhoneFormServer(
    private val title: String,
    private val fields: List<PhoneField>,
    private val script: String = "",
) {
    private val socket = ServerSocket(0)
    private val token = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    /** 手机要打开的地址；电视没连局域网时为 null */
    val url: String? = lanAddress()?.let { "http://$it:${socket.localPort}/$token" }

    /** 阻塞等待手机提交，返回各输入框的内容。 */
    fun await(): Map<String, String> = socket.use { server ->
        while (true) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                throw IOException("已取消")
            }
            client.use { c ->
                c.soTimeout = 15_000
                runCatching {
                    val input = c.getInputStream().buffered()
                    val requestLine = readLine(input) ?: return@runCatching null
                    val headers = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                    val method = requestLine.substringBefore(' ')
                    val path = requestLine.substringAfter(' ').substringBefore(' ').substringBefore('?')
                    if (path.trimEnd('/') != "/$token") {
                        respond(c, 404, page("页面不存在", "<p>请重新扫描电视上的二维码。</p>"))
                        return@runCatching null
                    }
                    if (method != "POST") {
                        respond(c, 200, formPage())
                        return@runCatching null
                    }
                    val length = headers.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    val body = ByteArray(length.coerceAtMost(64 * 1024)).also { buf ->
                        var read = 0
                        while (read < buf.size) {
                            val n = input.read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                        }
                    }.toString(Charsets.UTF_8)
                    val values = body.split('&').filter { '=' in it }.associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8").trim()
                    }
                    respond(c, 200, page("已发送到电视", "<p>可以关掉这个页面，回到电视继续。</p>"))
                    values
                }.getOrNull()?.let { return it }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }

    fun close() = runCatching { socket.close() }

    /** 读一行请求头（ASCII，去掉 \r\n）；连接关闭时返回 null。正文另按字节读，所以不用 Reader。 */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length < 8192) sb.append(b.toChar())
        }
    }

    private fun respond(c: java.net.Socket, code: Int, html: String) {
        val bytes = html.toByteArray()
        c.getOutputStream().apply {
            write("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun formPage(): String {
        val inputs = fields.joinToString("") { f ->
            f.htmlBefore + "<label>${esc(f.label)}<input name='${esc(f.key)}' value='${esc(f.value)}' " +
                "type='${if (f.secret) "password" else "text"}' autocomplete='off' autocapitalize='off' spellcheck='false'></label>"
        }
        return page(title, "<form method='post'>$inputs<button type='submit'>发送到电视</button></form>", script)
    }

    private fun page(heading: String, body: String, script: String = "") =
        "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>${esc(heading)}</title><style>" +
            "body{font-family:sans-serif;margin:0;padding:20px;background:#f7f7f7;color:#292929;line-height:1.5}" +
            "h1{font-size:20px}label{display:block;margin:14px 0 4px;font-size:14px;color:#5f5f5f}" +
            "input{display:block;width:100%;box-sizing:border-box;font-size:16px;padding:12px;margin-top:4px;border:1px solid #ccc;border-radius:12px;background:#fff}" +
            "button,.btn{display:block;width:100%;box-sizing:border-box;margin-top:22px;padding:14px;font-size:16px;border:0;border-radius:12px;background:#f28c00;color:#fff;text-align:center;text-decoration:none}" +
            ".step{margin-top:18px;font-size:14px}</style></head><body><h1>${esc(heading)}</h1>$body" +
            (if (script.isNotEmpty()) "<script>$script</script>" else "") + "</body></html>"

    companion object {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;")

        /** 电视在局域网里的 IPv4 地址（Wi-Fi 或有线）。 */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}

/**
 * 一次手机输入：开临时网页、显示二维码、等手机提交。
 * active 为 false 时什么都不显示；提交后回调 onValues（在主线程）。
 */
@Composable
internal fun PhoneFormPanel(
    title: String,
    caption: String,
    fields: List<PhoneField>,
    script: String = "",
    onCancel: () -> Unit,
    onValues: (Map<String, String>) -> Unit,
) {
    val server = remember { runCatching { PhoneFormServer(title, fields, script) }.getOrNull() }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(server) { onDispose { server?.close() } }
    LaunchedEffect(server) {
        if (server == null || server.url == null) return@LaunchedEffect
        try {
            val values = withContext(Dispatchers.IO) { server.await() }
            onValues(values)
        } catch (e: IOException) {
            error = e.message
        }
    }
    val url = server?.url
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        if (url == null) {
            Text("电视没有连到局域网（Wi-Fi 或有线），没法用手机输入。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            ScanWithPhone(url, caption) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("等待手机提交…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onCancel) { Text("取消手机输入") }
    }
}

/**
 * 电视上的「用手机输入」按钮：点开后显示二维码，手机上填完的内容自动填回这些输入框。
 * 手机上不显示（直接在输入框里打字）。
 */
@Composable
internal fun PhoneInputButton(fields: List<PhoneField>, title: String = "在手机上输入", onValues: (Map<String, String>) -> Unit) {
    if (!LocalTv.current) return
    var open by remember { mutableStateOf(false) }
    if (!open) {
        OutlinedButton(onClick = { open = true }) { Text("用手机输入") }
    } else {
        PhoneFormPanel(
            title = title,
            caption = "用手机扫码（手机和电视要在同一个局域网），在手机上填写后提交，会自动填到这里。",
            fields = fields,
            onCancel = { open = false },
            onValues = { open = false; onValues(it) },
        )
    }
}

/**
 * 电视上用手机完成「授权码」类 OAuth（Google 回环授权、Dropbox 授权码）：
 * 手机打开电视上的临时网页 → 填客户端 ID（和密钥）→ 点链接去授权 → 把授权后的地址或授权码粘回来提交。
 * authorizeUrl 里用 __CID__ 占位客户端 ID，由网页里的脚本按填写的内容替换。
 */
@Composable
internal fun PhoneOAuthPanel(
    title: String,
    caption: String,
    idField: PhoneField,
    secretField: PhoneField?,
    authorizeUrl: String,
    stepsHtml: String,
    codeField: PhoneField,
    onCancel: () -> Unit,
    onSubmit: suspend (Map<String, String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val link = "<p class='step'>$stepsHtml</p><a id='auth' class='btn' target='_blank' rel='noopener'>打开授权页面</a>"
    val script = "var tpl=${jsString(authorizeUrl)};function upd(){var c=document.getElementsByName(${jsString(idField.key)})[0].value.trim();" +
        "var a=document.getElementById('auth');if(c){a.href=tpl.replace('__CID__',encodeURIComponent(c));a.style.opacity=1}else{a.removeAttribute('href');a.style.opacity=.4}}" +
        "document.getElementsByName(${jsString(idField.key)})[0].addEventListener('input',upd);upd();"
    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Text("正在完成登录…")
        }
        return
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    // attempt 变了重新开一个网页（上一个提交后已经关闭）
    androidx.compose.runtime.key(attempt) {
        PhoneFormPanel(
            title = title, caption = caption,
            fields = listOfNotNull(idField, secretField, codeField.copy(htmlBefore = link + codeField.htmlBefore)),
            script = script,
            onCancel = onCancel,
            onValues = { values ->
                busy = true
                error = null
                scope.launch {
                    try {
                        onSubmit(values)
                    } catch (e: Exception) {
                        error = (e.message ?: e.toString()) + "。请在手机上重新扫码再试一次。"
                        attempt++
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

private fun jsString(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c") + "'"

/** 从粘贴的内容里取授权码：可以是整个跳转地址（…?code=xxx&…），也可以只是授权码本身。 */
internal fun extractAuthCode(pasted: String): String {
    val text = pasted.trim()
    if ("code=" !in text && "error=" !in text) return text.ifEmpty { throw IOException("没有填授权后的地址") }
    val query = text.substringAfter('?', text).substringBefore('#')
    val params = query.split('&').filter { '=' in it }
        .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    params["code"]?.let { return it }
    throw IOException("授权未完成：${params["error"] ?: "地址里没有授权码"}")
}
