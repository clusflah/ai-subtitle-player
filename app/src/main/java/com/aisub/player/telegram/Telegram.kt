package com.aisub.player.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Telegram 的登录状态 */
sealed interface TgAuth {
    /** 还没填 api_id / api_hash */
    data object NoCredentials : TgAuth
    data object Starting : TgAuth
    data object WaitPhone : TgAuth
    data class WaitCode(val phone: String, val viaApp: Boolean) : TgAuth
    data class WaitPassword(val hint: String) : TgAuth
    /** 这里做不了的步骤（注册新账号、邮箱验证）：去官方客户端完成 */
    data class Unsupported(val reason: String) : TgAuth
    data object Ready : TgAuth
    data object LoggingOut : TgAuth
}

/** 一个频道或群 */
data class TgChat(
    val id: Long,
    val title: String,
    val isChannel: Boolean,
    /** 头像（小图），要下载 */
    val photo: TdApi.File? = null,
    /** 头像的模糊预览图（JPEG），马上能显示 */
    val photoMini: ByteArray? = null,
    /** 公开的 @用户名、成员数：搜索结果里显示（会话列表里不取，省时间） */
    val username: String? = null,
    val members: Int = 0,
)

/** 频道 / 群里的一个视频（视频消息，或文件形式发的视频） */
data class TgVideo(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val caption: String,
    /** 文件名（一般带集数、画质） */
    val fileName: String,
    /** 说明里的 #标签（按出现顺序） */
    val tags: List<String>,
    val durationSec: Int,
    val size: Long,
    /** 发送时间（秒） */
    val date: Int,
    /** 很小的模糊预览图（JPEG），马上能显示 */
    val mini: ByteArray?,
    /** 缩略图文件，要下载 */
    val thumb: TdApi.File?,
) {
    val uri: String get() = Telegram.uriOf(chatId, messageId)

    /** 当作「剧名」的标签：第一行里的优先（「番剧: #片名」），没有就用第一个 */
    val seriesTag: String?
        get() {
            val first = caption.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return tags.firstOrNull { it in first } ?: tags.firstOrNull()
        }
}

class TgException(val code: Int, message: String) : Exception(message)

/**
 * Telegram 客户端（官方 TDLib）：登录、列出频道和群、找视频、边下边播（见 [TgDataSource]）。
 * 整个进程一个；api_id / api_hash 由用户自己在 my.telegram.org 申请，只存在这台手机上（不进备份）。
 * 登录状态、下载的文件放在 App 的私有目录里。
 */
object Telegram {
    private const val PREFS = "telegram"
    /** 视频缓存默认上限 */
    const val DEFAULT_CACHE_MB = 2048

    private lateinit var app: Context
    private val _auth = MutableStateFlow<TgAuth>(TgAuth.NoCredentials)
    val auth: StateFlow<TgAuth> = _auth.asStateFlow()

    @Volatile
    private var client: Client? = null

    /** 文件下载进度（[TgDataSource] 等着它） */
    val fileListeners = CopyOnWriteArrayList<(TdApi.File) -> Unit>()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        if (!hasCredentials()) _auth.value = TgAuth.NoCredentials
    }

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasCredentials(): Boolean = prefs.getInt("apiId", 0) != 0 && !prefs.getString("apiHash", null).isNullOrBlank()

    /** 已填的 api_id（修改凭证时显示）；api_hash 不回显 */
    fun apiId(): Int = prefs.getInt("apiId", 0)

    fun setCredentials(apiId: Int, apiHash: String) {
        prefs.edit().putInt("apiId", apiId).putString("apiHash", apiHash.trim()).apply()
        // 换了凭证：已经开着的客户端用的是旧的，关掉重开
        client?.send(TdApi.Close()) {}
        client = null
        start()
    }

    var cacheLimitMb: Int
        get() = prefs.getInt("cacheLimitMb", DEFAULT_CACHE_MB)
        set(v) = prefs.edit().putInt("cacheLimitMb", v).apply()

    /** 打开 Telegram 页面或播放 tg:// 时调：有凭证就启动客户端 */
    @Synchronized
    fun start() {
        if (client != null) return
        if (!hasCredentials()) {
            _auth.value = TgAuth.NoCredentials
            return
        }
        _auth.value = TgAuth.Starting
        runCatching { Client.execute(TdApi.SetLogVerbosityLevel(1)) }
        client = Client.create({ onUpdate(it) }, null, null)
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuth(obj.authorizationState)
            is TdApi.UpdateFile -> fileListeners.forEach { it(obj.file) }
        }
    }

    private fun onAuth(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val dir = File(app.filesDir, "telegram")
                val p = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = File(dir, "db").absolutePath
                    filesDirectory = File(dir, "files").absolutePath
                    databaseEncryptionKey = ByteArray(0)
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = prefs.getInt("apiId", 0)
                    apiHash = prefs.getString("apiHash", "").orEmpty()
                    systemLanguageCode = "zh-hans"
                    deviceModel = Build.MODEL ?: "Android"
                    systemVersion = Build.VERSION.RELEASE ?: ""
                    applicationVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "1.0"
                }
                client?.send(p) { r ->
                    // api_id / api_hash 不对：这里就报错
                    if (r is TdApi.Error) _auth.value = TgAuth.Unsupported("api_id / api_hash 不对（${r.message}），请检查后重新填写")
                }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = TgAuth.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> _auth.value = TgAuth.WaitCode(
                state.codeInfo.phoneNumber, state.codeInfo.type is TdApi.AuthenticationCodeTypeTelegramMessage,
            )
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = TgAuth.WaitPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateWaitRegistration -> _auth.value = TgAuth.Unsupported("这个手机号还没注册 Telegram，请先用官方 App 注册")
            is TdApi.AuthorizationStateWaitEmailAddress, is TdApi.AuthorizationStateWaitEmailCode ->
                _auth.value = TgAuth.Unsupported("Telegram 要求先设置登录邮箱，请先在官方 App 里登录一次完成设置")
            is TdApi.AuthorizationStateReady -> {
                _auth.value = TgAuth.Ready
                trimCacheAsync()
            }
            is TdApi.AuthorizationStateLoggingOut -> _auth.value = TgAuth.LoggingOut
            is TdApi.AuthorizationStateClosed -> {
                // 退出登录或换凭证后客户端就关了：要再登录得重新建一个
                synchronized(this) { client = null }
                if (_auth.value == TgAuth.LoggingOut) start()
            }
            else -> {}
        }
    }

    /** 发一个请求，等结果；出错抛 [TgException] */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : TdApi.Object> send(f: TdApi.Function<T>): T = suspendCancellableCoroutine { c ->
        val cl = client
        if (cl == null) {
            c.resumeWithException(TgException(0, "Telegram 还没登录"))
            return@suspendCancellableCoroutine
        }
        cl.send(f) { r ->
            if (r is TdApi.Error) c.resumeWithException(TgException(r.code, r.message)) else c.resume(r as T)
        }
    }

    /** 播放 tg:// 时：没启动就启动，等登录好（已登录过的话很快） */
    suspend fun awaitReady(timeoutMs: Long = 30_000): Boolean {
        start()
        return withTimeoutOrNull(timeoutMs) { auth.first { it == TgAuth.Ready || it is TgAuth.Unsupported || it == TgAuth.WaitPhone } } == TgAuth.Ready
    }

    // ---- 登录 ----

    suspend fun submitPhone(phone: String) = send(TdApi.SetAuthenticationPhoneNumber(phone.filter { it.isDigit() || it == '+' }, null))
    suspend fun submitCode(code: String) = send(TdApi.CheckAuthenticationCode(code.trim()))
    suspend fun submitPassword(password: String) = send(TdApi.CheckAuthenticationPassword(password))
    suspend fun logout() = send(TdApi.LogOut())

    // ---- 频道和群 ----

    /** 加入的频道和群（按会话列表的顺序） */
    suspend fun chats(): List<TgChat> {
        // 会话列表要先「加载」进 TDLib 才拿得到；全部加载完会返回 404
        repeat(5) {
            try {
                send(TdApi.LoadChats(TdApi.ChatListMain(), 100))
            } catch (e: TgException) {
                if (e.code == 404) return@repeat else throw e
            }
        }
        val ids = send(TdApi.GetChats(TdApi.ChatListMain(), 500)).chatIds.toList()
        // 收藏的公开频道可能没加入，也要列出来
        val all = ids + favorites().filter { it !in ids }
        return all.mapNotNull { chatOf(it) }
    }

    private suspend fun chatOf(id: Long, details: Boolean = false): TgChat? {
        val chat = runCatching { send(TdApi.GetChat(id)) }.getOrNull() ?: return null
        val c = when (val t = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                val sg = if (details) runCatching { send(TdApi.GetSupergroup(t.supergroupId)) }.getOrNull() else null
                TgChat(id, chat.title, t.isChannel, username = sg?.usernames?.activeUsernames?.firstOrNull(), members = sg?.memberCount ?: 0)
            }
            is TdApi.ChatTypeBasicGroup -> TgChat(id, chat.title, false)
            else -> return null
        }
        return c.copy(photo = chat.photo?.small, photoMini = chat.photo?.minithumbnail?.data)
    }

    /** 按名字或 @用户名 搜公开的频道和群（包括没加入的），带成员数和 @用户名 */
    suspend fun searchPublic(query: String): List<TgChat> =
        send(TdApi.SearchPublicChats(query, null)).chatIds.toList().mapNotNull { chatOf(it, details = true) }

    // ---- 收藏的频道有新视频：记下每个会话看到过的最新视频 ----

    /** 最新一个视频的消息 ID（0 是没有） */
    suspend fun latestVideoId(chatId: Long): Long = listOf(TdApi.SearchMessagesFilterVideo(), TdApi.SearchMessagesFilterDocument())
        .maxOf { f ->
            runCatching { send(TdApi.SearchChatMessages(chatId, null, "", null, 0, 0, 5, f)).messages }.getOrNull().orEmpty()
                .firstOrNull { videoOf(it) != null }?.id ?: 0L
        }

    fun seenVideoId(chatId: Long): Long = prefs.getLong("seen_$chatId", 0L)

    fun setSeenVideoId(chatId: Long, messageId: Long) {
        if (messageId > seenVideoId(chatId)) prefs.edit().putLong("seen_$chatId", messageId).apply()
    }

    /** 收藏的频道 / 群（排在列表最前） */
    fun favorites(): Set<Long> =
        prefs.getStringSet("favorites", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun setFavorite(chatId: Long, favorite: Boolean) {
        val set = favorites().let { if (favorite) it + chatId else it - chatId }
        prefs.edit().putStringSet("favorites", set.map { it.toString() }.toSet()).apply()
    }

    /**
     * 一个频道 / 群里的视频，从新到旧一页页取。让服务器按类型筛（视频消息、文件），
     * 文字消息再多也不用自己翻；[query] 不空时只要说明里有这些字的（例如「#片名」）。
     */
    class VideoPager(private val chatId: Long, private val query: String = "") {
        private inner class Stream(val filter: TdApi.SearchMessagesFilter) {
            var from = 0L
            var done = false
            val buffer = ArrayDeque<TgVideo>()

            suspend fun fetch() {
                val r = send(TdApi.SearchChatMessages(chatId, null, query, null, from, 0, 50, filter))
                r.messages.mapNotNullTo(buffer) { videoOf(it) }
                from = r.nextFromMessageId
                if (from == 0L || r.messages.isEmpty()) done = true
            }
        }

        private val streams = listOf(Stream(TdApi.SearchMessagesFilterVideo()), Stream(TdApi.SearchMessagesFilterDocument()))

        /** 全部取完了 */
        val done: Boolean get() = streams.all { it.done && it.buffer.isEmpty() }

        /** 再取（最多）[want] 个；两种消息按时间合在一起 */
        suspend fun next(want: Int = 30): List<TgVideo> {
            val out = ArrayList<TgVideo>()
            // 文件多、视频少的群（发的都是文档）也别一直翻：每次最多请求 8 次
            var requests = 0
            while (out.size < want) {
                // 还有没取的、缓冲又空了：先取，不然不知道它的下一个是不是更新
                val empty = streams.firstOrNull { !it.done && it.buffer.isEmpty() }
                if (empty != null) {
                    if (requests++ >= 8) break
                    empty.fetch()
                    continue
                }
                val s = streams.filter { it.buffer.isNotEmpty() }.maxByOrNull { it.buffer.first().messageId } ?: break
                out += s.buffer.removeFirst()
            }
            return out
        }
    }

    /** 说明里带这个标签的视频（同一部剧的各集），最多 [max] 个，从旧到新 */
    suspend fun seriesVideos(chatId: Long, tag: String, max: Int = 300): List<TgVideo> {
        val pager = VideoPager(chatId, tag)
        val out = ArrayList<TgVideo>()
        while (!pager.done && out.size < max) {
            val page = pager.next(50)
            if (page.isEmpty()) break
            out += page.filter { tag in it.tags }
        }
        return out.distinctBy { it.messageId }.sortedBy { it.messageId }
    }

    private val HASHTAG = Regex("""#[\p{L}\p{N}_]+""")

    private fun videoOf(m: TdApi.Message): TgVideo? {
        val (file, name, caption, duration, mini, thumb) = when (val c = m.content) {
            is TdApi.MessageVideo -> VideoParts(c.video.video, c.video.fileName, c.caption?.text.orEmpty(), c.video.duration, c.video.minithumbnail, c.video.thumbnail)
            is TdApi.MessageDocument -> {
                val d = c.document
                val isVideo = d.mimeType.startsWith("video/") || com.aisub.player.media.MediaEntry.isVideoName(d.fileName)
                if (!isVideo) return null
                VideoParts(d.document, d.fileName, c.caption?.text.orEmpty(), 0, d.minithumbnail, d.thumbnail)
            }
            else -> return null
        }
        val firstLine = caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val title = firstLine?.take(80) ?: name.ifBlank { "视频 ${m.id shr 20}" }
        return TgVideo(
            m.chatId, m.id, title, caption, name, HASHTAG.findAll(caption).map { it.value }.distinct().toList(),
            duration, file.size.takeIf { it > 0 } ?: file.expectedSize,
            m.date, mini?.data, thumb?.file,
        )
    }

    private data class VideoParts(
        val file: TdApi.File, val name: String, val caption: String, val duration: Int,
        val mini: TdApi.Minithumbnail?, val thumb: TdApi.Thumbnail?,
    )

    /** 下载缩略图，返回本地路径 */
    suspend fun thumbnailPath(file: TdApi.File): String? {
        if (file.local.isDownloadingCompleted) return file.local.path
        val f = send(TdApi.DownloadFile(file.id, 1, 0, 0, true))
        return f.local.path.takeIf { f.local.isDownloadingCompleted }
    }

    // ---- 播放：tg://msg/<会话>/<消息> ----

    fun uriOf(chatId: Long, messageId: Long) = "tg://msg/$chatId/$messageId"

    fun isTelegram(uri: String) = uri.startsWith("tg://msg/")

    /** tg:// 地址 → 会话和消息 */
    fun parse(uri: String): Pair<Long, Long>? {
        val parts = uri.removePrefix("tg://msg/").split('/', '?').take(2)
        val chat = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val msg = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return chat to msg
    }

    /** 消息里的视频文件（播放时用；文件 ID 每次登录会变，所以每次都从消息取） */
    suspend fun messageFile(chatId: Long, messageId: Long): TdApi.File {
        val m = send(TdApi.GetMessage(chatId, messageId))
        return when (val c = m.content) {
            is TdApi.MessageVideo -> c.video.video
            is TdApi.MessageDocument -> c.document.document
            else -> throw TgException(0, "这条消息里没有视频")
        }
    }

    // ---- 缓存：边下边播下载的视频，超过上限删最早的 ----

    /** 已下载的文件占用（字节） */
    suspend fun cacheSize(): Long = send(TdApi.GetStorageStatisticsFast()).filesSize

    /** 清空已下载的视频和图片（登录状态、聊天记录不动） */
    suspend fun clearCache() {
        send(TdApi.OptimizeStorage(0, 0, 0, 0, null, null, null, false, 0))
    }

    private fun trimCacheAsync() {
        val limit = cacheLimitMb.toLong() * 1024 * 1024
        // 刚看过一小时内的不删
        client?.send(TdApi.OptimizeStorage(limit, -1, -1, 3600, null, null, null, false, 0)) {}
    }

}
