package com.aisub.player.remote.cloud

import android.util.Base64
import com.aisub.player.remote.Server
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/** OAuth 2.0 的 PKCE 校验码。 */
class Pkce {
    val verifier: String = ByteArray(48).also { SecureRandom().nextBytes(it) }.let(::b64url)
    val challenge: String = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private companion object {
        fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

/** 向 token 接口提交表单，返回 JSON；出错时给出服务端的说明。 */
internal fun postTokenForm(url: String, form: Map<String, String>): JSONObject {
    val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
    val json = cloudHttp.newCall(Request.Builder().url(url).post(body).build()).execute().use { it.json() }
    if (json.has("error") && !json.has("access_token")) {
        val err = json.opt("error")
        val desc = json.optString("error_description").ifEmpty { if (err is JSONObject) err.toString() else err.toString() }
        throw OAuthError(if (err is String) err else "error", desc)
    }
    return json
}

class OAuthError(val code: String, message: String) : IOException(message)

/**
 * 回环地址授权（Google 桌面应用类型的标准做法）：在本机开一个临时端口，
 * 浏览器授权后跳回 http://127.0.0.1:端口/?code=…，在这里接住授权码。
 */
class LoopbackReceiver {
    private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val redirectUri = "http://127.0.0.1:${socket.localPort}"

    /** 阻塞等待浏览器跳回，返回授权码。 */
    fun awaitCode(timeoutMs: Int = 5 * 60_000): String = socket.use { server ->
        server.soTimeout = timeoutMs
        while (true) {
            server.accept().use { client ->
                val line = client.getInputStream().bufferedReader().readLine().orEmpty()
                val query = line.substringAfter("?", "").substringBefore(" ")
                val params = query.split('&').filter { '=' in it }
                    .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val ok = params["code"] != null
                val html = if (ok) "授权成功，请回到 AI 字幕播放器。" else "授权未完成：${params["error"] ?: "请重试"}"
                val bytes = "<html><meta charset='utf-8'><body style='font-size:20px;padding:40px'>$html</body></html>".toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
                params["code"]?.let { return it }
                params["error"]?.let { throw IOException("授权被拒绝：$it") }
                // 浏览器顺带请求的 favicon 等，忽略继续等
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("unreachable")
    }

    fun cancel() = runCatching { socket.close() }
}

/**
 * 标准 OAuth 网盘的公共部分：access_token 过期（401）时用 refresh_token 刷新一次。
 * creds：client_id / client_secret（可选）/ access_token / refresh_token
 */
abstract class OAuthCloudClient(server: Server) : CloudClient(server) {

    protected abstract val tokenUrl: String
    protected open val scope: String? = null

    @Synchronized
    protected fun accessToken(forceRefresh: Boolean = false): String {
        val cached = creds.optString("access_token")
        if (cached.isNotEmpty() && !forceRefresh) return cached
        val refresh = creds.optString("refresh_token").ifEmpty { throw IOException("${server.type.label} 登录已失效，请重新登录") }
        val form = mutableMapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to creds.optString("client_id"))
        creds.optString("client_secret").takeIf { it.isNotEmpty() }?.let { form["client_secret"] = it }
        scope?.let { form["scope"] = it }
        val json = try {
            postTokenForm(tokenUrl, form)
        } catch (e: OAuthError) {
            throw IOException("${server.type.label} 登录已失效，请重新登录（${e.message}）")
        }
        creds.put("access_token", json.getString("access_token"))
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { creds.put("refresh_token", it) }
        saveCreds()
        return creds.getString("access_token")
    }

    /** 带 Bearer 发请求；401 时刷新 token 重试一次。返回 JSON（无正文时为空对象）。 */
    protected fun authed(build: (Request.Builder) -> Request.Builder): JSONObject {
        repeat(2) { attempt ->
            val req = build(Request.Builder()).header("Authorization", "Bearer " + accessToken(forceRefresh = attempt > 0)).build()
            cloudHttp.newCall(req).execute().use { resp ->
                if (resp.code == 401 && attempt == 0) return@repeat
                val json = resp.json()
                if (!resp.isSuccessful) {
                    val err = json.opt("error")
                    val msg = json.optString("error_summary").takeIf { it.isNotEmpty() } ?: when (err) {
                        is JSONObject -> err.optString("message").ifEmpty { err.toString() }
                        is String -> json.optString("error_description").ifEmpty { err }
                        else -> json.toString().take(200)
                    }
                    throw IOException("${server.type.label}：$msg（HTTP ${resp.code}）")
                }
                return json
            }
        }
        throw IOException("${server.type.label} 登录已失效，请重新登录")
    }

    companion object {
        val JSON_TYPE = "application/json; charset=utf-8"
    }
}
