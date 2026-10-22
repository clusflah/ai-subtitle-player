package com.aisub.player.remote.cloud

import com.aisub.player.remote.ItemMeta
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Emby / Jellyfin 媒体服务器（两者接口兼容）。用服务器地址 + 用户名密码登录。
 * 浏览：媒体库 → 文件夹 / 剧集 → 季 → 集；播放用服务器原始文件（static=true，不转码）。
 * creds：token / user_id / device_id；Server.baseUrl 是服务器地址。定位符是条目 ID，根目录为空。
 */
class EmbyClient(server: Server) : CloudClient(server) {

    private val base get() = server.normalizedBase
    private val token get() = creds.optString("token")

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val url = (base + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("X-Emby-Token", token).header("Accept", "application/json").build()
        return cloudHttp.newCall(req).execute().use { resp ->
            if (resp.code == 401) throw IOException("${server.type.label} 登录已失效，请重新登录")
            if (!resp.isSuccessful) throw IOException("${server.type.label} 请求失败 HTTP ${resp.code}")
            resp.json()
        }
    }

    override suspend fun list(path: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val uid = creds.getString("user_id")
        val json = if (path.isEmpty()) {
            get("/Users/$uid/Views")
        } else {
            get(
                "/Users/$uid/Items",
                mapOf(
                    "ParentId" to path, "SortBy" to "SortName", "SortOrder" to "Ascending",
                    "Fields" to "MediaSources,ProductionYear,Overview,Genres,ProviderIds,PrimaryImageAspectRatio",
                    "EnableImageTypes" to "Primary,Backdrop,Thumb", "ImageTypeLimit" to "1",
                ),
            )
        }
        val arr = json.optJSONArray("Items")
        val items = ArrayList<RemoteItem>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val it = arr!!.getJSONObject(i)
            val id = it.getString("Id")
            val type = it.optString("Type")
            var name = it.optString("Name")
            if (type == "Episode" && it.has("IndexNumber")) name = "第 ${it.optInt("IndexNumber")} 集 · $name"
            val meta = meta(it)
            if (it.optBoolean("IsFolder") || type in FOLDER_TYPES) {
                items += RemoteItem(name, id, isDir = true, meta = meta)
                continue
            }
            val source = it.optJSONArray("MediaSources")?.optJSONObject(0)
            val isAudio = it.optString("MediaType") == "Audio"
            // 名字带上扩展名，列表和下载时才能识别为视频 / 音频
            val ext = (source?.optString("Container") ?: it.optString("Container")).substringBefore(',').ifEmpty { if (isAudio) "mp3" else "mkv" }
            val fileName = "${name.replace('/', '_')}.$ext"
            items += RemoteItem(
                fileName, id, isDir = false, size = source?.optLong("Size") ?: 0,
                playUrl = playUri("$id|${if (isAudio) "Audio" else "Videos"}", fileName), meta = meta,
            )
        }
        if (path.isEmpty()) items else items.sortedWith(compareBy { !it.isDir })
    }

    private fun image(id: String, type: String, tag: String, maxHeight: Int) =
        "$base/Items/$id/Images/$type?maxHeight=$maxHeight&quality=90&tag=$tag&api_key=$token"

    /** 海报、年份、评分、外部编号。单集没有自己的封面时用剧集海报。 */
    private fun meta(it: JSONObject): ItemMeta {
        val id = it.getString("Id")
        val type = it.optString("Type")
        val tags = it.optJSONObject("ImageTags")
        val primary = tags?.optString("Primary").orEmpty()
        val poster = when {
            primary.isNotEmpty() -> image(id, "Primary", primary, 450)
            it.optString("SeriesPrimaryImageTag").isNotEmpty() && it.has("SeriesId") ->
                image(it.getString("SeriesId"), "Primary", it.getString("SeriesPrimaryImageTag"), 450)
            tags?.optString("Thumb").orEmpty().isNotEmpty() -> image(id, "Thumb", tags!!.getString("Thumb"), 300)
            else -> null
        }
        val ratio = when {
            primary.isNotEmpty() && it.has("PrimaryImageAspectRatio") -> it.optDouble("PrimaryImageAspectRatio").toFloat()
            primary.isEmpty() && poster != null && it.optString("SeriesPrimaryImageTag").isEmpty() -> 16f / 9f
            type == "Episode" && primary.isNotEmpty() -> 16f / 9f
            type == "CollectionFolder" || type == "UserView" -> 16f / 9f
            else -> 2f / 3f
        }
        val backdrop = it.optJSONArray("BackdropImageTags")?.optString(0)?.takeIf { t -> t.isNotEmpty() }
            ?.let { t -> image(id, "Backdrop", t, 720) }
            ?: it.optJSONArray("ParentBackdropImageTags")?.optString(0)?.takeIf { t -> t.isNotEmpty() && it.has("ParentBackdropItemId") }
                ?.let { t -> image(it.getString("ParentBackdropItemId"), "Backdrop", t, 720) }
        val providers = it.optJSONObject("ProviderIds")
        fun provider(name: String) = providers?.keys()?.asSequence()?.firstOrNull { k -> k.equals(name, ignoreCase = true) }
            ?.let { k -> providers.optString(k) }?.takeIf { v -> v.isNotEmpty() }
        val unplayed = it.optJSONObject("UserData")?.optInt("UnplayedItemCount") ?: 0
        return ItemMeta(
            title = it.optString("Name"),
            posterUrl = poster,
            posterRatio = ratio.takeIf { r -> r > 0.2f && r < 5f } ?: (2f / 3f),
            backdropUrl = backdrop,
            year = it.optInt("ProductionYear").takeIf { y -> y > 0 },
            rating = it.optDouble("CommunityRating", 0.0).takeIf { r -> r > 0 },
            overview = it.optString("Overview"),
            genres = it.optJSONArray("Genres")?.let { g -> (0 until g.length()).map { k -> g.optString(k) } }.orEmpty(),
            kind = when (type) {
                "Movie" -> "movie"
                "Series" -> "tv"
                "Season" -> "season"
                "Episode" -> "episode"
                else -> null
            },
            seriesName = it.optString("SeriesName").takeIf { s -> s.isNotEmpty() },
            season = (if (type == "Season") it.optInt("IndexNumber") else it.optInt("ParentIndexNumber")).takeIf { n -> n > 0 },
            episode = it.optInt("IndexNumber").takeIf { n -> n > 0 && type == "Episode" },
            tmdbId = provider("Tmdb"),
            imdbId = provider("Imdb"),
            badge = when {
                type == "Episode" && it.has("IndexNumber") -> "第 ${it.optInt("IndexNumber")} 集"
                unplayed > 0 -> unplayed.toString()
                else -> null
            },
        )
    }

    /** 原始文件流（不转码），api_key 放在地址里，播放器和下载器都不需要额外请求头。 */
    override fun resolve(fileRef: String): ResolvedLink {
        val (id, kind) = fileRef.split('|').let { it[0] to (it.getOrNull(1) ?: "Videos") }
        return ResolvedLink("$base/$kind/$id/stream?static=true&api_key=$token", validMs = 24 * 60 * 60_000L)
    }

    companion object {
        private val FOLDER_TYPES = setOf("Folder", "CollectionFolder", "Series", "Season", "BoxSet", "MusicAlbum", "MusicArtist", "Playlist", "UserView")

        private fun authHeader(deviceId: String) =
            """MediaBrowser Client="AiSubPlayer", Device="Android", DeviceId="$deviceId", Version="1.0""""

        /** 用户名密码登录，返回凭据 JSON。 */
        fun login(baseUrl: String, username: String, password: String): String {
            val deviceId = UUID.randomUUID().toString()
            val body = JSONObject().put("Username", username).put("Pw", password).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(baseUrl.trim().trimEnd('/') + "/Users/AuthenticateByName")
                .header("X-Emby-Authorization", authHeader(deviceId))
                .header("Authorization", authHeader(deviceId))
                .post(body).build()
            val json = cloudHttp.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 -> throw IOException("用户名或密码错误")
                    !resp.isSuccessful -> throw IOException("登录失败 HTTP ${resp.code}，请检查服务器地址")
                }
                resp.json()
            }
            return JSONObject()
                .put("token", json.getString("AccessToken"))
                .put("user_id", json.getJSONObject("User").getString("Id"))
                .put("device_id", deviceId).toString()
        }
    }
}
