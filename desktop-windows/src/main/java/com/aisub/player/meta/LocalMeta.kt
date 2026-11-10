package com.aisub.player.meta

import android.content.Context
import com.aisub.player.media.RemoteFiles
import com.aisub.player.remote.RemoteClient
import com.aisub.player.remote.RemoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 打开详情页时文件所在的目录：同目录的文件（找 NFO、海报）和上一级目录（剧集的 tvshow.nfo、剧集海报）。
 * 目录名里可能带 {tmdb-123} 这类编号。
 */
data class FolderContext(
    val siblings: List<RemoteItem>,
    val folderName: String,
    val parentPath: String? = null,
    val parentName: String? = null,
)

/** 从本地文件（NFO、poster.jpg、fanart.jpg、目录名编号）读到的信息。 */
data class LocalMeta(
    /** 由 NFO 生成；没有 NFO 时为 null */
    val info: MediaInfo?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val stillUrl: String?,
    val tmdbId: String?,
    val imdbId: String?,
    /** "movie" / "tv" */
    val kind: String?,
    val doubanId: String?,
) {
    val isEmpty get() = info == null && posterUrl == null && backdropUrl == null && tmdbId == null && imdbId == null
}

/**
 * 按 Kodi / Emby / Jellyfin 的刮削文件约定读取视频旁边的元数据：
 * 视频名.nfo、movie.nfo、tvshow.nfo；视频名-poster.jpg、poster.jpg、folder.jpg；视频名-fanart.jpg、fanart.jpg；视频名-thumb.jpg。
 */
object LocalMetaLoader {

    private val IMAGE_EXT = listOf("jpg", "jpeg", "png", "webp")

    suspend fun load(context: Context, client: RemoteClient, item: RemoteItem, folder: FolderContext): LocalMeta = withContext(Dispatchers.IO) {
        val base = item.name.substringBeforeLast('.')
        val here = folder.siblings.filter { !it.isDir && it.playUrl != null }
        val parsed = NameParser.parse(item.name)

        val ownNfo = find(here, "$base.nfo") ?: find(here, "movie.nfo").takeIf { !parsed.isEpisode }
        var nfo = ownNfo?.let { readNfo(context, it) }
        val episodic = parsed.isEpisode || nfo?.kind == "episode"

        // 剧集：同目录（季文件夹）或上一级（剧集文件夹）的 tvshow.nfo 和海报
        val parent = if (!episodic || folder.parentPath == null) emptyList() else {
            try {
                client.list(folder.parentPath).filter { !it.isDir && it.playUrl != null }
            } catch (e: Exception) {
                emptyList()
            }
        }
        val show = if (episodic) (find(here, "tvshow.nfo") ?: find(parent, "tvshow.nfo"))?.let { readNfo(context, it) } else null

        val poster = image(here, "$base-poster", "$base.poster")
            ?: image(here, "poster", "folder", "cover", "movie", "season-poster")
            ?: (if (episodic) image(parent, "poster", "folder", "cover", "show") else null)
        val backdrop = image(here, "$base-fanart", "$base-backdrop")
            ?: image(here, "fanart", "backdrop", "background", "landscape")
            ?: (if (episodic) image(parent, "fanart", "backdrop", "background", "landscape") else null)
        val still = if (episodic) image(here, "$base-thumb", base) else null

        // 目录名、文件名里的编号：电影看当前目录，剧集还要看上一级（季目录一般不带编号）
        val nameIds = listOfNotNull(item.name, folder.folderName, folder.parentName.takeIf { episodic })
            .map(Nfo::idsFromName)
        val pathTmdb = nameIds.firstNotNullOfOrNull { it.first }
        val pathImdb = nameIds.firstNotNullOfOrNull { it.second }

        // 单集 NFO 的编号是这一集的，剧集编号要从 tvshow.nfo 或目录名取
        val seriesSource = show ?: nfo?.takeIf { it.kind != "episode" }
        val tmdbId = seriesSource?.tmdbId ?: pathTmdb
        val imdbId = seriesSource?.imdbId ?: pathImdb
        val kind = when {
            episodic -> "tv"
            nfo?.kind == "tv" -> "tv"
            else -> "movie"
        }
        if (nfo != null && nfo.kind == null && !episodic) nfo = nfo.copy(kind = kind)

        LocalMeta(
            info = toInfo(nfo, show, kind, tmdbId),
            posterUrl = poster,
            backdropUrl = backdrop,
            stillUrl = still,
            tmdbId = tmdbId,
            imdbId = imdbId,
            kind = kind,
            doubanId = nfo?.doubanId ?: show?.doubanId,
        )
    }

    private fun find(files: List<RemoteItem>, name: String) = files.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun image(files: List<RemoteItem>, vararg names: String): String? =
        names.firstNotNullOfOrNull { n -> IMAGE_EXT.firstNotNullOfOrNull { ext -> find(files, "$n.$ext") } }?.playUrl

    private fun readNfo(context: Context, file: RemoteItem): NfoInfo? = runCatching {
        Nfo.parse(RemoteFiles.decodeText(RemoteFiles.read(context, file.playUrl!!, 1024 * 1024, truncate = true)))
    }.getOrNull()

    /** NFO → 详情页用的 MediaInfo。剧集用 tvshow.nfo 作主体，单集 NFO 填「本集」。 */
    private fun toInfo(nfo: NfoInfo?, show: NfoInfo?, kind: String, tmdbId: String?): MediaInfo? {
        val main = show ?: nfo?.takeIf { it.kind != "episode" || it.title.isNotEmpty() } ?: return null
        // 只有单集 NFO、没有 tvshow.nfo 时，剧名取 showtitle，简介不当作剧集简介
        val episodeOnly = show == null && nfo?.kind == "episode"
        if (main.kind == null && main.title.isEmpty() && main.plot.isEmpty()) return null
        val episode = nfo?.takeIf { it.kind == "episode" }
        val rated = if (episodeOnly) null else main
        return MediaInfo(
            kind = kind,
            tmdbId = tmdbId?.toIntOrNull() ?: 0,
            imdbId = main.imdbId.takeIf { !episodeOnly },
            title = if (episodeOnly) main.showTitle else main.title,
            originalTitle = if (episodeOnly) "" else main.originalTitle,
            year = (if (episodeOnly) null else main.year)?.toString().orEmpty(),
            overview = if (episodeOnly) "" else main.plot,
            posterUrl = main.posterUrl.takeIf { !episodeOnly },
            backdropUrl = main.fanartUrl.takeIf { !episodeOnly },
            genres = main.genres,
            length = main.runtimeMinutes?.takeIf { kind == "movie" }?.let { m -> if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟" }.orEmpty(),
            rating = rated?.rating ?: 0.0,
            voteCount = rated?.votes ?: 0,
            directors = main.directors,
            cast = main.cast.take(12),
            countries = main.countries,
            episodeTitle = episode?.let { e ->
                val s = e.season ?: 1
                listOfNotNull(e.episode?.let { "第 $s 季第 $it 集" }, e.title.ifEmpty { null }).joinToString(" · ").ifEmpty { null }
            },
            episodeOverview = episode?.plot?.ifBlank { null },
            ratingLabel = rated?.ratingSource ?: "NFO",
            source = "本地 NFO",
            doubanRating = nfo?.doubanRating ?: show?.doubanRating,
            doubanVotes = nfo?.doubanVotes ?: show?.doubanVotes,
            doubanId = nfo?.doubanId ?: show?.doubanId,
            tvdbId = (show ?: nfo?.takeIf { it.kind != "episode" })?.tvdbId?.toIntOrNull(),
        )
    }
}
