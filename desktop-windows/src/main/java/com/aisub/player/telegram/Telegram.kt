package com.aisub.player.telegram

import android.content.Context
import android.os.Build
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

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
    /** 最后一条消息的时间（秒），0 是不知道 */
    val lastDate: Int = 0,
)

/** 一个会话里有多少视频、媒体文件，最新视频的时间（秒）；[checked] 是查的时间（毫秒） */
data class TgChatStats(val videos: Int, val media: Int, val latestVideo: Int, val checked: Long)

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
    /** 视频以外的消息（不是「仅视频」模式时列出来）：文字的正文在 [caption]，图片和文件的原文件在 [file] */
    val kind: TgKind = TgKind.VIDEO,
    val file: TdApi.File? = null,
    /** 「全部消息」模式才有：像官方客户端那样按聊天气泡显示要用的 */
    val meta: TgMsgMeta? = null,
) {
    val uri: String get() = Telegram.uriOf(chatId, messageId)
    val isText: Boolean get() = kind == TgKind.TEXT
    val isVideo: Boolean get() = kind == TgKind.VIDEO

    /** 当作「剧名」的标签：第一行里的优先（「番剧: #片名」），没有就用第一个 */
    val seriesTag: String?
        get() {
            val first = caption.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            return tags.firstOrNull { it in first } ?: tags.firstOrNull()
        }
}

enum class TgKind { VIDEO, TEXT, PHOTO, FILE }

/** 发消息的人（或频道、群），名字颜色按官方的 7 种 */
data class TgSender(val id: Long, val name: String, val colorIndex: Int, val photo: TdApi.File?, val photoMini: ByteArray?)

/**
 * 聊天气泡要显示的：谁发的、转发自谁、回复的哪条、表情回应、频道帖的阅读数；
 * [mediaWidth] / [mediaHeight] 是图片、视频的原始宽高（气泡里按比例显示）
 */
data class TgMsgMeta(
    val sender: TgSender?,
    val isChannelPost: Boolean,
    val authorSignature: String,
    val forwardFrom: String?,
    val replyName: String?,
    val replyColorIndex: Int,
    val replyText: String?,
    val reactions: List<Pair<String, Int>>,
    val views: Int,
    val edited: Boolean,
    val mediaWidth: Int,
    val mediaHeight: Int,
)

/** 频道页列出哪些消息（频道里常有大量文字、图片、广告，默认只看视频） */
enum class TgMode(val label: String) {
    ALL("全部消息"), VIDEO("仅视频"), VIDEO_PHOTO("视频+图片"), FILES("文件"),
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
        client = runCatching {
            Client.execute(TdApi.SetLogVerbosityLevel(1))
            Client.create({ onUpdate(it) }, null, null)
        }.onFailure { e ->
            _auth.value = TgAuth.Unsupported("Windows 桌面版暂未安装 TDLib (tdjni.dll) 组件（${e.message}）")
        }.getOrNull()
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
        return c.copy(photo = chat.photo?.small, photoMini = chat.photo?.minithumbnail?.data, lastDate = chat.lastMessage?.date ?: 0)
    }

    // ---- 频道列表：视频数、媒体数（筛选、排序用），置顶、分组 ----

    /** 上次查到的（存着，打开列表马上能用） */
    fun cachedStats(chatId: Long): TgChatStats? = prefs.getString("stats_$chatId", null)?.split(',')
        ?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 4 }
        ?.let { (v, m, d, t) -> TgChatStats(v.toInt(), m.toInt(), d.toInt(), t) }

    /**
     * 查一个会话的视频数、媒体数。服务器按类型数（不用自己翻消息）；文件形式发的不一定是视频，
     * 按最近 20 个文件里视频占的比例估算。
     */
    suspend fun chatStats(chatId: Long): TgChatStats {
        suspend fun count(f: TdApi.SearchMessagesFilter) =
            runCatching { send(TdApi.GetChatMessageCount(chatId, null, f, false)).count }.getOrDefault(0)
        suspend fun recent(f: TdApi.SearchMessagesFilter, n: Int) =
            runCatching { send(TdApi.SearchChatMessages(chatId, null, "", null, 0, 0, n, f)).messages.toList() }.getOrDefault(emptyList())
        val videos = count(TdApi.SearchMessagesFilterVideo())
        val docs = count(TdApi.SearchMessagesFilterDocument())
        val others = count(TdApi.SearchMessagesFilterPhoto()) + count(TdApi.SearchMessagesFilterAudio()) + count(TdApi.SearchMessagesFilterAnimation())
        val recentDocs = recent(TdApi.SearchMessagesFilterDocument(), 20)
        val docVideos = recentDocs.filter { videoOf(it) != null }
        val docVideoCount = if (recentDocs.isEmpty()) 0 else (docs.toLong() * docVideos.size / recentDocs.size).toInt()
        val latest = maxOf(recent(TdApi.SearchMessagesFilterVideo(), 1).firstOrNull()?.date ?: 0, docVideos.firstOrNull()?.date ?: 0)
        val s = TgChatStats(videos + docVideoCount, videos + docs + others, latest, System.currentTimeMillis())
        prefs.edit().putString("stats_$chatId", "${s.videos},${s.media},${s.latestVideo},${s.checked}").apply()
        return s
    }

    /** 置顶的会话，按置顶的先后（后置顶的在前） */
    fun pinned(): List<Long> = prefs.getString("pinned", "")!!.split(',').mapNotNull { it.toLongOrNull() }

    fun setPinned(chatId: Long, pinned: Boolean) {
        val list = pinned().filter { it != chatId }.let { if (pinned) listOf(chatId) + it else it }
        prefs.edit().putString("pinned", list.joinToString(",")).apply()
    }

    /** 自己建的分组（按建的顺序） */
    fun groups(): List<String> = runCatching {
        val a = org.json.JSONArray(prefs.getString("groups", "[]"))
        List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())

    /** 会话 → 所在分组 */
    fun chatGroups(): Map<Long, String> = runCatching {
        val o = org.json.JSONObject(prefs.getString("chatGroups", "{}")!!)
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { it to o.getString(k) } }.toMap()
    }.getOrDefault(emptyMap())

    fun saveGroups(groups: List<String>, chatGroups: Map<Long, String>) {
        val o = org.json.JSONObject()
        chatGroups.filterValues { it in groups }.forEach { (k, v) -> o.put(k.toString(), v) }
        prefs.edit().putString("groups", org.json.JSONArray(groups).toString()).putString("chatGroups", o.toString()).apply()
    }

    /** 频道列表的显示和排序（记住上次选的） */
    var chatFilter: Int
        get() = prefs.getInt("chatFilter", 0)
        set(v) = prefs.edit().putInt("chatFilter", v).apply()

    var chatSort: Int
        get() = prefs.getInt("chatSort", 0)
        set(v) = prefs.edit().putInt("chatSort", v).apply()

    /** 按名字或 @用户名 搜公开的频道和群（包括没加入的），带成员数和 @用户名 */
    suspend fun searchPublic(query: String): List<TgChat> {
        // 按名字搜不到用户名完全一样的频道：用户名或 t.me 链接再精确找一次，放最前
        val username = USERNAME.matchEntire(query.trim())?.groupValues?.get(1)
        val exact = username?.let { runCatching { send(TdApi.SearchPublicChat(it)).id }.getOrNull() }
        val ids = listOfNotNull(exact) + send(TdApi.SearchPublicChats(query, null)).chatIds.toList()
        return ids.distinct().mapNotNull { chatOf(it, details = true) }
    }

    private val USERNAME = Regex("""(?:https?://)?(?:t\.me/|@)?([A-Za-z][A-Za-z0-9_]{3,31})/?""")

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
     * [mode]：除了视频还列出哪些消息（和视频按时间排在一起）。
     */
    class VideoPager(private val chatId: Long, private val query: String = "", private val mode: TgMode = TgMode.VIDEO) {
        private inner class Stream(val filter: TdApi.SearchMessagesFilter) {
            var from = 0L
            var done = false
            val buffer = ArrayDeque<TgVideo>()

            suspend fun fetch() {
                val r = send(TdApi.SearchChatMessages(chatId, null, query, null, from, 0, 50, filter))
                val page = r.messages.toList()
                val found = ArrayList<TgVideo>()
                for (m in page) {
                    // 「文件」模式只要文件形式发的（视频文件也算）
                    if (mode == TgMode.FILES && m.content !is TdApi.MessageDocument) continue
                    val v = videoOf(m, groupCaption(m, page))
                    if (v != null) { found += v; continue }
                    when (mode) {
                        TgMode.ALL -> (textOf(m) ?: photoOf(m) ?: fileOf(m) ?: otherOf(m))?.let { found += it }
                        TgMode.VIDEO_PHOTO -> photoOf(m)?.let { found += it }
                        TgMode.FILES -> fileOf(m)?.let { found += it }
                        TgMode.VIDEO -> Unit
                    }
                    // 搜到的是相册封面图或文字帖：把同一组的视频带出来
                    if (filter is TdApi.SearchMessagesFilterEmpty && query.isNotBlank()) found += groupVideos(m)
                }
                val list = found.distinctBy { it.messageId }.sortedByDescending { it.messageId }
                // 「全部消息」按聊天气泡显示：补上发送人、回复、表情回应（同时查，TDLib 本地有的不联网）
                if (mode == TgMode.ALL) kotlinx.coroutines.coroutineScope {
                    list.map { v ->
                        async { (page.firstOrNull { it.id == v.messageId } ?: known[v.messageId])?.let { v.copy(meta = metaOf(it)) } ?: v }
                    }.awaitAll()
                }.toCollection(buffer)
                else list.toCollection(buffer)
                from = r.nextFromMessageId
                if (from == 0L || r.messages.isEmpty()) done = true
            }
        }

        // 搜索时多一路不限类型：标签常写在相册的封面图或单独的文字帖里，视频本身没有说明
        // 「全部消息」一路不限类型就够了；文件模式只要文件
        private val streams = when (mode) {
            TgMode.ALL -> listOf(Stream(TdApi.SearchMessagesFilterEmpty()))
            TgMode.FILES -> listOf(Stream(TdApi.SearchMessagesFilterDocument()))
            else -> listOfNotNull(
                Stream(TdApi.SearchMessagesFilterVideo()), Stream(TdApi.SearchMessagesFilterDocument()),
                if (mode == TgMode.VIDEO_PHOTO) Stream(TdApi.SearchMessagesFilterPhoto()) else null,
                if (query.isNotBlank()) Stream(TdApi.SearchMessagesFilterEmpty()) else null,
            )
        }
        private val returned = HashSet<Long>()

        /** 取到过的前后消息，按 ID 排好；[covered] 是其中连续取到的区间 */
        private val known = java.util.TreeMap<Long, TdApi.Message>()
        private val covered = ArrayList<LongRange>()

        /** [m] 前后紧挨着的消息（TDLib 本地有就不用联网），返回把它们都连续取到了的区间 */
        private suspend fun window(m: TdApi.Message): LongRange? {
            covered.firstOrNull { m.id in it && it.first < m.id && it.last > m.id }?.let { return it }
            // TDLib 第一次可能只返回一部分，不够再要一次
            var range: LongRange? = null
            for (attempt in 0 until 2) {
                val h = runCatching { send(TdApi.GetChatHistory(chatId, m.id, -8, 17, false)).messages }.getOrNull().orEmpty()
                if (h.isEmpty()) break
                h.forEach { known[it.id] = it }
                val r = h.minOf { it.id }..h.maxOf { it.id }
                range = r
                if (r.first < m.id) { covered += r; break }
            }
            return range
        }

        private suspend fun before(m: TdApi.Message): TdApi.Message? =
            window(m)?.let { r -> known.lowerEntry(m.id)?.value?.takeIf { it.id in r } }

        private suspend fun after(m: TdApi.Message): TdApi.Message? =
            window(m)?.let { r -> known.higherEntry(m.id)?.value?.takeIf { it.id in r } }

        private suspend fun album(m: TdApi.Message): List<TdApi.Message> {
            val r = window(m) ?: return listOf(m)
            return known.subMap(r.first, true, r.last, true).values.filter { it.mediaAlbumId == m.mediaAlbumId }
        }

        /**
         * 视频自己没说明时用谁的：同一相册里带说明的那条（常是封面图）；不在相册里的话，
         * 紧挨着的前一条不是视频、带说明、几分钟内发的（频道常先发封面图或文字，再发视频）。
         */
        private suspend fun groupCaption(m: TdApi.Message, page: List<TdApi.Message>): String? {
            if (captionOf(m).isNotBlank() || videoOf(m) == null) return null
            if (m.mediaAlbumId != 0L) return page.albumCaption(m.mediaAlbumId) ?: album(m).albumCaption(m.mediaAlbumId)
            val prev = before(m) ?: return null
            if (prev.mediaAlbumId != 0L || videoOf(prev) != null || abs(m.date - prev.date) > 300) return null
            return captionOf(prev).ifBlank { null }
        }

        private fun Collection<TdApi.Message>.albumCaption(album: Long): String? =
            firstOrNull { it.mediaAlbumId == album && captionOf(it).isNotBlank() }?.let(::captionOf)

        /** [m] 不是视频（搜索命中了封面图或文字帖）：它带着的视频，也就是同一相册里的，或紧挨着的后一条 */
        private suspend fun groupVideos(m: TdApi.Message): List<TgVideo> {
            val caption = captionOf(m).ifBlank { return emptyList() }
            val group = if (m.mediaAlbumId != 0L) album(m)
            else listOfNotNull(after(m)?.takeIf { it.mediaAlbumId == 0L && captionOf(it).isBlank() && abs(it.date - m.date) <= 300 })
            return group.mapNotNull { videoOf(it, caption) }
        }

        /** 不同的路会搜到同一个视频（例如说明里有搜索词的视频），每个只给一次 */
        private fun Stream.take(): TgVideo? {
            while (buffer.isNotEmpty()) {
                val v = buffer.removeFirst()
                if (returned.add(v.messageId)) return v
            }
            return null
        }

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
                s.take()?.let { out += it }
            }
            return out
        }
    }

    // ---- 视频收藏夹：只存会话、消息编号和当时的标题说明，打开收藏夹时重新取消息（文件 ID 每次登录会变） ----

    /** 收藏的一个视频；[caption] 是收藏时看到的说明（视频自己没说明、用的是相册或同组的说明时靠它） */
    data class SavedVideo(val chatId: Long, val messageId: Long, val chatTitle: String, val title: String, val caption: String, val added: Long) {
        val key get() = "$chatId/$messageId"
    }

    /** 收藏的视频，最近收藏的在前 */
    fun savedVideos(): List<SavedVideo> = runCatching {
        val a = org.json.JSONArray(prefs.getString("savedVideos", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            SavedVideo(o.getLong("c"), o.getLong("m"), o.optString("ct"), o.optString("t"), o.optString("cap"), o.optLong("a"))
        }
    }.getOrDefault(emptyList())

    fun savedKeys(): Set<String> = savedVideos().map { it.key }.toSet()

    fun setSaved(v: TgVideo, chatTitle: String, saved: Boolean) {
        val rest = savedVideos().filter { it.chatId != v.chatId || it.messageId != v.messageId }
        val list = if (saved) listOf(SavedVideo(v.chatId, v.messageId, chatTitle, v.title, v.caption.take(1000), System.currentTimeMillis())) + rest else rest
        val a = org.json.JSONArray()
        list.forEach { s ->
            a.put(org.json.JSONObject().put("c", s.chatId).put("m", s.messageId).put("ct", s.chatTitle).put("t", s.title).put("cap", s.caption).put("a", s.added))
        }
        prefs.edit().putString("savedVideos", a.toString()).apply()
    }

    /** 取回收藏的视频（同时最多取 6 个）；消息被删了、看不到了的是 null */
    suspend fun loadSaved(list: List<SavedVideo>): List<Pair<SavedVideo, TgVideo?>> = kotlinx.coroutines.coroutineScope {
        val gate = kotlinx.coroutines.sync.Semaphore(6)
        list.map { s ->
            async {
                gate.withPermit {
                    s to runCatching { videoOf(send(TdApi.GetMessage(s.chatId, s.messageId)), s.caption) }.getOrNull()
                        ?.let { v -> if (v.caption.isBlank() && s.title.isNotBlank()) v.copy(title = s.title) else v }
                }
            }
        }.awaitAll()
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

    // 也认全角的「＃」（有的频道这样写）
    private val HASHTAG = Regex("""[#＃][\p{L}\p{N}_]+""")

    /** 消息的说明（文字帖就是正文） */
    private fun captionOf(m: TdApi.Message): String = when (val c = m.content) {
        is TdApi.MessageVideo -> c.caption?.text
        is TdApi.MessageDocument -> c.caption?.text
        is TdApi.MessagePhoto -> c.caption?.text
        is TdApi.MessageAnimation -> c.caption?.text
        is TdApi.MessageText -> c.text?.text
        else -> null
    }.orEmpty()

    /** [inherited]：视频自己没说明时用的说明（同一相册或同一组的） */
    private fun videoOf(m: TdApi.Message, inherited: String? = null): TgVideo? {
        val own = captionOf(m)
        val (file, name, caption, duration, mini, thumb) = when (val c = m.content) {
            is TdApi.MessageVideo -> VideoParts(c.video.video, c.video.fileName, own.ifBlank { inherited.orEmpty() }, c.video.duration, c.video.minithumbnail, c.video.thumbnail)
            is TdApi.MessageDocument -> {
                val d = c.document
                val isVideo = d.mimeType.startsWith("video/") || com.aisub.player.media.MediaEntry.isVideoName(d.fileName)
                if (!isVideo) return null
                VideoParts(d.document, d.fileName, own.ifBlank { inherited.orEmpty() }, 0, d.minithumbnail, d.thumbnail)
            }
            else -> return null
        }
        val firstLine = caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val title = firstLine?.take(80) ?: name.ifBlank { "视频 ${m.id shr 20}" }
        return TgVideo(
            m.chatId, m.id, title, caption, name, HASHTAG.findAll(caption).map { "#" + it.value.drop(1) }.distinct().toList(),
            duration, file.size.takeIf { it > 0 } ?: file.expectedSize,
            m.date, mini?.data, thumb?.file,
        )
    }

    private fun tagsOf(text: String) = HASHTAG.findAll(text).map { "#" + it.value.drop(1) }.distinct().toList()

    private fun firstLine(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)

    /** 文字消息 → 列表里的一条 */
    private fun textOf(m: TdApi.Message): TgVideo? {
        val text = (m.content as? TdApi.MessageText)?.text?.text?.trim()?.ifBlank { null } ?: return null
        return TgVideo(m.chatId, m.id, firstLine(text)!!, text, "", tagsOf(text), 0, 0, m.date, null, null, TgKind.TEXT)
    }

    /** 图片消息：缩略图用中等大小的，[TgVideo.file] 是最大的那张 */
    private fun photoOf(m: TdApi.Message): TgVideo? {
        val c = m.content as? TdApi.MessagePhoto ?: return null
        val sizes = c.photo.sizes.sortedBy { it.width * it.height }
        val full = sizes.lastOrNull() ?: return null
        val thumb = sizes.firstOrNull { it.width >= 320 } ?: full
        val caption = c.caption?.text.orEmpty()
        return TgVideo(
            m.chatId, m.id, firstLine(caption) ?: "图片", caption, "", tagsOf(caption), 0,
            full.photo.size.takeIf { it > 0 } ?: full.photo.expectedSize, m.date, c.photo.minithumbnail?.data, thumb.photo,
            TgKind.PHOTO, full.photo,
        )
    }

    /** 不是视频的文件（压缩包、字幕、文档…） */
    private fun fileOf(m: TdApi.Message): TgVideo? {
        val d = (m.content as? TdApi.MessageDocument) ?: return null
        val caption = d.caption?.text.orEmpty()
        val f = d.document.document
        return TgVideo(
            m.chatId, m.id, d.document.fileName.ifBlank { firstLine(caption) ?: "文件" }, caption, d.document.fileName, tagsOf(caption), 0,
            f.size.takeIf { it > 0 } ?: f.expectedSize, m.date, d.document.minithumbnail?.data, d.document.thumbnail?.file,
            TgKind.FILE, f,
        )
    }

    /** 「全部消息」里其他能写成一句话的消息（贴纸、语音、投票…）；入群、置顶这些系统消息不列 */
    private fun otherOf(m: TdApi.Message): TgVideo? {
        fun sec(s: Int) = "%d:%02d".format(s / 60, s % 60)
        val text = when (val c = m.content) {
            is TdApi.MessageSticker -> c.sticker.emoji.ifBlank { "[贴纸]" }
            is TdApi.MessageAnimation -> "[GIF] ${c.caption?.text.orEmpty()}"
            is TdApi.MessageVoiceNote -> "[语音 ${sec(c.voiceNote.duration)}] ${c.caption?.text.orEmpty()}"
            is TdApi.MessageVideoNote -> "[视频消息 ${sec(c.videoNote.duration)}]"
            is TdApi.MessageAudio -> "[音乐] ${listOf(c.audio.performer, c.audio.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { c.audio.fileName }}\n${c.caption?.text.orEmpty()}"
            is TdApi.MessagePoll -> "[投票] ${c.poll.question.text}\n" + c.poll.options.joinToString("\n") { "○ " + it.text.text }
            is TdApi.MessageDice -> c.emoji
            else -> null
        }?.trim()?.ifBlank { null } ?: return null
        return TgVideo(m.chatId, m.id, firstLine(text)!!, text, "", tagsOf(text), 0, 0, m.date, null, null, TgKind.TEXT)
    }

    private val senders = java.util.concurrent.ConcurrentHashMap<Long, TgSender>()

    /** 用户或会话（频道、匿名管理员）→ 名字和头像；查过的记着（TDLib 本地一般就有） */
    private suspend fun senderOf(s: TdApi.MessageSender?): TgSender? {
        val id = when (s) {
            is TdApi.MessageSenderUser -> s.userId
            is TdApi.MessageSenderChat -> s.chatId
            else -> return null
        }
        senders[id]?.let { return it }
        val sender = runCatching {
            if (s is TdApi.MessageSenderUser) send(TdApi.GetUser(id)).let { u ->
                TgSender(id, "${u.firstName} ${u.lastName}".trim().ifBlank { "已注销账号" }, colorIndexOf(u.accentColorId, id), u.profilePhoto?.small, u.profilePhoto?.minithumbnail?.data)
            } else send(TdApi.GetChat(id)).let { c ->
                TgSender(id, c.title, colorIndexOf(c.accentColorId, id), c.photo?.small, c.photo?.minithumbnail?.data)
            }
        }.getOrNull() ?: return null
        senders[id] = sender
        return sender
    }

    /** 官方的名字颜色：前 7 种是固定的，其他（会员自选的）按 ID 落在这 7 种里 */
    private fun colorIndexOf(accent: Int, id: Long) = if (accent in 0..6) accent else (abs(id) % 7).toInt()

    /** 气泡上显示的：发送人、转发来源、回复的哪条、表情回应、阅读数 */
    private suspend fun metaOf(m: TdApi.Message): TgMsgMeta {
        val forward = m.forwardInfo?.origin?.let { o ->
            when (o) {
                is TdApi.MessageOriginUser -> senderOf(TdApi.MessageSenderUser(o.senderUserId))?.name
                is TdApi.MessageOriginChat -> senderOf(TdApi.MessageSenderChat(o.senderChatId))?.name
                is TdApi.MessageOriginChannel -> senderOf(TdApi.MessageSenderChat(o.chatId))?.name
                is TdApi.MessageOriginHiddenUser -> o.senderName
                else -> null
            }
        }
        // 回复的那条：取不到（删了、在别的会话）就用回复里带的引用
        val replyTo = m.replyTo as? TdApi.MessageReplyToMessage
        val replied = replyTo?.let { runCatching { send(TdApi.GetRepliedMessage(m.chatId, m.id)) }.getOrNull() }
        val replySender = replied?.let { senderOf(it.senderId) }
        val replyText = replyTo?.quote?.text?.text?.ifBlank { null }
            ?: replied?.content?.let(::summaryOf)
            ?: replyTo?.content?.let(::summaryOf)
        val (w, h) = when (val c = m.content) {
            is TdApi.MessagePhoto -> c.photo.sizes.maxByOrNull { it.width * it.height }?.let { it.width to it.height } ?: (0 to 0)
            is TdApi.MessageVideo -> c.video.width to c.video.height
            else -> 0 to 0
        }
        return TgMsgMeta(
            sender = senderOf(m.senderId),
            isChannelPost = m.isChannelPost,
            authorSignature = m.authorSignature.orEmpty(),
            forwardFrom = forward,
            replyName = if (replyTo == null) null else replySender?.name ?: "消息",
            replyColorIndex = replySender?.colorIndex ?: 5,
            replyText = replyText,
            reactions = m.interactionInfo?.reactions?.reactions.orEmpty().mapNotNull { r ->
                (r.type as? TdApi.ReactionTypeEmoji)?.emoji?.let { it to r.totalCount }
            },
            views = m.interactionInfo?.viewCount ?: 0,
            edited = m.editDate > 0,
            mediaWidth = w, mediaHeight = h,
        )
    }

    /** 被回复的消息在引用框里显示的一行 */
    private fun summaryOf(c: TdApi.MessageContent): String {
        val caption = when (c) {
            is TdApi.MessageText -> c.text?.text
            is TdApi.MessageVideo -> c.caption?.text
            is TdApi.MessageDocument -> c.caption?.text
            is TdApi.MessagePhoto -> c.caption?.text
            is TdApi.MessageAnimation -> c.caption?.text
            else -> null
        }.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        val kind = when (c) {
            is TdApi.MessageVideo -> "视频"
            is TdApi.MessagePhoto -> "图片"
            is TdApi.MessageDocument -> c.document.fileName.ifBlank { "文件" }
            is TdApi.MessageSticker -> c.sticker.emoji + " 贴纸"
            is TdApi.MessageAnimation -> "GIF"
            is TdApi.MessageVoiceNote -> "语音"
            is TdApi.MessageAudio -> "音乐"
            is TdApi.MessagePoll -> "投票"
            else -> null
        }
        return caption ?: kind ?: "消息"
    }

    /** 频道页列出哪些消息（默认仅视频） */
    var mode: TgMode
        get() = runCatching { TgMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(TgMode.VIDEO)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    /** 下载整个文件（图片、文档），返回本地路径；下好前一直等 */
    suspend fun downloadPath(file: TdApi.File): String? {
        if (file.local.isDownloadingCompleted) return file.local.path
        val f = send(TdApi.DownloadFile(file.id, 16, 0, 0, true))
        return f.local.path.takeIf { f.local.isDownloadingCompleted }
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
