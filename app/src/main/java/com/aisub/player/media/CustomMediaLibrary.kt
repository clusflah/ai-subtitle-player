package com.aisub.player.media

import android.content.Context
import com.aisub.player.AppSettings
import com.aisub.player.LibraryViewModel
import com.aisub.player.meta.Douban
import com.aisub.player.meta.MediaInfo
import com.aisub.player.meta.MetaQuery
import com.aisub.player.meta.MetaRepository
import com.aisub.player.meta.NameParser
import com.aisub.player.remote.OpenListClient
import com.aisub.player.remote.RemoteClient
import com.aisub.player.remote.RemoteItem
import com.aisub.player.remote.Server
import com.aisub.player.remote.cloud.MediaBrowser
import com.aisub.player.remote.cloud.ServerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 首页自定义媒体库类型：电影 / 电视剧 */
enum class MediaLibraryType(val label: String) {
    MOVIE("电影"),
    TV("电视剧"),
}

/** 媒体库绑定的云存储或本地特定文件夹 */
data class LibraryFolderBinding(
    /** null 表示本机文件夹，非 null 表示已添加的云存储 / WebDAV / OpenList 服务器 ID */
    val serverId: String?,
    val serverName: String,
    /** 云存储目录路径或目录 ID（如 115 的 cid、阿里云盘 file_id、WebDAV 路径） */
    val path: String,
    /** 便于阅读的完整目录显示名（如「115 网盘:/影视/电影」） */
    val displayPath: String,
) {
    val key: String get() = "${serverId ?: "local"}|$path"

    fun toJson(): JSONObject = JSONObject()
        .put("serverId", serverId.orEmpty())
        .put("serverName", serverName)
        .put("path", path)
        .put("displayPath", displayPath)

    companion object {
        fun fromJson(o: JSONObject): LibraryFolderBinding = LibraryFolderBinding(
            serverId = o.optString("serverId").ifEmpty { null },
            serverName = o.optString("serverName"),
            path = o.optString("path"),
            displayPath = o.optString("displayPath").ifBlank { o.optString("path") },
        )
    }
}

/** 用户创建的自定义媒体库定义 */
data class CustomMediaLibrary(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: MediaLibraryType,
    val folders: List<LibraryFolderBinding>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastScrapedAt: Long = 0L,
    val itemCount: Int = 0,
    val coverUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("type", type.name)
        .put("folders", JSONArray(folders.map { it.toJson() }))
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("lastScrapedAt", lastScrapedAt)
        .put("itemCount", itemCount)
        .put("coverUrl", coverUrl.orEmpty())

    companion object {
        fun fromJson(o: JSONObject): CustomMediaLibrary {
            val type = runCatching { MediaLibraryType.valueOf(o.optString("type")) }.getOrDefault(MediaLibraryType.MOVIE)
            val arr = o.optJSONArray("folders") ?: JSONArray()
            val folders = List(arr.length()) { LibraryFolderBinding.fromJson(arr.getJSONObject(it)) }
            return CustomMediaLibrary(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name").ifBlank { type.label },
                type = type,
                folders = folders,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                lastScrapedAt = o.optLong("lastScrapedAt", 0L),
                itemCount = o.optInt("itemCount", 0),
                coverUrl = o.optString("coverUrl").ifBlank { null },
            )
        }
    }
}

/** 刮削条目下的单个可播放媒体文件（电影的多版本文件，或电视剧的单集文件） */
data class ScrapedEpisode(
    val id: String = UUID.randomUUID().toString(),
    val season: Int = 1,
    val episode: Int = 1,
    val title: String,
    val fileName: String,
    val playUrl: String,
    val serverId: String? = null,
    val path: String = "",
    val sizeBytes: Long = 0L,
) {
    val episodeLabel: String get() = "S%02dE%02d".format(season.coerceAtLeast(1), episode.coerceAtLeast(1))

    fun toMediaEntry(seriesTitle: String? = null): MediaEntry {
        val display = if (!seriesTitle.isNullOrBlank() && !fileName.contains(seriesTitle, ignoreCase = true)) {
            "$seriesTitle $episodeLabel - $title"
        } else {
            fileName.ifBlank { title }
        }
        return MediaEntry(
            uri = playUrl,
            title = display,
            isVideo = true,
            sizeBytes = sizeBytes,
            folder = seriesTitle.orEmpty(),
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("season", season)
        .put("episode", episode)
        .put("title", title)
        .put("fileName", fileName)
        .put("playUrl", playUrl)
        .put("serverId", serverId.orEmpty())
        .put("path", path)
        .put("sizeBytes", sizeBytes)

    companion object {
        fun fromJson(o: JSONObject): ScrapedEpisode = ScrapedEpisode(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            season = o.optInt("season", 1),
            episode = o.optInt("episode", 1),
            title = o.optString("title"),
            fileName = o.optString("fileName"),
            playUrl = o.optString("playUrl"),
            serverId = o.optString("serverId").ifBlank { null },
            path = o.optString("path"),
            sizeBytes = o.optLong("sizeBytes", 0L),
        )
    }
}

/** 刮削完成的一部电影或整部剧集 */
data class ScrapedMediaItem(
    val id: String = UUID.randomUUID().toString(),
    val libraryId: String,
    /** "movie" 或 "tv" */
    val kind: String,
    val title: String,
    val originalTitle: String = "",
    val year: Int? = null,
    val rating: Double? = null,
    val ratingLabel: String? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val tmdbId: Int? = null,
    val doubanId: String? = null,
    val imdbId: String? = null,
    val episodes: List<ScrapedEpisode> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
) {
    val badgeText: String?
        get() = when {
            kind == "tv" && episodes.isNotEmpty() -> {
                val seasons = episodes.map { it.season }.distinct().size
                if (seasons > 1) "${seasons}季 · ${episodes.size}集" else "共 ${episodes.size} 集"
            }
            kind == "movie" && episodes.size > 1 -> "${episodes.size} 个版本"
            else -> null
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("libraryId", libraryId)
        .put("kind", kind)
        .put("title", title)
        .put("originalTitle", originalTitle)
        .put("year", year ?: 0)
        .put("rating", rating ?: 0.0)
        .put("ratingLabel", ratingLabel.orEmpty())
        .put("overview", overview)
        .put("posterUrl", posterUrl.orEmpty())
        .put("backdropUrl", backdropUrl.orEmpty())
        .put("genres", JSONArray(genres))
        .put("countries", JSONArray(countries))
        .put("directors", JSONArray(directors))
        .put("cast", JSONArray(cast))
        .put("tmdbId", tmdbId ?: 0)
        .put("doubanId", doubanId.orEmpty())
        .put("imdbId", imdbId.orEmpty())
        .put("episodes", JSONArray(episodes.map { it.toJson() }))
        .put("addedAt", addedAt)

    companion object {
        private fun JSONArray?.toStrList(): List<String> =
            if (this == null) emptyList() else List(length()) { optString(it) }.filter { it.isNotBlank() }

        fun fromJson(o: JSONObject): ScrapedMediaItem {
            val epsArr = o.optJSONArray("episodes") ?: JSONArray()
            return ScrapedMediaItem(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                libraryId = o.optString("libraryId"),
                kind = o.optString("kind").ifBlank { "movie" },
                title = o.optString("title"),
                originalTitle = o.optString("originalTitle"),
                year = o.optInt("year", 0).takeIf { it > 0 },
                rating = o.optDouble("rating", 0.0).takeIf { !it.isNaN() && it > 0.0 },
                ratingLabel = o.optString("ratingLabel").ifBlank { null },
                overview = o.optString("overview"),
                posterUrl = o.optString("posterUrl").ifBlank { null },
                backdropUrl = o.optString("backdropUrl").ifBlank { null },
                genres = o.optJSONArray("genres").toStrList(),
                countries = o.optJSONArray("countries").toStrList(),
                directors = o.optJSONArray("directors").toStrList(),
                cast = o.optJSONArray("cast").toStrList(),
                tmdbId = o.optInt("tmdbId", 0).takeIf { it > 0 },
                doubanId = o.optString("doubanId").ifBlank { null },
                imdbId = o.optString("imdbId").ifBlank { null },
                episodes = List(epsArr.length()) { ScrapedEpisode.fromJson(epsArr.getJSONObject(it)) },
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            )
        }
    }
}

/** 刮削实时状态 */
data class ScrapeProgress(
    val libraryId: String,
    val running: Boolean = false,
    val stage: String = "",
    val scannedFiles: Int = 0,
    val scrapedCount: Int = 0,
    val totalGroups: Int = 0,
    val error: String? = null,
)

/**
 * 跨媒体库与媒体服务器（Emby / Jellyfin / Plex / OpenList / 本机）的统一可播片源索引命中结果。
 */
sealed interface IndexedPlayHit {
    data class CustomLibraryHit(
        val library: CustomMediaLibrary,
        val item: ScrapedMediaItem,
    ) : IndexedPlayHit

    data class MediaServerHit(
        val server: Server,
        val item: ServerItem,
    ) : IndexedPlayHit

    data class OpenListHit(
        val server: Server,
        val item: RemoteItem,
    ) : IndexedPlayHit
}

/**
 * 首页自定义媒体库与云盘目录刮削管理器（单例持久化 + 响应式 StateFlow，多端同步直接读写）。
 */
object CustomMediaLibraryStore {
    const val PREFS_NAME = "custom_libraries"
    private const val KEY_LIBRARIES = "libraries"
    private const val MAX_SCAN_DEPTH = 6
    internal const val MAX_SCAN_FILES_PER_FOLDER = 10_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scrapeJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var initialized = false

    private val _libraries = MutableStateFlow<List<CustomMediaLibrary>>(emptyList())
    val libraries: StateFlow<List<CustomMediaLibrary>> = _libraries.asStateFlow()

    private val _itemsByLibrary = MutableStateFlow<Map<String, List<ScrapedMediaItem>>>(emptyMap())
    val itemsByLibrary: StateFlow<Map<String, List<ScrapedMediaItem>>> = _itemsByLibrary.asStateFlow()

    private val _scrapeProgress = MutableStateFlow<Map<String, ScrapeProgress>>(emptyMap())
    val scrapeProgress: StateFlow<Map<String, ScrapeProgress>> = _scrapeProgress.asStateFlow()

    /** 当刮削或修改完成时回调（可触发多端自动同步） */
    @Volatile
    var onDataChanged: ((Context) -> Unit)? = null

    private fun catalogDir(context: Context): File =
        File(context.applicationContext.filesDir, "custom_libraries").apply { mkdirs() }

    private fun catalogFile(context: Context, libraryId: String): File =
        File(catalogDir(context), "$libraryId.json")

    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        reload(context)
        initialized = true
    }

    @Synchronized
    fun reload(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LIBRARIES, "[]") ?: "[]"
        val libs = runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { CustomMediaLibrary.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        val map = LinkedHashMap<String, List<ScrapedMediaItem>>()
        for (lib in libs) {
            map[lib.id] = readCatalogFromDisk(app, lib.id)
        }
        _libraries.value = libs
        _itemsByLibrary.value = map
        initialized = true
    }

    private fun readCatalogFromDisk(context: Context, libraryId: String): List<ScrapedMediaItem> {
        val f = catalogFile(context, libraryId)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText(Charsets.UTF_8))
            List(arr.length()) { ScrapedMediaItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeCatalogToDisk(context: Context, libraryId: String, items: List<ScrapedMediaItem>) {
        val f = catalogFile(context, libraryId)
        val tmp = File(f.parentFile, "${f.name}.tmp")
        val json = JSONArray(items.map { it.toJson() }).toString()
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(json, Charsets.UTF_8)
            tmp.delete()
        }
    }

    @Synchronized
    private fun persistLibraries(context: Context, list: List<CustomMediaLibrary>) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LIBRARIES, JSONArray(list.map { it.toJson() }).toString()).apply()
        _libraries.value = list
    }

    fun saveLibrary(context: Context, library: CustomMediaLibrary): CustomMediaLibrary {
        ensureInit(context)
        val now = System.currentTimeMillis()
        val existing = _libraries.value.firstOrNull { it.id == library.id }
        val existingItems = _itemsByLibrary.value[library.id].orEmpty()
        val cleanName = library.name.trim().ifEmpty { library.type.label }
        val updated = library.copy(
            name = cleanName,
            updatedAt = now,
            itemCount = existingItems.size.takeIf { existing != null } ?: library.itemCount,
            coverUrl = existingItems.firstOrNull { it.posterUrl != null }?.posterUrl ?: library.coverUrl,
        )
        val nextList = if (existing != null) {
            _libraries.value.map { if (it.id == updated.id) updated else it }
        } else {
            _libraries.value + updated
        }
        persistLibraries(context, nextList)
        onDataChanged?.invoke(context.applicationContext)
        return updated
    }

    fun deleteLibrary(context: Context, libraryId: String) {
        ensureInit(context)
        scrapeJobs.remove(libraryId)?.cancel()
        catalogFile(context, libraryId).delete()
        persistLibraries(context, _libraries.value.filterNot { it.id == libraryId })
        _itemsByLibrary.update { it - libraryId }
        _scrapeProgress.update { it - libraryId }
        onDataChanged?.invoke(context.applicationContext)
    }

    fun itemsOf(libraryId: String): List<ScrapedMediaItem> =
        _itemsByLibrary.value[libraryId].orEmpty()

    fun findItem(libraryId: String, itemId: String): ScrapedMediaItem? =
        _itemsByLibrary.value[libraryId]?.firstOrNull { it.id == itemId }

    /** 手动修正并重新匹配某个刮削条目的影片元数据 */
    suspend fun rematchItem(
        context: Context,
        settings: AppSettings,
        libraryId: String,
        itemId: String,
        newTitle: String,
        newYear: Int?,
    ): ScrapedMediaItem? = withContext(Dispatchers.IO) {
        ensureInit(context)
        val currentList = _itemsByLibrary.value[libraryId].orEmpty()
        val target = currentList.firstOrNull { it.id == itemId } ?: return@withContext null
        val q = MetaQuery(
            titles = listOf(newTitle.trim()).filter { it.isNotEmpty() },
            year = newYear,
            kind = target.kind,
        )
        val scraped = lookupOnlineMeta(settings, q, target.kind == "tv")
        val updated = if (scraped != null) {
            target.copy(
                title = scraped.title.ifBlank { newTitle.trim() },
                originalTitle = scraped.originalTitle,
                year = scraped.year.take(4).toIntOrNull() ?: newYear ?: target.year,
                rating = scraped.doubanRating ?: scraped.rating.takeIf { it > 0.0 },
                ratingLabel = if (scraped.doubanRating != null) "豆瓣" else if (scraped.rating > 0.0) scraped.ratingLabel else null,
                overview = scraped.overview.ifBlank { target.overview },
                posterUrl = scraped.posterUrl ?: target.posterUrl,
                backdropUrl = scraped.backdropUrl ?: target.backdropUrl,
                genres = scraped.genres.ifEmpty { target.genres },
                countries = scraped.countries.ifEmpty { target.countries },
                directors = scraped.directors.ifEmpty { target.directors },
                cast = scraped.cast.ifEmpty { target.cast },
                tmdbId = scraped.tmdbId.takeIf { it > 0 } ?: target.tmdbId,
                doubanId = scraped.doubanId ?: target.doubanId,
                imdbId = scraped.imdbId ?: target.imdbId,
            )
        } else {
            target.copy(title = newTitle.trim(), year = newYear ?: target.year)
        }
        val nextItems = currentList.map { if (it.id == itemId) updated else it }
        saveLibraryCatalog(context, libraryId, nextItems)
        updated
    }

    @Synchronized
    fun saveLibraryCatalog(context: Context, libraryId: String, items: List<ScrapedMediaItem>) {
        val app = context.applicationContext
        writeCatalogToDisk(app, libraryId, items)
        _itemsByLibrary.update { it + (libraryId to items) }
        val now = System.currentTimeMillis()
        val cover = items.firstOrNull { !it.posterUrl.isNullOrBlank() }?.posterUrl
        val nextLibs = _libraries.value.map { lib ->
            if (lib.id == libraryId) {
                lib.copy(
                    itemCount = items.size,
                    lastScrapedAt = now,
                    updatedAt = now,
                    coverUrl = cover ?: lib.coverUrl,
                )
            } else lib
        }
        persistLibraries(app, nextLibs)
        onDataChanged?.invoke(app)
    }

    /** 导出全量自定义媒体库及刮削缓存 JSON（供多端同步使用） */
    fun exportForSync(context: Context): JSONObject {
        ensureInit(context)
        val catalogs = JSONObject()
        for ((libId, items) in _itemsByLibrary.value) {
            catalogs.put(libId, JSONArray(items.map { it.toJson() }))
        }
        return JSONObject()
            .put("libraries", JSONArray(_libraries.value.map { it.toJson() }))
            .put("catalogs", catalogs)
    }

    /** 合并导入其他设备同步来的自定义媒体库及刮削结果 */
    @Synchronized
    fun mergeFromSync(context: Context, incoming: JSONObject?) {
        if (incoming == null) return
        ensureInit(context)
        val app = context.applicationContext
        val inLibsArr = incoming.optJSONArray("libraries") ?: JSONArray()
        val inCatalogs = incoming.optJSONObject("catalogs") ?: JSONObject()

        val mergedLibs = _libraries.value.associateBy { it.id }.toMutableMap()
        val mergedCatalogs = _itemsByLibrary.value.toMutableMap()

        for (i in 0 until inLibsArr.length()) {
            val remoteLib = CustomMediaLibrary.fromJson(inLibsArr.getJSONObject(i))
            val localLib = mergedLibs[remoteLib.id]
            val remoteItemsArr = inCatalogs.optJSONArray(remoteLib.id)
            val remoteItems = if (remoteItemsArr != null) {
                List(remoteItemsArr.length()) { ScrapedMediaItem.fromJson(remoteItemsArr.getJSONObject(it)) }
            } else emptyList()

            if (localLib == null || remoteLib.updatedAt >= localLib.updatedAt) {
                mergedLibs[remoteLib.id] = remoteLib
                if (remoteItems.isNotEmpty() || localLib == null) {
                    mergedCatalogs[remoteLib.id] = remoteItems
                    writeCatalogToDisk(app, remoteLib.id, remoteItems)
                }
            } else if (mergedCatalogs[remoteLib.id].isNullOrEmpty() && remoteItems.isNotEmpty()) {
                mergedCatalogs[remoteLib.id] = remoteItems
                writeCatalogToDisk(app, remoteLib.id, remoteItems)
                mergedLibs[remoteLib.id] = localLib.copy(
                    itemCount = remoteItems.size,
                    coverUrl = localLib.coverUrl ?: remoteItems.firstOrNull { it.posterUrl != null }?.posterUrl,
                )
            }
        }
        val finalLibs = mergedLibs.values.sortedBy { it.createdAt }
        persistLibraries(app, finalLibs)
        _itemsByLibrary.value = mergedCatalogs
    }

    /** 启动异步云存储文件夹扫描与影视元数据刮削 */
    fun startScrape(
        context: Context,
        libraryId: String,
        settings: AppSettings,
        libVm: LibraryViewModel,
    ) {
        ensureInit(context)
        val app = context.applicationContext
        val library = _libraries.value.firstOrNull { it.id == libraryId } ?: return
        scrapeJobs.remove(libraryId)?.cancel()

        val job = scope.launch {
            _scrapeProgress.update {
                it + (libraryId to ScrapeProgress(libraryId = libraryId, running = true, stage = "正在扫描文件夹…"))
            }
            try {
                val discovered = ArrayList<DiscoveredVideo>()
                val allServers = (libVm.state.value.servers + AppStore(app).servers()).distinctBy { it.id }
                val servers = allServers.associateBy { it.id }
                val localVideos = libVm.state.value.videos

                library.folders.forEachIndexed { bindingIndex, binding ->
                    val folderDiscovered = ArrayList<DiscoveredVideo>()
                    if (binding.serverId == null) {
                        val prefix = binding.path.trim('/')
                        val matched = if (prefix.isEmpty()) {
                            localVideos
                        } else {
                            localVideos.filter { v ->
                                val f = v.folder.trim('/')
                                f.equals(prefix, ignoreCase = true) || f.startsWith("$prefix/", ignoreCase = true)
                            }
                        }
                        for (v in matched) {
                            if (folderDiscovered.size >= MAX_SCAN_FILES_PER_FOLDER) break
                            val folderSegs = v.folder.trim('/').split('/').filter { it.isNotBlank() }
                            folderDiscovered += DiscoveredVideo(
                                serverId = null,
                                bindingIndex = bindingIndex,
                                fileName = v.title,
                                path = v.uri,
                                playUrl = v.uri,
                                sizeBytes = v.sizeBytes,
                                parentFolder = folderSegs.lastOrNull().orEmpty(),
                                grandParentFolder = folderSegs.getOrNull(folderSegs.size - 2).orEmpty(),
                                posterSidecar = null,
                            )
                        }
                    } else {
                        val srv = servers[binding.serverId]
                            ?: allServers.firstOrNull { !it.type.isMediaServer && it.name.equals(binding.serverName, ignoreCase = true) }
                        val client = srv?.let { runCatching { libVm.client(it) }.getOrNull() }
                        if (srv != null && client != null) {
                            _scrapeProgress.update { map ->
                                map + (libraryId to ScrapeProgress(
                                    libraryId = libraryId,
                                    running = true,
                                    stage = "正在扫描 ${srv.name}（累计已发现 ${discovered.size} 个视频）…",
                                    scannedFiles = discovered.size,
                                ))
                            }
                            try {
                                scanRemoteFolderRecursive(
                                    serverId = srv.id,
                                    bindingIndex = bindingIndex,
                                    client = client,
                                    path = binding.path.ifEmpty { srv.rootPath },
                                    folderName = binding.displayPath.substringAfterLast('/').trim(),
                                    parentFolderName = "",
                                    depth = 0,
                                    out = folderDiscovered,
                                ) { folderCount ->
                                    val totalSoFar = discovered.size + folderCount
                                    _scrapeProgress.update { map ->
                                        map + (libraryId to ScrapeProgress(
                                            libraryId = libraryId,
                                            running = true,
                                            stage = "正在扫描 ${srv.name}（当前源 $folderCount 个，累计 $totalSoFar 个视频）…",
                                            scannedFiles = totalSoFar,
                                        ))
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                android.util.Log.w("Voxtitle", "扫描存储源 ${srv.name} 异常: ${e.message}")
                            }
                        }
                    }
                    discovered.addAll(folderDiscovered)
                }

                if (discovered.isEmpty()) {
                    saveLibraryCatalog(app, libraryId, emptyList())
                    _scrapeProgress.update {
                        it + (libraryId to ScrapeProgress(
                            libraryId = libraryId,
                            running = false,
                            stage = "未扫描到视频文件，请检查所选目录",
                            scannedFiles = 0,
                        ))
                    }
                    return@launch
                }

                val existingItems = (_itemsByLibrary.value[libraryId].orEmpty() +
                    _itemsByLibrary.value.values.flatten()).distinctBy { it.id }
                val scrapedItems = if (library.type == MediaLibraryType.MOVIE) {
                    scrapeMovieGroups(
                        libraryId = libraryId,
                        files = discovered,
                        existing = existingItems,
                        settings = settings,
                        folderCount = library.folders.size,
                        onPartialSave = { partial -> saveLibraryCatalog(app, libraryId, partial) },
                    ) { done, total, curTitle ->
                        _scrapeProgress.update {
                            it + (libraryId to ScrapeProgress(
                                libraryId = libraryId,
                                running = true,
                                stage = "正在刮削电影 ($done/$total)：$curTitle",
                                scannedFiles = discovered.size,
                                scrapedCount = done,
                                totalGroups = total,
                            ))
                        }
                    }
                } else {
                    scrapeTvGroups(
                        libraryId = libraryId,
                        files = discovered,
                        existing = existingItems,
                        settings = settings,
                        folderCount = library.folders.size,
                        onPartialSave = { partial -> saveLibraryCatalog(app, libraryId, partial) },
                    ) { done, total, curTitle ->
                        _scrapeProgress.update {
                            it + (libraryId to ScrapeProgress(
                                libraryId = libraryId,
                                running = true,
                                stage = "正在刮削剧集 ($done/$total)：$curTitle",
                                scannedFiles = discovered.size,
                                scrapedCount = done,
                                totalGroups = total,
                            ))
                        }
                    }
                }

                saveLibraryCatalog(app, libraryId, scrapedItems)
                _scrapeProgress.update {
                    it + (libraryId to ScrapeProgress(
                        libraryId = libraryId,
                        running = false,
                        stage = "刮削完成，共 ${scrapedItems.size} 部${library.type.label}",
                        scannedFiles = discovered.size,
                        scrapedCount = scrapedItems.size,
                        totalGroups = scrapedItems.size,
                    ))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _scrapeProgress.update {
                    it + (libraryId to ScrapeProgress(
                        libraryId = libraryId,
                        running = false,
                        stage = "刮削中断",
                        error = e.message ?: e.javaClass.simpleName,
                    ))
                }
            }
        }
        scrapeJobs[libraryId] = job
    }

    fun cancelScrape(libraryId: String) {
        scrapeJobs.remove(libraryId)?.cancel()
        _scrapeProgress.update { map ->
            val cur = map[libraryId] ?: return@update map
            map + (libraryId to cur.copy(running = false, stage = "已取消刮削"))
        }
    }

    internal data class DiscoveredVideo(
        val serverId: String?,
        val bindingIndex: Int = 0,
        val fileName: String,
        val path: String,
        val playUrl: String,
        val sizeBytes: Long,
        val parentFolder: String,
        val grandParentFolder: String,
        val posterSidecar: String?,
    )

    private suspend fun listRemoteWithRetry(client: RemoteClient, path: String): List<RemoteItem>? {
        var lastErr: Throwable? = null
        for (attempt in 0..2) {
            try {
                return client.list(path)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                lastErr = e
                if (attempt < 2) delay(350L * (attempt + 1))
            }
        }
        android.util.Log.w("Voxtitle", "列举远端目录失败 ($path): ${lastErr?.message}")
        return null
    }

    private suspend fun scanRemoteFolderRecursive(
        serverId: String,
        bindingIndex: Int,
        client: RemoteClient,
        path: String,
        folderName: String,
        parentFolderName: String,
        depth: Int,
        out: MutableList<DiscoveredVideo>,
        onCount: (Int) -> Unit,
    ) {
        if (depth > MAX_SCAN_DEPTH || out.size >= MAX_SCAN_FILES_PER_FOLDER) return
        val children = listRemoteWithRetry(client, path) ?: return
        val posterFile = children.firstOrNull {
            !it.isDir && it.playUrl != null && it.name.lowercase() in setOf(
                "poster.jpg", "poster.png", "poster.webp", "folder.jpg", "folder.png", "cover.jpg", "cover.png",
            )
        }?.playUrl
        val isCloudSource = client is com.aisub.player.remote.cloud.CloudClient

        for (item in children) {
            if (out.size >= MAX_SCAN_FILES_PER_FOLDER) break
            if (item.name.startsWith(".")) continue
            if (item.isDir) {
                val low = item.name.lowercase()
                if (low in setOf("extras", "sample", "samples", "subs", "subtitles", "featurettes", "@eadir", "bdmv")) continue
                if (depth < MAX_SCAN_DEPTH) {
                    // 对 115 等直连网盘在所有层级保持温和请求间隔，避免并发或快速扫子目录触发风控限流
                    if (isCloudSource) delay(80L) else if (depth >= 1) delay(20L)
                    scanRemoteFolderRecursive(
                        serverId = serverId,
                        bindingIndex = bindingIndex,
                        client = client,
                        path = item.path,
                        folderName = item.name,
                        parentFolderName = folderName,
                        depth = depth + 1,
                        out = out,
                        onCount = onCount,
                    )
                }
            } else if (item.playUrl != null && MediaEntry.isVideoName(item.name)) {
                out += DiscoveredVideo(
                    serverId = serverId,
                    bindingIndex = bindingIndex,
                    fileName = item.name,
                    path = item.path,
                    playUrl = item.playUrl,
                    sizeBytes = item.size,
                    parentFolder = folderName,
                    grandParentFolder = parentFolderName,
                    posterSidecar = item.meta?.posterUrl ?: posterFile,
                )
                onCount(out.size)
            }
        }
    }

    private val TMDB_TAG_REGEX = Regex("""\{tmdb-(\d+)\}|\[tmdbid=(\d+)\]""", RegexOption.IGNORE_CASE)
    private val IMDB_TAG_REGEX = Regex("""(tt\d{7,9})""", RegexOption.IGNORE_CASE)
    private val SEASON_DIR_REGEX = Regex(
        """^(?:season\s*(\d+)|s(\d{1,2})|第\s*([一二三四五六七八九十\d]+)\s*[季部]|specials)$""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_SUBGROUP_REGEX = Regex("""^\s*[\[【][^\]】]*[\]】]\s*""")
    private val LEADING_EP_MARKER_REGEX = Regex(
        """^(?:s\d{1,2}[\s._\-]*e[p]?\d{1,4}|ep?\s*\d{1,4}|episode[\s._\-]*\d{1,4}|第\s*[0-9零〇一二两三四五六七八九十百]+\s*[集话話期幕]|\d{1,4}(?=[\s._\-]|$))""",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_EP_TITLE_REGEX = Regex(
        """^(?:s\d{1,2}[\s._\-]*e[p]?\d{1,4}|ep?\s*\d{1,4}|episode\s*\d{1,4}|第?\s*[0-9零〇一二两三四五六七八九十百]+\s*[集话話期幕]|\d{1,4}(?:-\d{1,4})?|part\s*\d+|ova\s*\d*|sp\s*\d*)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun extractTmdbTag(vararg texts: String): String? {
        for (t in texts) {
            val m = TMDB_TAG_REGEX.find(t) ?: continue
            val id = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (id.isNotEmpty()) return id
        }
        return null
    }

    private fun extractImdbTag(vararg texts: String): String? {
        for (t in texts) {
            IMDB_TAG_REGEX.find(t)?.groupValues?.get(1)?.let { return it.lowercase() }
        }
        return null
    }

    internal fun parseSeasonFromFolder(folder: String): Int? {
        val trimmed = folder.trim()
        if (trimmed.equals("specials", ignoreCase = true) || trimmed.equals("sp", ignoreCase = true)) return 0
        val m = SEASON_DIR_REGEX.matchEntire(trimmed) ?: return null
        m.groupValues[1].toIntOrNull()?.let { return it }
        m.groupValues[2].toIntOrNull()?.let { return it }
        val cn = m.groupValues[3]
        cn.toIntOrNull()?.let { return it }
        val cnMap = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10)
        if (cn.length == 1) return cnMap[cn[0]]
        return null
    }

    internal fun isGenericContainerFolder(name: String): Boolean {
        if (name.isBlank()) return true
        val low = name.trim().lowercase()
        if (low.matches(Regex("""^(?:19\d{2}|20\d{2})年?$"""))) return true
        return low in setOf(
            "movie", "movies", "film", "films", "tv", "tvs", "tvshows", "tv shows", "series", "show", "shows",
            "video", "videos", "media", "电影", "电视剧", "剧集", "连续剧", "动漫", "动画", "番剧", "新番",
            "纪录片", "综艺", "真人秀", "影视", "影视剧", "网盘", "云盘", "根目录", "内部存储根目录",
            "root", "download", "downloads", "nas", "115", "115网盘", "115 网盘", "aliyun", "阿里云盘",
            "baidu", "百度网盘", "pikpak", "onedrive", "webdav", "openlist", "alist",
            "国产剧", "大陆剧", "内地剧", "华语剧", "美剧", "英剧", "欧美剧", "日剧", "韩剧", "日韩剧",
            "港剧", "台剧", "港台剧", "泰剧", "海外剧", "短剧", "合集", "完结", "连载", "追剧", "在看", "未看", "已看",
            "4k", "2160p", "1080p", "720p", "remux", "bluray", "blu-ray", "bdmv", "stream", "原盘", "杜比", "杜比视界", "hdr",
        )
    }

    internal fun startsWithEpisodeMarker(fileName: String): Boolean {
        val stem = fileName.substringBeforeLast('.').trim()
        val stripped = LEADING_SUBGROUP_REGEX.find(stem)?.let {
            if (it.range.last < stem.lastIndex) stem.substring(it.range.last + 1).trim() else stem
        } ?: stem
        return LEADING_EP_MARKER_REGEX.containsMatchIn(stripped)
    }

    internal fun isBareEpisodeTitle(parsedTitle: String, rawFileName: String): Boolean {
        val t = parsedTitle.trim()
        if (t.isEmpty() || t.length <= 1) return true
        if (t.equals(rawFileName.trim(), ignoreCase = true) || t.equals(rawFileName.substringBeforeLast('.').trim(), ignoreCase = true)) {
            if (startsWithEpisodeMarker(rawFileName)) return true
        }
        return BARE_EP_TITLE_REGEX.matches(t)
    }

    /** 多目录绑定时按目录轮询交错排列，保证首页预览与默认排序同时均衡呈现 NAS 与 115 等各来源内容 */
    private fun <T> interleaveByBinding(items: List<Pair<Int, T>>, folderCount: Int): List<T> {
        if (folderCount <= 1 || items.size <= 1) return items.map { it.second }
        val buckets = LinkedHashMap<Int, ArrayDeque<T>>()
        for ((bIdx, item) in items) {
            buckets.getOrPut(bIdx) { ArrayDeque() }.addLast(item)
        }
        if (buckets.size <= 1) return items.map { it.second }
        val out = ArrayList<T>(items.size)
        while (out.size < items.size) {
            var progressed = false
            for (q in buckets.values) {
                if (q.isNotEmpty()) {
                    out += q.removeFirst()
                    progressed = true
                }
            }
            if (!progressed) break
        }
        return out
    }

    private suspend fun scrapeMovieGroups(
        libraryId: String,
        files: List<DiscoveredVideo>,
        existing: List<ScrapedMediaItem>,
        settings: AppSettings,
        folderCount: Int = 1,
        onPartialSave: (List<ScrapedMediaItem>) -> Unit = {},
        onProgress: (Int, Int, String) -> Unit,
    ): List<ScrapedMediaItem> {
        data class MovieCandidate(
            val key: String,
            val bindingIndex: Int,
            val titles: List<String>,
            val year: Int?,
            val tmdbTag: String?,
            val imdbTag: String?,
            val files: MutableList<DiscoveredVideo>,
        )

        val groups = LinkedHashMap<String, MovieCandidate>()
        for (v in files) {
            val fileParsed = NameParser.parse(v.fileName)
            val folderParsed = v.parentFolder.takeIf { !isGenericContainerFolder(it) && parseSeasonFromFolder(it) == null }
                ?.let { NameParser.parse(it) }
            val primaryTitle = when {
                fileParsed.title.length >= 2 && !fileParsed.title.matches(Regex("""^(?:cd|disc|part)?\s*\d+$""", RegexOption.IGNORE_CASE)) -> fileParsed.title
                folderParsed != null && folderParsed.title.isNotBlank() -> folderParsed.title
                else -> fileParsed.title.ifBlank { v.fileName.substringBeforeLast('.') }
            }
            val year = fileParsed.year ?: folderParsed?.year
            val tmdbTag = extractTmdbTag(v.fileName, v.parentFolder, v.grandParentFolder)
            val imdbTag = extractImdbTag(v.fileName, v.parentFolder)
            val normKey = (tmdbTag?.let { "tmdb:$it" } ?: "${normalizeMatchText(primaryTitle)}:${year ?: 0}")
            val titles = (listOfNotNull(primaryTitle, folderParsed?.title) + fileParsed.candidates()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val g = groups.getOrPut(normKey) {
                MovieCandidate(normKey, v.bindingIndex, titles, year, tmdbTag, imdbTag, mutableListOf())
            }
            g.files += v
        }

        val existingByPlayUrl = existing.flatMap { item -> item.episodes.map { ep -> ep.playUrl to item } }.toMap()
        val existingByNormTitle = existing.filter { it.kind == "movie" && !it.posterUrl.isNullOrBlank() }
            .associateBy { "${normalizeMatchText(it.title)}:${it.year ?: 0}" }
        val taggedResults = ArrayList<Pair<Int, ScrapedMediaItem>>(groups.size)
        var index = 0
        for ((_, group) in groups) {
            index++
            val label = group.titles.firstOrNull() ?: "电影"
            onProgress(index, groups.size, label)

            val cachedItem = group.files.firstNotNullOfOrNull { existingByPlayUrl[it.playUrl] }
                ?: group.titles.firstNotNullOfOrNull { t -> existingByNormTitle["${normalizeMatchText(t)}:${group.year ?: 0}"] }
            val episodes = group.files.mapIndexed { idx, f ->
                ScrapedEpisode(
                    season = 1,
                    episode = idx + 1,
                    title = f.fileName.substringBeforeLast('.'),
                    fileName = f.fileName,
                    playUrl = f.playUrl,
                    serverId = f.serverId,
                    path = f.path,
                    sizeBytes = f.sizeBytes,
                )
            }

            if (cachedItem != null && (!cachedItem.posterUrl.isNullOrBlank() || !settings.metaOnline)) {
                val reusedId = if (cachedItem.libraryId == libraryId) cachedItem.id else UUID.randomUUID().toString()
                taggedResults += group.bindingIndex to cachedItem.copy(id = reusedId, libraryId = libraryId, episodes = episodes)
                continue
            }

            val q = MetaQuery(
                titles = group.titles,
                year = group.year,
                kind = "movie",
                tmdbId = group.tmdbTag,
                imdbId = group.imdbTag,
            )
            val info = if (settings.metaOnline) lookupOnlineMeta(settings, q, tv = false) else null
            val fallbackPoster = group.files.firstNotNullOfOrNull { it.posterSidecar }
            val reusedId = if (cachedItem != null && cachedItem.libraryId == libraryId) cachedItem.id else UUID.randomUUID().toString()
            taggedResults += group.bindingIndex to ScrapedMediaItem(
                id = reusedId,
                libraryId = libraryId,
                kind = "movie",
                title = info?.title?.ifBlank { null } ?: label,
                originalTitle = info?.originalTitle.orEmpty(),
                year = info?.year?.take(4)?.toIntOrNull() ?: group.year,
                rating = info?.doubanRating ?: info?.rating?.takeIf { it > 0.0 },
                ratingLabel = when {
                    info?.doubanRating != null -> "豆瓣"
                    (info?.rating ?: 0.0) > 0.0 -> info?.ratingLabel ?: "TMDB"
                    else -> null
                },
                overview = info?.overview.orEmpty(),
                posterUrl = info?.posterUrl ?: fallbackPoster,
                backdropUrl = info?.backdropUrl,
                genres = info?.genres.orEmpty(),
                countries = info?.countries.orEmpty(),
                directors = info?.directors.orEmpty(),
                cast = info?.cast.orEmpty(),
                tmdbId = info?.tmdbId?.takeIf { it > 0 } ?: group.tmdbTag?.toIntOrNull(),
                doubanId = info?.doubanId,
                imdbId = info?.imdbId ?: group.imdbTag,
                episodes = episodes,
            )
            if (taggedResults.size % 10 == 0 && taggedResults.size < groups.size) {
                onPartialSave(interleaveByBinding(taggedResults, folderCount))
            }
        }
        val ordered = interleaveByBinding(taggedResults, folderCount)
        val baseTime = System.currentTimeMillis()
        return ordered.mapIndexed { idx, item ->
            item.copy(addedAt = baseTime - idx)
        }
    }

    internal data class TvFileHit(
        val video: DiscoveredVideo,
        val season: Int,
        val episode: Int,
        val epTitle: String,
    )

    internal data class TvCandidate(
        val key: String,
        val bindingIndex: Int,
        val titles: List<String>,
        val year: Int?,
        val tmdbTag: String?,
        val imdbTag: String?,
        val hits: MutableList<TvFileHit>,
    )

    internal fun groupTvCandidates(files: List<DiscoveredVideo>): List<TvCandidate> {
        // 检测是否存在把多部不同剧集平铺在同一个非通用目录（如「待看合集」）里的情况
        val prefixesByParent = HashMap<String, MutableSet<String>>()
        for (v in files) {
            if (parseSeasonFromFolder(v.parentFolder) != null || isGenericContainerFolder(v.parentFolder)) continue
            val fp = NameParser.parse(v.fileName)
            if (!startsWithEpisodeMarker(v.fileName) && !isBareEpisodeTitle(fp.title, v.fileName) && fp.isEpisode) {
                val norm = normalizeMatchText(fp.title)
                if (norm.length >= 2) {
                    prefixesByParent.getOrPut("${v.serverId}|${v.parentFolder}") { HashSet() } += norm
                }
            }
        }

        val groups = LinkedHashMap<String, TvCandidate>()
        for (v in files) {
            val fileParsed = NameParser.parse(v.fileName)
            val folderSeason = parseSeasonFromFolder(v.parentFolder)
            val multiShowContainer = (prefixesByParent["${v.serverId}|${v.parentFolder}"]?.size ?: 0) >= 2
            val showFolderRaw = when {
                folderSeason != null && !isGenericContainerFolder(v.grandParentFolder) -> v.grandParentFolder
                folderSeason == null && !isGenericContainerFolder(v.parentFolder) && !multiShowContainer -> v.parentFolder
                else -> ""
            }
            val folderParsed = showFolderRaw.takeIf { it.isNotBlank() }?.let { NameParser.parse(it) }

            val fileStartsWithEp = startsWithEpisodeMarker(v.fileName)
            val fileTitleLooksBareEp = isBareEpisodeTitle(fileParsed.title, v.fileName) || fileStartsWithEp
            val showTitle = when {
                folderParsed != null && folderParsed.title.isNotBlank() && (fileTitleLooksBareEp || folderSeason != null) -> folderParsed.title
                folderParsed != null && folderParsed.title.isNotBlank() && !isBareEpisodeTitle(folderParsed.title, showFolderRaw) -> folderParsed.title
                fileParsed.title.isNotBlank() && !fileTitleLooksBareEp -> fileParsed.title
                folderParsed != null && folderParsed.title.isNotBlank() -> folderParsed.title
                else -> fileParsed.title.ifBlank { v.fileName.substringBeforeLast('.') }
            }

            val season = (fileParsed.season ?: folderSeason ?: 1).coerceAtLeast(0)
            val fallbackEpNum = Regex("""(?:^|[\s._\-\[E第])(\d{1,4})(?:[集话話期\]\s._\-]|$)""", RegexOption.IGNORE_CASE)
                .find(v.fileName.substringBeforeLast('.'))?.groupValues?.get(1)?.toIntOrNull()
            val episode = (fileParsed.episode ?: fallbackEpNum ?: 1).coerceAtLeast(1)
            val year = folderParsed?.year ?: fileParsed.year
            val tmdbTag = extractTmdbTag(showFolderRaw, v.parentFolder, v.fileName)
            val imdbTag = extractImdbTag(showFolderRaw, v.parentFolder, v.fileName)

            val normKey = tmdbTag?.let { "tmdb:$it" } ?: normalizeMatchText(showTitle).ifEmpty { showTitle.lowercase() }
            val candidates = (
                listOfNotNull(
                    showTitle,
                    folderParsed?.title,
                    fileParsed.title.takeUnless { fileTitleLooksBareEp },
                ) + (folderParsed?.candidates().orEmpty()) + (if (!fileTitleLooksBareEp) fileParsed.candidates() else emptyList())
                ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val group = groups.getOrPut(normKey) {
                TvCandidate(normKey, v.bindingIndex, candidates, year, tmdbTag, imdbTag, mutableListOf())
            }
            group.hits += TvFileHit(
                video = v,
                season = if (season == 0 && folderSeason == null) 1 else season,
                episode = episode,
                epTitle = v.fileName.substringBeforeLast('.'),
            )
        }
        return groups.values.toList()
    }

    private suspend fun scrapeTvGroups(
        libraryId: String,
        files: List<DiscoveredVideo>,
        existing: List<ScrapedMediaItem>,
        settings: AppSettings,
        folderCount: Int = 1,
        onPartialSave: (List<ScrapedMediaItem>) -> Unit = {},
        onProgress: (Int, Int, String) -> Unit,
    ): List<ScrapedMediaItem> {
        val groups = groupTvCandidates(files)
        val existingByPlayUrl = existing.flatMap { item -> item.episodes.map { ep -> ep.playUrl to item } }.toMap()
        val existingByNormTitle = existing.filter { it.kind == "tv" && !it.posterUrl.isNullOrBlank() }
            .associateBy { normalizeMatchText(it.title) }
        val taggedResults = ArrayList<Pair<Int, ScrapedMediaItem>>(groups.size)
        var index = 0
        for (group in groups) {
            index++
            val label = group.titles.firstOrNull() ?: "剧集"
            onProgress(index, groups.size, label)

            val sortedEpisodes = group.hits
                .sortedWith(compareBy({ it.season }, { it.episode }, { it.video.bindingIndex }, { it.video.fileName.lowercase() }))
                .map { h ->
                    ScrapedEpisode(
                        season = h.season,
                        episode = h.episode,
                        title = "第 ${h.episode} 集",
                        fileName = h.video.fileName,
                        playUrl = h.video.playUrl,
                        serverId = h.video.serverId,
                        path = h.video.path,
                        sizeBytes = h.video.sizeBytes,
                    )
                }

            // 取该剧集包含最多分集（或最早出现）的绑定源下标用于多源交错排序
            val dominantBinding = group.hits.groupingBy { it.video.bindingIndex }.eachCount()
                .maxByOrNull { it.value }?.key ?: group.bindingIndex

            val cachedItem = group.hits.firstNotNullOfOrNull { existingByPlayUrl[it.video.playUrl] }
                ?: group.titles.firstNotNullOfOrNull { t -> existingByNormTitle[normalizeMatchText(t)] }
            if (cachedItem != null && (!cachedItem.posterUrl.isNullOrBlank() || !settings.metaOnline)) {
                val reusedId = if (cachedItem.libraryId == libraryId) cachedItem.id else UUID.randomUUID().toString()
                taggedResults += dominantBinding to cachedItem.copy(id = reusedId, libraryId = libraryId, episodes = sortedEpisodes)
                continue
            }

            val q = MetaQuery(
                titles = group.titles,
                year = group.year,
                kind = "tv",
                tmdbId = group.tmdbTag,
                imdbId = group.imdbTag,
            )
            val info = if (settings.metaOnline) lookupOnlineMeta(settings, q, tv = true) else null
            val fallbackPoster = group.hits.firstNotNullOfOrNull { it.video.posterSidecar }
            val reusedId = if (cachedItem != null && cachedItem.libraryId == libraryId) cachedItem.id else UUID.randomUUID().toString()
            taggedResults += dominantBinding to ScrapedMediaItem(
                id = reusedId,
                libraryId = libraryId,
                kind = "tv",
                title = info?.title?.ifBlank { null } ?: label,
                originalTitle = info?.originalTitle.orEmpty(),
                year = info?.year?.take(4)?.toIntOrNull() ?: group.year,
                rating = info?.doubanRating ?: info?.rating?.takeIf { it > 0.0 },
                ratingLabel = when {
                    info?.doubanRating != null -> "豆瓣"
                    (info?.rating ?: 0.0) > 0.0 -> info?.ratingLabel ?: "TMDB"
                    else -> null
                },
                overview = info?.overview.orEmpty(),
                posterUrl = info?.posterUrl ?: fallbackPoster,
                backdropUrl = info?.backdropUrl,
                genres = info?.genres.orEmpty(),
                countries = info?.countries.orEmpty(),
                directors = info?.directors.orEmpty(),
                cast = info?.cast.orEmpty(),
                tmdbId = info?.tmdbId?.takeIf { it > 0 } ?: group.tmdbTag?.toIntOrNull(),
                doubanId = info?.doubanId,
                imdbId = info?.imdbId ?: group.imdbTag,
                episodes = sortedEpisodes,
            )
            if (taggedResults.size % 10 == 0 && taggedResults.size < groups.size) {
                onPartialSave(interleaveByBinding(taggedResults, folderCount))
            }
        }
        val ordered = interleaveByBinding(taggedResults, folderCount)
        val baseTime = System.currentTimeMillis()
        return ordered.mapIndexed { idx, item ->
            item.copy(addedAt = baseTime - idx)
        }
    }

    private suspend fun lookupOnlineMeta(settings: AppSettings, q: MetaQuery, tv: Boolean): MediaInfo? {
        var info: MediaInfo? = null
        if (settings.tmdbKey.isNotBlank()) {
            info = runCatching { MetaRepository.tmdb(settings, q) }.getOrNull()
        }
        if (settings.doubanEnabled && (info == null || info.doubanRating == null || info.posterUrl == null || info.overview.isBlank())) {
            val db = runCatching { MetaRepository.douban(q, info) }.getOrNull()
            if (db != null) {
                val dbInfo = db.info?.copy(doubanRating = db.rating, doubanVotes = db.votes, doubanId = db.id)
                info = when {
                    info != null && dbInfo != null -> info.fillFrom(dbInfo)
                    info != null -> info.copy(doubanRating = db.rating, doubanVotes = db.votes, doubanId = db.id)
                    else -> dbInfo
                }
            }
            delay(80L)
        }
        return info
    }

    internal fun normalizeMatchText(s: String): String =
        s.lowercase().replace(Regex("""[\s·・:：\-—_!！?？.'"`()\[\]【】《》,，]"""), "")

    /**
     * 在本机已刮削的自定义媒体库中秒级检索匹配的电影或剧集（按 TMDB ID / 豆瓣 ID / IMDb ID / 归一化片名）。
     */
    fun matchInScrapedLibraries(
        context: Context,
        title: String,
        origTitle: String = "",
        tmdbId: Int? = null,
        doubanId: String? = null,
        imdbId: String? = null,
        kind: String? = null,
    ): List<IndexedPlayHit.CustomLibraryHit> {
        ensureInit(context)
        val targets = listOf(title, origTitle).map(::normalizeMatchText).filter { it.length >= 2 }.distinct()
        val libsById = _libraries.value.associateBy { it.id }
        val hits = ArrayList<IndexedPlayHit.CustomLibraryHit>()

        for ((libId, items) in _itemsByLibrary.value) {
            val lib = libsById[libId] ?: continue
            for (item in items) {
                val idMatched = (tmdbId != null && tmdbId > 0 && item.tmdbId == tmdbId && (kind == null || item.kind == kind)) ||
                    (!doubanId.isNullOrBlank() && item.doubanId == doubanId) ||
                    (!imdbId.isNullOrBlank() && item.imdbId.equals(imdbId, ignoreCase = true))
                val titleMatched = if (!idMatched && targets.isNotEmpty()) {
                    val itemT = normalizeMatchText(item.title)
                    val itemOrig = normalizeMatchText(item.originalTitle)
                    targets.any { t ->
                        itemT == t || itemOrig == t ||
                            (t.length >= 3 && (itemT.contains(t) || t.contains(itemT))) ||
                            (itemOrig.length >= 3 && (itemOrig.contains(t) || t.contains(itemOrig)))
                    }
                } else false

                if (idMatched || titleMatched) {
                    hits += IndexedPlayHit.CustomLibraryHit(lib, item)
                }
            }
        }
        return hits
    }

    /**
     * 统一并发检索自定义刮削媒体库 + Emby / Jellyfin / Plex 媒体服务器 + OpenList 网盘，
     * 实现影片详情页一键直达索引播放。
     */
    suspend fun queryAllIndexedSources(
        context: Context,
        lib: LibraryViewModel,
        title: String,
        origTitle: String = "",
        tmdbId: Int? = null,
        doubanId: String? = null,
        imdbId: String? = null,
        kind: String? = null,
    ): List<IndexedPlayHit> = withContext(Dispatchers.IO) {
        val localLibHits = matchInScrapedLibraries(context, title, origTitle, tmdbId, doubanId, imdbId, kind)
        val servers = lib.state.value.servers
        if (servers.isEmpty() || title.isBlank()) return@withContext localLibHits

        val remoteHits = coroutineScope {
            servers.map { srv ->
                async {
                    runCatching {
                        when (val client = lib.client(srv)) {
                            is MediaBrowser -> {
                                val primary = client.search(title)
                                val merged = if (primary.isNotEmpty() || origTitle.isBlank() || origTitle == title) {
                                    primary
                                } else {
                                    client.search(origTitle)
                                }
                                merged.take(6).map { IndexedPlayHit.MediaServerHit(srv, it) }
                            }
                            is OpenListClient -> {
                                val primary = client.searchVideos(title)
                                val merged = if (primary.isNotEmpty() || origTitle.isBlank() || origTitle == title) {
                                    primary
                                } else {
                                    client.searchVideos(origTitle)
                                }
                                merged.take(5).map { IndexedPlayHit.OpenListHit(srv, it) }
                            }
                            else -> emptyList()
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        localLibHits + remoteHits
    }
}
