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
data class TgChat(val id: Long, val title: String, val isChannel: Boolean)

/** 频道 / 群里的一个视频（视频消息，或文件形式发的视频） */
data class TgVideo(
    val chatId: Long,
    val messageId: Long,
    val title: String,
    val caption: String,
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
        val ids = send(TdApi.GetChats(TdApi.ChatListMain(), 500)).chatIds
        return ids.toList().mapNotNull { id ->
            val chat = runCatching { send(TdApi.GetChat(id)) }.getOrNull() ?: return@mapNotNull null
            when (val t = chat.type) {
                is TdApi.ChatTypeSupergroup -> TgChat(id, chat.title, t.isChannel)
                is TdApi.ChatTypeBasicGroup -> TgChat(id, chat.title, false)
                else -> null
            }
        }
    }

    /**
     * 往前翻聊天记录找视频：从 [fromMessageId]（0 是最新）开始，凑够 [want] 个或者翻到头为止。
     * 返回找到的视频和下次接着翻的位置（0 表示到头了）。
     */
    suspend fun videos(chatId: Long, fromMessageId: Long, want: Int = 20): Pair<List<TgVideo>, Long> {
        val out = ArrayList<TgVideo>()
        var from = fromMessageId
        var emptyPages = 0
        // 每次最多翻 15 页，免得一个全是文字的频道翻半天
        repeat(15) {
            val page = send(TdApi.GetChatHistory(chatId, from, 0, 100, false)).messages
                .filter { it.id != from || from == 0L }
            if (page.isEmpty()) {
                // TDLib 第一次请求有时只给本地有的几条：再试一次，还是空就是到头了
                if (++emptyPages >= 2) return out to 0L
                return@repeat
            }
            emptyPages = 0
            page.mapNotNullTo(out) { videoOf(it) }
            from = page.last().id
            if (out.size >= want) return out to from
        }
        return out to from
    }

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
            m.chatId, m.id, title, caption, duration, file.size.takeIf { it > 0 } ?: file.expectedSize,
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
