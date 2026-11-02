package com.aisub.player.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.formatDuration
import com.aisub.player.media.formatSize
import com.aisub.player.telegram.Telegram
import com.aisub.player.telegram.TgAuth
import com.aisub.player.telegram.TgChat
import com.aisub.player.telegram.TgVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Telegram：填 api_id / api_hash、登录，然后列出加入的频道和群，点进去看里面的视频（[TelegramChatScreen]）。
 */
@Composable
fun TelegramScreen(onBack: () -> Unit, openChat: (TgChat) -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        Telegram.init(context)
        Telegram.start()
    }
    val auth by Telegram.auth.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var editCredentials by remember { mutableStateOf(false) }
    var cacheDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    DetailScaffold(
        "Telegram", onBack,
        actions = {
            if (auth != TgAuth.NoCredentials) Box {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多") }
                AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (auth == TgAuth.Ready) DropdownMenuItem(text = { Text("视频缓存") }, onClick = { menu = false; cacheDialog = true })
                    DropdownMenuItem(text = { Text("修改 api_id / api_hash") }, onClick = { menu = false; editCredentials = true })
                    if (auth == TgAuth.Ready) DropdownMenuItem(text = { Text("退出登录") }, onClick = {
                        menu = false
                        scope.launch { runCatching { Telegram.logout() } }
                    })
                }
            }
        },
    ) {
        when (val a = auth) {
            TgAuth.NoCredentials -> CredentialsForm(onSaved = {})
            TgAuth.Starting, TgAuth.LoggingOut -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            TgAuth.WaitPhone -> LoginStep(
                "用手机号登录 Telegram", "手机号（带国家区号，例如 +8613800000000）", KeyboardType.Phone,
                note = "验证码会发到你已登录的 Telegram App 里（没有的话发短信）。登录状态只存在这台手机上。",
            ) { Telegram.submitPhone(it) }
            is TgAuth.WaitCode -> LoginStep(
                "输入验证码", "验证码", KeyboardType.Number,
                note = if (a.viaApp) "验证码已发到 ${a.phone} 登录着的 Telegram App 里（在「Telegram」官方会话中）" else "验证码已发到 ${a.phone}",
            ) { Telegram.submitCode(it) }
            is TgAuth.WaitPassword -> LoginStep(
                "输入两步验证密码", "密码", KeyboardType.Password, password = true,
                note = if (a.hint.isNotBlank()) "密码提示：${a.hint}" else "这个账号开了两步验证",
            ) { Telegram.submitPassword(it) }
            is TgAuth.Unsupported -> EmptyState(a.reason, icon = AppIcons.Live, buttonText = "修改 api_id / api_hash") { editCredentials = true }
            TgAuth.Ready -> ChatList(openChat)
        }
    }

    if (editCredentials) AppDialog(
        onDismissRequest = { editCredentials = false },
        title = { Text("api_id / api_hash") },
        text = { CredentialsForm(onSaved = { editCredentials = false }) },
        confirmButton = { TextButton(onClick = { editCredentials = false }) { Text("取消") } },
    )
    if (cacheDialog) CacheDialog(onDismiss = { cacheDialog = false })
}

/** 填 api_id / api_hash（用户自己在 my.telegram.org 申请） */
@Composable
private fun CredentialsForm(onSaved: () -> Unit) {
    var id by remember { mutableStateOf(Telegram.apiId().takeIf { it != 0 }?.toString().orEmpty()) }
    var hash by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(PageGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("接入 Telegram", style = MaterialTheme.typography.titleMedium)
        Text(
            "Telegram 要求每个第三方客户端用自己的凭证：\n" +
                "1. 用浏览器打开 my.telegram.org，用你的手机号登录\n" +
                "2. 进「API development tools」，随便填个应用名创建\n" +
                "3. 把页面上的 api_id 和 api_hash 填到下面\n" +
                "凭证只存在这台手机上，不会进备份。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            id, { id = it.filter(Char::isDigit) }, label = { Text("api_id") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = appFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            hash, { hash = it.trim() }, label = { Text("api_hash") }, singleLine = true,
            colors = appFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        // 电视：长串用手机粘贴
        PhoneInputButton(listOf(PhoneField("id", "api_id", id), PhoneField("hash", "api_hash", hash)), title = "Telegram 凭证") { v ->
            id = v["id"]?.filter(Char::isDigit) ?: id
            hash = v["hash"]?.trim() ?: hash
        }
        val valid = id.toIntOrNull() != null && hash.length >= 16
        Button(onClick = { Telegram.setCredentials(id.toInt(), hash); onSaved() }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("保存并登录") }
    }
}

/** 登录的一步：一个输入框和「下一步」 */
@Composable
private fun LoginStep(
    title: String,
    label: String,
    type: KeyboardType,
    note: String,
    password: Boolean = false,
    submit: suspend (String) -> Unit,
) {
    var value by remember(title) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(PageGutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value, { value = it; error = null }, label = { Text(label) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = type),
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = appFieldColors(), modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        submit(value)
                    } catch (e: Exception) {
                        error = loginError(e.message)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = value.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "请稍候…" else "下一步") }
    }
}

private fun loginError(message: String?): String = when {
    message == null -> "出错了"
    "PHONE_NUMBER_INVALID" in message -> "手机号不对，要带国家区号（例如 +86）"
    "PHONE_CODE_INVALID" in message -> "验证码不对"
    "PHONE_CODE_EXPIRED" in message -> "验证码过期了，请返回重新获取"
    "PASSWORD_HASH_INVALID" in message -> "密码不对"
    "FLOOD_WAIT" in message -> "试得太频繁，Telegram 要求等一会儿再试"
    "API_ID_INVALID" in message -> "api_id / api_hash 不对"
    else -> "出错了：$message"
}

/** 加入的频道和群：顶上搜索，收藏的排最前；搜索时下面再列出没加入的公开频道 */
@Composable
private fun ChatList(openChat: (TgChat) -> Unit) {
    var reload by remember { mutableStateOf(0) }
    val chats by produceState<Result<List<TgChat>>?>(null, reload) {
        value = runCatching { Telegram.chats() }
    }
    var favorites by remember { mutableStateOf(Telegram.favorites()) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    // 公开频道：停止输入一会儿再搜，免得每个字都请求
    val public by produceState<Result<List<TgChat>>?>(null, q) {
        value = null
        if (q.length < 2) return@produceState
        kotlinx.coroutines.delay(500)
        value = runCatching { Telegram.searchPublic(q) }
    }
    fun toggle(c: TgChat) {
        val fav = c.id !in favorites
        Telegram.setFavorite(c.id, fav)
        favorites = if (fav) favorites + c.id else favorites - c.id
    }

    val r = chats
    when {
        r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        r.isFailure -> EmptyState("读取频道失败：${r.exceptionOrNull()?.message}", buttonText = "重试", icon = AppIcons.Live) { reload++ }
        else -> Column(Modifier.fillMaxSize()) {
            val all = r.getOrThrow()
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                placeholder = { Text("搜索频道和群（也能搜没加入的公开频道）") },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(AppIcons.Close, "清除") } },
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            val mine = all.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
                .sortedByDescending { it.id in favorites } // 稳定排序：收藏的在前，各自保持原来的顺序
            val others = public?.getOrNull().orEmpty().filter { p -> all.none { it.id == p.id } }
            if (all.isEmpty() && q.isEmpty()) {
                EmptyState("还没有加入频道或群\n在 Telegram App 里加入，或者在上面搜公开频道", buttonText = "刷新", icon = AppIcons.Live) { reload++ }
                return@Column
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 4.dp)) {
                itemsIndexed(mine, key = { _, c -> c.id }) { _, c ->
                    ChatRow(c, c.id in favorites, onFavorite = { toggle(c) }) { openChat(c) }
                }
                if (q.length >= 2) {
                    item(key = "public") {
                        Text(
                            "公开频道和群", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    val p = public
                    when {
                        p == null -> item(key = "public-loading") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                        }
                        p.isFailure -> item(key = "public-error") { NoteText("搜索失败：${p.exceptionOrNull()?.message}") }
                        others.isEmpty() -> item(key = "public-empty") { NoteText("没有找到其它公开频道") }
                        else -> itemsIndexed(others, key = { _, c -> "p${c.id}" }) { _, c ->
                            ChatRow(c, c.id in favorites, onFavorite = { toggle(c) }) { openChat(c) }
                        }
                    }
                } else if (mine.isEmpty()) {
                    item(key = "none") { NoteText("没有名字里带「$q」的，多输几个字可以搜公开频道") }
                }
            }
        }
    }
}

@Composable
private fun NoteText(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChatRow(c: TgChat, favorite: Boolean, onFavorite: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(c.title.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (c.isChannel) "频道" else "群", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) AppIcons.Star else AppIcons.StarBorder, if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 视频缓存：占用多少、上限、清空 */
@Composable
private fun CacheDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf<Long?>(null) }
    var limit by remember { mutableStateOf(Telegram.cacheLimitMb) }
    LaunchedEffect(Unit) { size = runCatching { Telegram.cacheSize() }.getOrNull() }
    val limits = listOf(512, 1024, 2048, 5120, 10240)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("视频缓存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("看过的视频会缓存在手机里，再看不用重新下载；超过上限时先删最早的（一小时内看过的不删）。")
                Text("现在占用：${size?.let(::formatSize) ?: "…"}")
                Text("上限", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    limits.forEach { mb ->
                        androidx.compose.material3.FilterChip(
                            selected = limit == mb,
                            onClick = { limit = mb; Telegram.cacheLimitMb = mb },
                            label = { Text(if (mb >= 1024) "${mb / 1024}G" else "${mb}M") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { Telegram.clearCache() }
                    size = runCatching { Telegram.cacheSize() }.getOrNull()
                }
            }) { Text("清空") }
        },
    )
}

/** 一个频道 / 群里的视频，从新到旧；滑到底自动加载更多 */
@Composable
fun TelegramChatScreen(chatId: Long, title: String, onBack: () -> Unit, play: (List<MediaEntry>, Int) -> Unit) {
    val videos = remember(chatId) { mutableStateListOf<TgVideo>() }
    var next by remember(chatId) { mutableStateOf(0L) }
    var done by remember(chatId) { mutableStateOf(false) }
    var loading by remember(chatId) { mutableStateOf(false) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMore() {
        if (loading || done) return
        loading = true
        error = null
        scope.launch {
            try {
                val (list, from) = Telegram.videos(chatId, next)
                videos.addAll(list.filter { v -> videos.none { it.messageId == v.messageId } })
                next = from
                if (from == 0L) done = true
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(chatId) { loadMore() }

    DetailScaffold(title, onBack) {
        when {
            videos.isEmpty() && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            videos.isEmpty() && error != null -> EmptyState("读取失败：$error", buttonText = "重试", icon = AppIcons.Live) { loadMore() }
            videos.isEmpty() && done -> EmptyState("这里没有视频", icon = AppIcons.Live)
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                itemsIndexed(videos, key = { _, v -> v.messageId }) { i, v ->
                    if (i >= videos.size - 3) LaunchedEffect(videos.size) { loadMore() }
                    VideoRow(v) {
                        val entries = videos.map {
                            MediaEntry(uri = it.uri, title = it.title, durationMs = it.durationSec * 1000L, sizeBytes = it.size)
                        }
                        play(entries, i)
                    }
                }
                if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                error?.let { e -> item { TextButton(onClick = ::loadMore, modifier = Modifier.padding(16.dp)) { Text("加载失败（$e），点这里重试") } } }
            }
        }
    }
}

@Composable
private fun VideoRow(v: TgVideo, onClick: () -> Unit) {
    // 先显示模糊的小图，缩略图下好了换清楚的
    val mini = remember(v.messageId) { v.mini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val thumb by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, v.messageId) {
        val file = v.thumb ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { Telegram.thumbnailPath(file)?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            (thumb ?: mini)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: Icon(AppIcons.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            if (v.durationSec > 0) Text(
                formatDuration(v.durationSec * 1000L), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(androidx.compose.ui.graphics.Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(v.date * 1000L))
            Text(
                listOfNotNull(date, v.size.takeIf { it > 0 }?.let(::formatSize)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
