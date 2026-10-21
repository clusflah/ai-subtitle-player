package com.aisub.player.remote.cloud

import android.net.Uri
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.checkName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Google Drive（Drive API v3）。登录用"桌面应用"类型的 OAuth 客户端 + 回环地址：
 * 浏览器授权后跳回 http://127.0.0.1:端口，应用自动接收。
 * 需要用户在 Google Cloud Console 建 OAuth 客户端（桌面应用），填入客户端 ID 和密钥。
 * creds：client_id / client_secret / access_token / refresh_token；定位符是文件 ID（根目录 root）。
 */
class GoogleDriveClient(server: Server) : OAuthCloudClient(server) {

    override val tokenUrl = TOKEN_URL

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val parent = path.ifEmpty { "root" }
        val items = ArrayList<RemoteItem>()
        var pageToken = ""
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", "'${parent.replace("'", "\\'")}' in parents and trashed=false")
                .addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "folder,name")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { if (pageToken.isNotEmpty()) addQueryParameter("pageToken", pageToken) }
                .build()
            val json = authed { it.url(url).get() }
            val arr = json.optJSONArray("files")
            for (i in 0 until (arr?.length() ?: 0)) {
                val f = arr!!.getJSONObject(i)
                val id = f.getString("id")
                val name = f.optString("name")
                val mime = f.optString("mimeType")
                when {
                    mime == FOLDER -> items += RemoteItem(name, id, isDir = true)
                    // 谷歌文档、表格等在线文档不能直接下载
                    mime.startsWith("application/vnd.google-apps.") -> Unit
                    else -> items += RemoteItem(name, id, isDir = false, size = f.optString("size").toLongOrNull() ?: 0, playUrl = playUri(id, name))
                }
            }
            pageToken = json.optString("nextPageToken")
        } while (pageToken.isNotEmpty())
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Drive 没有免鉴权的直链：播放和下载时带上 Bearer token（有效约 1 小时）。 */
    override fun resolve(fileRef: String): ResolvedLink {
        // 先访问一次元数据，顺便在 token 过期时刷新
        authed { it.url("$API/files/$fileRef?fields=id&supportsAllDrives=true").get() }
        return ResolvedLink(
            "$API/files/$fileRef?alt=media&supportsAllDrives=true",
            mapOf("Authorization" to "Bearer " + accessToken()),
            validMs = 40 * 60_000L,
        )
    }

    override suspend fun rename(parent: String, item: RemoteItem, newName: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", checkName(newName)).toString().toRequestBody(JSON)
        authed { it.url("$API/files/${item.path}?supportsAllDrives=true").patch(body) }
        Unit
    }

    /** 移到 Google Drive 回收站。 */
    override suspend fun delete(parent: String, items: List<RemoteItem>) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("trashed", true).toString().toRequestBody(JSON)
        for (item in items) authed { it.url("$API/files/${item.path}?supportsAllDrives=true").patch(body) }
    }

    override suspend fun mkdir(parent: String, name: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", checkName(name)).put("mimeType", FOLDER)
            .put("parents", JSONArray().put(parent.ifEmpty { "root" })).toString().toRequestBody(JSON)
        authed { it.url("$API/files?supportsAllDrives=true").post(body) }
        Unit
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val FOLDER = "application/vnd.google-apps.folder"
        private const val SCOPE = "https://www.googleapis.com/auth/drive"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun authorizeUrl(clientId: String, redirectUri: String, pkce: Pkce): String =
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .appendQueryParameter("code_challenge", pkce.challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build().toString()

        fun exchangeCode(clientId: String, clientSecret: String, code: String, redirectUri: String, pkce: Pkce): String {
            val json = try {
                postTokenForm(
                    TOKEN_URL,
                    mapOf(
                        "code" to code, "client_id" to clientId, "client_secret" to clientSecret,
                        "redirect_uri" to redirectUri, "grant_type" to "authorization_code", "code_verifier" to pkce.verifier,
                    ),
                )
            } catch (e: OAuthError) {
                throw IOException("Google 授权失败：${e.message}")
            }
            return JSONObject().put("client_id", clientId).put("client_secret", clientSecret)
                .put("access_token", json.getString("access_token"))
                .put("refresh_token", json.optString("refresh_token")).toString()
        }
    }
}
