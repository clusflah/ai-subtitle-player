package com.aisub.player.remote.cloud

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
 * OneDrive（Microsoft Graph）。登录用设备码：在任意浏览器打开 microsoft.com/devicelogin 输入代码。
 * 需要用户自己在 Azure 注册一个"公共客户端"应用（免费），填入应用程序 ID。
 * creds：client_id / access_token / refresh_token；目录定位符是 driveItem ID，根目录为空。
 */
class OneDriveClient(server: Server) : OAuthCloudClient(server) {

    override val tokenUrl = TOKEN_URL
    override val scope = SCOPE

    private fun childrenUrl(id: String) =
        if (id.isEmpty() || id == "root") "$GRAPH/me/drive/root/children" else "$GRAPH/me/drive/items/$id/children"

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val items = ArrayList<RemoteItem>()
        var url: String? = childrenUrl(path) + "?\$top=500&\$select=id,name,size,folder,file"
        while (url != null) {
            val json = authed { it.url(url!!).get() }
            val arr = json.optJSONArray("value")
            for (i in 0 until (arr?.length() ?: 0)) {
                val f = arr!!.getJSONObject(i)
                val id = f.getString("id")
                val name = f.optString("name")
                if (f.has("folder")) items += RemoteItem(name, id, isDir = true)
                else items += RemoteItem(name, id, isDir = false, size = f.optLong("size"), playUrl = playUri(id, name))
            }
            url = json.optString("@odata.nextLink").ifEmpty { null }
        }
        items.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    /** downloadUrl 是预签名的临时地址，播放器直接访问，不需要再带 token。 */
    override fun resolve(fileRef: String): ResolvedLink {
        val json = authed { it.url("$GRAPH/me/drive/items/$fileRef?\$select=id,@microsoft.graph.downloadUrl").get() }
        val url = json.optString("@microsoft.graph.downloadUrl").ifEmpty { throw IOException("OneDrive：没有拿到下载地址") }
        return ResolvedLink(url, validMs = 40 * 60_000L)
    }

    override suspend fun rename(parent: String, item: RemoteItem, newName: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", checkName(newName)).toString().toRequestBody(JSON)
        authed { it.url("$GRAPH/me/drive/items/${item.path}").patch(body) }
        Unit
    }

    /** 移到 OneDrive 回收站。 */
    override suspend fun delete(parent: String, items: List<RemoteItem>) = withContext(Dispatchers.IO) {
        for (item in items) authed { it.url("$GRAPH/me/drive/items/${item.path}").delete() }
    }

    override suspend fun mkdir(parent: String, name: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", checkName(name)).put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "fail").toString().toRequestBody(JSON)
        authed { it.url(childrenUrl(parent)).post(body) }
        Unit
    }

    companion object {
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
        private const val TOKEN_URL = "$AUTHORITY/token"
        private const val SCOPE = "Files.ReadWrite offline_access"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** 申请设备码。QrSession.content 是验证地址，hint 里是要输入的代码。 */
        fun startDeviceCode(clientId: String): QrSession {
            val json = postTokenForm("$AUTHORITY/devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))
            val code = json.getString("user_code")
            return QrSession(
                imageUrl = null,
                content = json.optString("verification_uri").ifEmpty { "https://microsoft.com/devicelogin" },
                hint = "打开 microsoft.com/devicelogin，输入代码 $code 并登录",
                state = mapOf("device_code" to json.getString("device_code"), "client_id" to clientId, "user_code" to code),
            )
        }

        /** 轮询授权结果；成功时返回凭据 JSON。 */
        fun pollDeviceCode(s: QrSession): Pair<QrStatus, String?> {
            val clientId = s.state.getValue("client_id")
            return try {
                val json = postTokenForm(
                    TOKEN_URL,
                    mapOf(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id" to clientId,
                        "device_code" to s.state.getValue("device_code"),
                    ),
                )
                QrStatus.SUCCESS to JSONObject().put("client_id", clientId)
                    .put("access_token", json.getString("access_token"))
                    .put("refresh_token", json.optString("refresh_token")).toString()
            } catch (e: OAuthError) {
                when (e.code) {
                    "authorization_pending", "slow_down" -> QrStatus.WAITING to null
                    "expired_token" -> QrStatus.EXPIRED to null
                    "authorization_declined", "access_denied" -> QrStatus.CANCELED to null
                    else -> throw IOException("OneDrive 授权失败：${e.message}")
                }
            }
        }
    }
}
