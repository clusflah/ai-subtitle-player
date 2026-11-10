package com.aisub.player.remote.cloud

import android.net.Uri
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.checkName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * Dropbox。登录用 PKCE：浏览器授权后 Dropbox 显示一段授权码，粘贴回应用即可（不需要回调地址和密钥）。
 * 需要用户在 Dropbox App Console 建一个应用，填入 App key。
 * creds：client_id / access_token / refresh_token；定位符是路径（根目录为空字符串）。
 */
class DropboxClient(server: Server) : OAuthCloudClient(server) {

    override val tokenUrl = TOKEN_URL

    private fun rpc(endpoint: String, args: JSONObject): JSONObject =
        authed { it.url("$API/2/$endpoint").post(args.toString().toRequestBody(JSON)) }

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val items = ArrayList<RemoteItem>()
        var json = rpc("files/list_folder", JSONObject().put("path", path).put("limit", 2000))
        while (true) {
            val arr = json.optJSONArray("entries")
            for (i in 0 until (arr?.length() ?: 0)) {
                val f = arr!!.getJSONObject(i)
                val name = f.optString("name")
                val p = f.optString("path_display").ifEmpty { f.optString("path_lower") }
                when (f.optString(".tag")) {
                    "folder" -> items += RemoteItem(name, p, isDir = true)
                    "file" -> items += RemoteItem(name, p, isDir = false, size = f.optLong("size"), playUrl = playUri(p, name))
                }
            }
            if (!json.optBoolean("has_more")) break
            json = rpc("files/list_folder/continue", JSONObject().put("cursor", json.getString("cursor")))
        }
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** 临时直链 4 小时有效，不需要鉴权头。 */
    override fun resolve(fileRef: String): ResolvedLink {
        val link = rpc("files/get_temporary_link", JSONObject().put("path", fileRef)).optString("link")
        if (link.isEmpty()) throw IOException("Dropbox：没有拿到下载地址")
        return ResolvedLink(link, validMs = 3 * 60 * 60_000L)
    }

    override suspend fun rename(parent: String, item: RemoteItem, newName: String) = withContext(Dispatchers.IO) {
        val to = item.path.substringBeforeLast('/') + "/" + checkName(newName)
        rpc("files/move_v2", JSONObject().put("from_path", item.path).put("to_path", to))
        Unit
    }

    /** Dropbox 删除后 30 天内可在网页上恢复。 */
    override suspend fun delete(parent: String, items: List<RemoteItem>) = withContext(Dispatchers.IO) {
        for (item in items) rpc("files/delete_v2", JSONObject().put("path", item.path))
    }

    override suspend fun mkdir(parent: String, name: String) = withContext(Dispatchers.IO) {
        rpc("files/create_folder_v2", JSONObject().put("path", parent.trimEnd('/') + "/" + checkName(name)))
        Unit
    }

    companion object {
        private const val API = "https://api.dropboxapi.com"
        private const val TOKEN_URL = "$API/oauth2/token"
        private val JSON = "application/json".toMediaType()

        /** 授权页地址（不带回调地址时 Dropbox 会直接显示授权码）。 */
        fun authorizeUrl(appKey: String, pkce: Pkce): String =
            Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
                .appendQueryParameter("client_id", appKey)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("token_access_type", "offline")
                .appendQueryParameter("code_challenge", pkce.challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build().toString()

        /** 用授权码换 token，返回凭据 JSON。 */
        fun exchangeCode(appKey: String, code: String, pkce: Pkce): String {
            val json = try {
                postTokenForm(
                    TOKEN_URL,
                    mapOf("code" to code.trim(), "grant_type" to "authorization_code", "client_id" to appKey, "code_verifier" to pkce.verifier),
                )
            } catch (e: OAuthError) {
                throw IOException("Dropbox 授权失败：${e.message}（授权码只能用一次，请重新获取）")
            }
            return JSONObject().put("client_id", appKey)
                .put("access_token", json.getString("access_token"))
                .put("refresh_token", json.optString("refresh_token")).toString()
        }
    }
}
