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
import androidx.compose.foundation.layout.height
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
fun TelegramScreen(onBack: () -> Unit, openSaved: () -> Unit, openChat: (TgChat) -> Unit) {
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
            if (auth == TgAuth.Ready) IconButton(onClick = openSaved) { Icon(AppIcons.Favorite, "收藏夹") }
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
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = appFieldColors(), modifier = Modifier.fillMaxWidth(), phoneInput = false,
        )
        OutlinedTextField(
            hash, { hash = it.trim() }, label = { Text("api_hash") }, singleLine = true,
            colors = appFieldColors(), modifier = Modifier.fillMaxWidth(), phoneInput = false,
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
    var pinned by remember { mutableStateOf(Telegram.pinned()) }
    var groups by remember { mutableStateOf(Telegram.groups()) }
    var chatGroups by remember { mutableStateOf(Telegram.chatGroups()) }
    var filter by remember { mutableStateOf(Telegram.chatFilter) }
    var sort by remember { mutableStateOf(Telegram.chatSort) }
    /** 看哪一组：null 是全部，[FAVORITES] 是收藏的，其余是自己建的分组 */
    var group by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<TgChat?>(null) }
    var managing by remember { mutableStateOf(false) }
    fun saveGroups(g: List<String>, cg: Map<Long, String>) {
        groups = g
        chatGroups = cg.filterValues { it in g }
        if (group != null && group != FAVORITES && group !in g) group = null
        Telegram.saveGroups(groups, chatGroups)
    }
    fun togglePin(c: TgChat) {
        val pin = c.id !in pinned
        Telegram.setPinned(c.id, pin)
        pinned = Telegram.pinned()
    }
    // 各会话的视频数、媒体数：先用存着的，超过 6 小时的在后台重新查（同时最多查 4 个）
    val stats = remember { androidx.compose.runtime.mutableStateMapOf<Long, com.aisub.player.telegram.TgChatStats>() }
    LaunchedEffect(chats) {
        val list = chats?.getOrNull() ?: return@LaunchedEffect
        list.forEach { c -> Telegram.cachedStats(c.id)?.let { stats[c.id] = it } }
        val stale = System.currentTimeMillis() - 6 * 3600_000L
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        kotlinx.coroutines.coroutineScope {
            list.filter { (stats[it.id]?.checked ?: 0L) < stale }.forEach { c ->
                launch { gate.acquire(); try { runCatching { Telegram.chatStats(c.id) }.getOrNull()?.let { stats[c.id] = it } } finally { gate.release() } }
            }
        }
    }
    // 收藏的频道有没有新视频（比上次点进去时看到的新）；第一次查的只记下来，不算新
    val fresh by produceState(emptySet<Long>(), favorites, chats) {
        val ids = favorites.toList()
        value = ids.filter { id ->
            val latest = runCatching { Telegram.latestVideoId(id) }.getOrDefault(0L)
            val seen = Telegram.seenVideoId(id)
            if (seen == 0L && latest > 0) Telegram.setSeenVideoId(id, latest)
            seen in 1 until latest
        }.toSet()
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
            // 已加入的也按服务器的结果算（输入 @用户名 或 t.me 链接时名字里没有这些字）
            val publicIds = public?.getOrNull().orEmpty().map { it.id }.toSet()
            val matched = all.filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.id in publicIds }
                .filter { c ->
                    // 还没查到数量的先显示着
                    val s = stats[c.id]
                    when (filter) {
                        1 -> s == null || s.videos > 0
                        2 -> s == null || s.media > 0
                        else -> true
                    }
                }
                .filter { c -> group == null || (group == FAVORITES && c.id in favorites) || chatGroups[c.id] == group }
            // 都是稳定排序：置顶的最前（按置顶先后），其余按选的方式排
            val sorted = when (sort) {
                1 -> matched.sortedByDescending { stats[it.id]?.videos ?: -1 }
                2 -> matched.sortedByDescending { stats[it.id]?.latestVideo?.takeIf { d -> d > 0 } ?: it.lastDate }
                else -> matched.sortedByDescending { it.id in favorites }
            }
            val mine = sorted.sortedBy { pinned.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { MenuChip(CHAT_FILTERS, filter, highlighted = filter > 0) { filter = it; Telegram.chatFilter = it } }
                item { MenuChip(CHAT_SORTS, sort, highlighted = sort > 0) { sort = it; Telegram.chatSort = it } }
                // 竖线隔开：前面是显示方式和排序，后面是分组（免得「所有分组」和「全部频道」看着像一回事）
                item {
                    Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                }
                val tabs = listOf<String?>(null, FAVORITES) + groups
                items(tabs.size) { i ->
                    val g = tabs[i]
                    androidx.compose.material3.FilterChip(
                        selected = group == g,
                        onClick = { group = g },
                        label = { Text(g ?: "所有分组") },
                    )
                }
                item {
                    androidx.compose.material3.AssistChip(
                        onClick = { managing = true },
                        label = { Text("管理分组") },
                        leadingIcon = { Icon(AppIcons.Edit, null, Modifier.size(18.dp)) },
                    )
                }
            }
            val others = public?.getOrNull().orEmpty().filter { p -> all.none { it.id == p.id } }
            if (all.isEmpty() && q.isEmpty()) {
                EmptyState("还没有加入频道或群\n在 Telegram App 里加入，或者在上面搜公开频道", buttonText = "刷新", icon = AppIcons.Live) { reload++ }
                return@Column
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 4.dp)) {
                itemsIndexed(mine, key = { _, c -> c.id }) { _, c ->
                    ChatRow(
                        c, c.id in favorites, c.id in fresh, onFavorite = { toggle(c) },
                        pinned = c.id in pinned, group = chatGroups[c.id], stats = stats[c.id],
                        onPin = { togglePin(c) }, onMove = { moving = c },
                    ) { openChat(c) }
                }
                if (mine.isEmpty() && all.isNotEmpty() && q.isEmpty()) item(key = "filtered") {
                    NoteText(if (group != null) "这一组还没有频道，在频道右边的「⋮」里可以移进来" else "没有符合条件的频道")
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
                            ChatRow(c, c.id in favorites, c.id in fresh, onFavorite = { toggle(c) }) { openChat(c) }
                        }
                    }
                } else if (mine.isEmpty() && q.isNotEmpty()) {
                    item(key = "none") { NoteText("没有名字里带「$q」的，多输几个字可以搜公开频道") }
                }
            }
        }
    }

    moving?.let { c ->
        MoveToGroupDialog(
            c.title, groups, chatGroups[c.id],
            onDismiss = { moving = null },
        ) { g, newGroup ->
            val gs = if (newGroup && g != null && g !in groups) groups + g else groups
            saveGroups(gs, if (g == null) chatGroups - c.id else chatGroups + (c.id to g))
            moving = null
        }
    }
    if (managing) ManageGroupsDialog(
        groups, chatGroups,
        onDismiss = { managing = false },
    ) { g, cg -> saveGroups(g, cg) }
}

private const val FAVORITES = "收藏"
private val CHAT_FILTERS = listOf("全部频道", "只看有视频的", "隐藏没有媒体的")
private val CHAT_SORTS = listOf("默认排序", "按视频数量", "按更新时间")

/** 点开选一项的按钮，显示选中的那项 */
@Composable
private fun MenuChip(options: List<String>, selected: Int, highlighted: Boolean, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.FilterChip(
            selected = highlighted,
            onClick = { open = true },
            label = { Text(options[selected.coerceIn(options.indices)]) },
            trailingIcon = { Icon(AppIcons.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(o) },
                    onClick = { open = false; onSelect(i) },
                    trailingIcon = if (i == selected) ({ Icon(AppIcons.Check, null) }) else null,
                )
            }
        }
    }
}

/** 把一个频道移到某个分组（或新建一个）；选「不分组」就移出来 */
@Composable
private fun MoveToGroupDialog(
    title: String,
    groups: List<String>,
    current: String?,
    onDismiss: () -> Unit,
    onPick: (group: String?, isNew: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("「$title」移到分组") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                (listOf<String?>(null) + groups).forEach { g ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(g, false) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(g ?: "不分组", Modifier.weight(1f))
                        if (g == current) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedTextField(
                    name, { name = it }, singleLine = true, label = { Text("新建分组") },
                    colors = appFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), phoneLabel = "分组名",
                )
            }
        },
        confirmButton = {
            val n = name.trim()
            TextButton(onClick = { onPick(n, true) }, enabled = n.isNotEmpty() && n != FAVORITES) { Text("新建并移入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 新建、改名、删除分组（删分组不会删频道，只是移出来） */
@Composable
private fun ManageGroupsDialog(
    groups: List<String>,
    chatGroups: Map<Long, String>,
    onDismiss: () -> Unit,
    onSave: (List<String>, Map<Long, String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (groups.isEmpty()) Text("还没有分组", color = MaterialTheme.colorScheme.onSurfaceVariant)
                groups.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val n = chatGroups.count { it.value == g }
                        Text("$g（$n）", Modifier.weight(1f))
                        IconButton(onClick = { renaming = g; name = g }) { Icon(AppIcons.Edit, "改名") }
                        IconButton(onClick = { onSave(groups - g, chatGroups.filterValues { it != g }) }) { Icon(AppIcons.Delete, "删除") }
                    }
                }
                OutlinedTextField(
                    name, { name = it }, singleLine = true,
                    label = { Text(renaming?.let { "「$it」改名为" } ?: "新建分组") },
                    colors = appFieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp), phoneLabel = "分组名",
                )
            }
        },
        confirmButton = {
            val n = name.trim()
            val ok = n.isNotEmpty() && n != FAVORITES && (n !in groups || n == renaming)
            TextButton(enabled = ok, onClick = {
                val old = renaming
                if (old == null) onSave(groups + n, chatGroups)
                else onSave(groups.map { if (it == old) n else it }, chatGroups.mapValues { if (it.value == old) n else it.value })
                name = ""
                renaming = null
            }) { Text(if (renaming != null) "改名" else "新建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun NoteText(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChatRow(
    c: TgChat,
    favorite: Boolean,
    fresh: Boolean,
    onFavorite: () -> Unit,
    pinned: Boolean = false,
    group: String? = null,
    stats: com.aisub.player.telegram.TgChatStats? = null,
    /** 置顶、分组只给加入的会话（搜到的公开频道没有） */
    onPin: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ChatAvatar(c)
            // 收藏的频道有新视频
            if (fresh) Box(
                Modifier.align(Alignment.TopEnd).size(12.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = listOfNotNull(
                "置顶".takeIf { pinned },
                if (c.isChannel) "频道" else "群",
                group,
                stats?.let { if (it.videos > 0) "${countText(it.videos)} 个视频" else if (it.media > 0) "没有视频" else "没有媒体" },
                c.members.takeIf { it > 0 }?.let { "${countText(it)} ${if (c.isChannel) "订阅" else "成员"}" },
                c.username?.let { "@$it" },
                "有新视频".takeIf { fresh },
            ).joinToString(" · ")
            Text(
                info, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (fresh) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (favorite) AppIcons.Star else AppIcons.StarBorder, if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onPin != null || onMove != null) Box {
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) { Icon(AppIcons.MoreVert, "更多") }
            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onPin?.let { DropdownMenuItem(text = { Text(if (pinned) "取消置顶" else "置顶") }, onClick = { menu = false; it() }) }
                DropdownMenuItem(text = { Text(if (favorite) "取消收藏" else "收藏") }, onClick = { menu = false; onFavorite() })
                onMove?.let { DropdownMenuItem(text = { Text(if (group != null) "换分组…" else "移到分组…") }, onClick = { menu = false; it() }) }
            }
        }
    }
}

/** 频道头像：先显示模糊的小图，下好了换清楚的；没有头像显示名字的第一个字（emoji 也完整显示） */
@Composable
private fun ChatAvatar(c: TgChat) {
    val mini = remember(c.id) { c.photoMini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val photo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, c.id) {
        val file = c.photo ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { Telegram.thumbnailPath(file)?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val img = photo ?: mini
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(firstGrapheme(c.title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** 第一个「字」：emoji、带变体的字符不能只截一个 char（会变成问号） */
internal fun firstGrapheme(s: String): String {
    val t = s.trim()
    if (t.isEmpty()) return "?"
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(t)
    val end = it.next()
    return t.substring(0, if (end == java.text.BreakIterator.DONE) t.length else end)
}

/** 12345 → 1.2万 */
private fun countText(n: Int): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000f)
    n >= 10_000 -> "%.1f万".format(n / 10_000f)
    else -> n.toString()
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

/**
 * 一个频道 / 群里的视频，从新到旧；滑到底自动加载更多。顶上可以搜说明里的字，或者点 #标签 只看一部剧；
 * 看过的显示进度。点一个视频：有剧名标签的按这部剧的集数顺序（从旧到新）连着放
 */
@Composable
fun TelegramChatScreen(
    chatId: Long,
    title: String,
    lib: com.aisub.player.LibraryViewModel,
    onBack: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
) {
    // 去播放再回来时接着显示（筛选、已经加载的、滚到哪），不用重新加载
    val page = remember(chatId) { chatPages.getOrPut(chatId) { ChatPage(chatId) }.also { trimChatPages(chatId) } }
    var query by page::query
    var applied by page::applied    // 停止输入一会儿再搜（点标签马上搜）
    LaunchedEffect(query) {
        if (query.trim() != applied) {
            kotlinx.coroutines.delay(if (query.startsWith("#")) 0 else 600)
            applied = query.trim()
        }
    }
    var mode by page::mode
    val results = remember(applied, mode) { page.resultsFor(applied, mode) }
    val pager = results.pager
    val videos = results.videos
    var done by results::done
    var loading by results::loading
    var error by results::error
    val tagCounts = page.tagCounts
    // 按时长、大小筛选：在已经加载的视频里挑，符合的太少就接着往下加载
    var durationFilter by page::durationFilter
    var sizeFilter by page::sizeFilter
    var customMinSec by page::customMinSec
    var customDialog by remember { mutableStateOf(false) }
    val custom = durationFilter == DURATION_FILTERS.size
    val filtering = durationFilter > 0 || sizeFilter > 0
    // 筛选只看视频：图片、文字、文件不显示
    val shown = if (!filtering) videos else videos.filter {
        val durationOk = if (custom) it.durationSec >= customMinSec else DURATION_FILTERS[durationFilter].second(it.durationSec)
        it.isVideo && durationOk && SIZE_FILTERS[sizeFilter].second(it.size)
    }
    var starting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val libState by lib.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { lib.reloadStored() }
    val progress = remember(libState.history) {
        libState.history.filter { Telegram.isTelegram(it.entry.uri) }.associate { it.entry.uri to (it.positionMs to it.entry.durationMs) }
    }

    fun loadMore() {
        if (loading || done) return
        loading = true
        error = null
        scope.launch {
            try {
                val list = pager.next()
                videos.addAll(list.filter { v -> videos.none { it.messageId == v.messageId } })
                done = pager.done
                if (applied.isEmpty()) {
                    list.filter { it.isVideo }.forEach { v -> v.tags.forEach { t -> tagCounts[t] = (tagCounts[t] ?: 0) + 1 } }
                    // 点进来看过了：收藏列表上的「有新视频」消掉
                    list.maxOfOrNull { it.messageId }?.let { Telegram.setSeenVideoId(chatId, it) }
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(pager) { if (videos.isEmpty()) loadMore() }
    LaunchedEffect(filtering, shown.size, loading, done) {
        if (filtering && shown.size < 20 && !loading && !done && error == null) loadMore()
    }

    fun entryOf(v: TgVideo) = v.entry()
    var saved by remember { mutableStateOf(Telegram.savedKeys()) }
    fun toggleSaved(v: TgVideo) {
        val on = "${v.chatId}/${v.messageId}" !in saved
        Telegram.setSaved(v, title, on)
        saved = Telegram.savedKeys()
        android.widget.Toast.makeText(context, if (on) "已加入收藏夹" else "已取消收藏", android.widget.Toast.LENGTH_SHORT).show()
    }

    var reading by remember { mutableStateOf<TgVideo?>(null) }
    val openDetail = LocalOpenItemDetail.current

    var viewing by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    /** 图片：下载原图后全屏看；文件：下载后交给别的 App 打开 */
    fun openFile(v: TgVideo) {
        val file = v.file ?: return
        if (starting) return
        starting = true
        scope.launch {
            try {
                val path = Telegram.downloadPath(file) ?: throw IllegalStateException("下载失败")
                if (v.kind == com.aisub.player.telegram.TgKind.PHOTO) {
                    viewing = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                } else {
                    openWithOtherApp(context, java.io.File(path), v.fileName)
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "打开失败：${e.message ?: e}", android.widget.Toast.LENGTH_LONG).show()
            } finally {
                starting = false
            }
        }
    }

    fun start(v: TgVideo) {
        if (v.isText) { reading = v; return }
        if (!v.isVideo) return openFile(v)
        if (starting) return
        starting = true
        saveHistoryThumb(context, scope, v)
        scope.launch {
            try {
                // 有剧名标签：这部剧的各集按顺序（从旧到新），「下一集」就是真的下一集
                val series = v.seriesTag?.let { tag ->
                    runCatching { Telegram.seriesVideos(chatId, tag) }.getOrNull()
                        ?.takeIf { s -> s.size > 1 && s.any { it.messageId == v.messageId } }
                        ?.let { com.aisub.player.telegram.TgEpisodes.playOrder(v, it) }
                }
                val list = series ?: shown.filter { it.isVideo }.sortedBy { it.messageId }
                play(list.map(::entryOf), list.indexOfFirst { it.messageId == v.messageId }.coerceAtLeast(0))
            } finally {
                starting = false
            }
        }
    }

    DetailScaffold(title, onBack) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                placeholder = { Text(if (mode == com.aisub.player.telegram.TgMode.ALL) "搜索这里的消息" else "搜索这里的视频") },
                leadingIcon = { Icon(AppIcons.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(AppIcons.Close, "清除") } },
                colors = appFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            val tags = tagCounts.entries.filter { it.value >= 2 || tagCounts.size <= 12 }
                .sortedByDescending { it.value }.take(30).map { it.key }
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { ModeChip(mode) { mode = it; Telegram.mode = it } }
                item {
                    // 最后一项是自定义：选了已经设过的直接用，点「自定义…」再填
                    val options = DURATION_FILTERS.map { it.first } + (if (custom) customDurationLabel(customMinSec) else "自定义…")
                    FilterMenuChip("时长", options, durationFilter) { i ->
                        if (i == DURATION_FILTERS.size) customDialog = true else durationFilter = i
                    }
                }
                item { FilterMenuChip("大小", SIZE_FILTERS.map { it.first }, sizeFilter) { sizeFilter = it } }
                items(tags.size) { i ->
                    val t = tags[i]
                    androidx.compose.material3.FilterChip(
                        selected = applied == t,
                        onClick = { query = if (applied == t) "" else t },
                        label = { Text(t.removePrefix("#")) },
                    )
                }
            }
            if (starting) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
                when {
                    shown.isEmpty() && (loading || !done && error == null) ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    shown.isEmpty() && error != null -> EmptyState("读取失败：$error", buttonText = "重试", icon = AppIcons.Live) { loadMore() }
                    shown.isEmpty() && videos.isNotEmpty() -> EmptyState("没有符合筛选条件的视频", icon = AppIcons.Live)
                    shown.isEmpty() -> EmptyState(
                        when {
                            applied.isNotEmpty() -> "没有找到「$applied」"
                            mode == com.aisub.player.telegram.TgMode.VIDEO -> "这里没有视频，可以在「仅视频」里换成别的消息类型"
                            else -> "这里没有${mode.label}"
                        },
                        icon = AppIcons.Live,
                    )
                    // 「全部消息」像官方客户端那样按聊天气泡显示；长按视频收藏
                    mode == com.aisub.player.telegram.TgMode.ALL && !filtering -> TgChatMessages(
                        shown, results.listState, loading, error, ::loadMore, progress, saved,
                        onClick = ::start,
                        onLongClick = { v -> if (v.isVideo) toggleSaved(v) else start(v) },
                        onTag = { query = it },
                    )
                    else -> LazyColumn(state = results.listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(shown, key = { _, v -> v.messageId }) { i, v ->
                            if (i >= shown.size - 3) LaunchedEffect(shown.size) { loadMore() }
                            if (v.isText) TextRow(v) { start(v) } else VideoRow(
                                v, progress[v.uri],
                                onInfo = if (!v.isVideo) null else ({
                                    val list = shown.filter { it.isVideo }.sortedBy { it.messageId }
                                    openDetail(v.detailItem(), list.map(::entryOf), list.indexOfFirst { it.messageId == v.messageId }.coerceAtLeast(0))
                                }),
                                saved = if (v.isVideo) "${v.chatId}/${v.messageId}" in saved else null,
                                onSave = { toggleSaved(v) },
                            ) { start(v) }
                        }
                        if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                        error?.let { e -> item { TextButton(onClick = ::loadMore, modifier = Modifier.padding(16.dp)) { Text("加载失败（$e），点这里重试") } } }
                    }
                }
            }
        }
    }
    if (customDialog) CustomDurationDialog(customMinSec, onDismiss = { customDialog = false }) { sec ->
        customMinSec = sec
        lastCustomMinSec = sec
        durationFilter = DURATION_FILTERS.size
        customDialog = false
    }
    reading?.let { v ->
        AppDialog(
            onDismissRequest = { reading = null },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(v.caption, Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { reading = null }) { Text("关闭") } },
        )
    }
    viewing?.let { img ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            androidx.activity.compose.BackHandler { viewing = null }
            PopupTheme {
                Box(
                    Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.88f))
                        .dismissOnOutsideClick { viewing = null },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(36.dp))
                    PopupCloseButton(
                        onClick = { viewing = null },
                        modifier = Modifier.align(Alignment.TopEnd).padding(20.dp),
                    )
                }
            }
        }
    }
}

/** 列出哪些消息：全部 / 仅视频 / 视频+图片 / 文件 */
@Composable
private fun ModeChip(mode: com.aisub.player.telegram.TgMode, onSelect: (com.aisub.player.telegram.TgMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.FilterChip(
            selected = mode != com.aisub.player.telegram.TgMode.VIDEO,
            onClick = { open = true },
            label = { Text(mode.label) },
            trailingIcon = { Icon(AppIcons.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            com.aisub.player.telegram.TgMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = { open = false; onSelect(m) },
                    trailingIcon = if (m == mode) ({ Icon(AppIcons.Check, null) }) else null,
                )
            }
        }
    }
}

/** Telegram 下载的文件复制一份到可以分享的目录，交给别的 App 打开 */
private suspend fun openWithOtherApp(context: android.content.Context, file: java.io.File, name: String) {
    val copy = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "tgfiles").apply { deleteRecursively(); mkdirs() }
        java.io.File(dir, name.ifBlank { file.name }.replace('/', '_')).also { file.copyTo(it, overwrite = true) }
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.update", copy)
    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(copy.extension.lowercase()) ?: "*/*"
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(android.content.Intent.createChooser(intent, "打开 ${copy.name}"))
    } catch (e: android.content.ActivityNotFoundException) {
        throw IllegalStateException("没有能打开这种文件的 App")
    }
}

/** 文字消息：前几行正文和发送时间，点开看全文 */
@Composable
private fun TextRow(v: TgVideo, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(v.caption, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Text(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(v.date * 1000L)),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** 一个频道页面的状态，离开页面（去播放）也留着 */
private class ChatPage(val chatId: Long) {
    var query by mutableStateOf("")
    var applied by mutableStateOf("")
    /** 标签：从没筛选时看到的视频里收集，常见的排前面 */
    val tagCounts = androidx.compose.runtime.mutableStateMapOf<String, Int>()
    /** [DURATION_FILTERS] / [SIZE_FILTERS] 里选的第几项，0 是不限 */
    var durationFilter by mutableStateOf(0)
    var sizeFilter by mutableStateOf(0)
    /** 自定义的「X 分钟 / 秒以上」（秒）；[durationFilter] 是 [DURATION_FILTERS].size 时用它 */
    var customMinSec by mutableStateOf(lastCustomMinSec)
    var mode by mutableStateOf(Telegram.mode)
    private var results: Results? = null

    /** 一次搜索（或不筛选）的结果 */
    class Results(chatId: Long, val query: String, val mode: com.aisub.player.telegram.TgMode) {
        val pager = Telegram.VideoPager(chatId, query, mode)
        val videos = mutableStateListOf<TgVideo>()
        var done by mutableStateOf(false)
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>(null)
        val listState = androidx.compose.foundation.lazy.LazyListState()
    }

    fun resultsFor(query: String, mode: com.aisub.player.telegram.TgMode): Results =
        results?.takeIf { it.query == query && it.mode == mode } ?: Results(chatId, query, mode).also { results = it }
}

private const val MB = 1024L * 1024

private val DURATION_FILTERS: List<Pair<String, (Int) -> Boolean>> = listOf(
    "不限" to { _ -> true },
    "5 分钟以内" to { s -> s in 1 until 300 },
    "5–20 分钟" to { s -> s in 300 until 1200 },
    "20–60 分钟" to { s -> s in 1200 until 3600 },
    "1 小时以上" to { s -> s >= 3600 },
)

/** 上次自定义的时长（秒），换个频道接着用 */
private var lastCustomMinSec = 0

/** 600 → 「10 分钟以上」，90 → 「90 秒以上」 */
private fun customDurationLabel(sec: Int) =
    if (sec >= 60 && sec % 60 == 0) "${sec / 60} 分钟以上" else "$sec 秒以上"

/** 自定义时长：填数字，选分钟或秒 */
@Composable
private fun CustomDurationDialog(initialSec: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var minutes by remember { mutableStateOf(initialSec == 0 || initialSec % 60 == 0) }
    var text by remember { mutableStateOf(if (initialSec == 0) "" else (if (minutes) initialSec / 60 else initialSec).toString()) }
    val n = text.toIntOrNull()?.takeIf { it > 0 }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义时长") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只看时长不少于这个的视频")
                OutlinedTextField(
                    text, { v -> text = v.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text(if (minutes) "分钟" else "秒") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = appFieldColors(), modifier = Modifier.fillMaxWidth(), phoneLabel = "时长（数字）",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(selected = minutes, onClick = { minutes = true }, label = { Text("分钟") })
                    androidx.compose.material3.FilterChip(selected = !minutes, onClick = { minutes = false }, label = { Text("秒") })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { n?.let { onConfirm(if (minutes) it * 60 else it) } }, enabled = n != null) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val SIZE_FILTERS: List<Pair<String, (Long) -> Boolean>> = listOf(
    "不限" to { _ -> true },
    "100 MB 以内" to { b -> b in 1 until 100 * MB },
    "100–500 MB" to { b -> b in 100 * MB until 500 * MB },
    "500 MB–1 GB" to { b -> b in 500 * MB until 1024 * MB },
    "1 GB 以上" to { b -> b >= 1024 * MB },
)

/** 点开选一项的筛选按钮；选了「不限」以外的显示选中的那项 */
@Composable
private fun FilterMenuChip(name: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.FilterChip(
            selected = selected > 0,
            onClick = { open = true },
            label = { Text(if (selected > 0) options[selected] else name) },
            trailingIcon = { Icon(AppIcons.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        AppMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    text = { Text(if (i == 0) "不限$name" else o) },
                    onClick = { open = false; onSelect(i) },
                    trailingIcon = if (i == selected) ({ Icon(AppIcons.Check, null) }) else null,
                )
            }
        }
    }
}

/** 最近打开的几个频道页面 */
private val chatPages = LinkedHashMap<Long, ChatPage>()

private fun trimChatPages(keep: Long) {
    while (chatPages.size > 4) chatPages.keys.firstOrNull { it != keep }?.let { chatPages.remove(it) } ?: break
}

/** 播放列表里的一项：认出了剧名和集数用「魔女守护者 第07集」（一眼分得清），标题一样的靠文件名分开 */
private fun TgVideo.entry(): MediaEntry {
    val name = fileName.substringBeforeLast('.')
    val ep = com.aisub.player.telegram.TgEpisodes.episodeOf(fileName)
    val t = when {
        seriesTag != null && ep != null -> "${seriesTag!!.removePrefix("#")} 第${"%02d".format(ep)}集"
        name.isNotBlank() && name !in title -> "$title · $name"
        else -> title
    }
    return MediaEntry(uri = uri, title = t, durationMs = durationSec * 1000L, sizeBytes = size)
}

/** 「继续观看」的封面：播放器截不了 Telegram 的视频，用消息自带的缩略图 */
private fun saveHistoryThumb(context: android.content.Context, scope: kotlinx.coroutines.CoroutineScope, v: TgVideo) {
    val thumb = v.thumb ?: return
    if (HistoryThumbs.file(context, v.uri).exists()) return
    scope.launch(Dispatchers.IO) {
        runCatching { Telegram.thumbnailPath(thumb)?.let(BitmapFactory::decodeFile)?.let { HistoryThumbs.save(context, v.uri, it) } }
    }
}

/**
 * Telegram 视频收藏夹：在频道里点视频右边的「♡」收藏，这里按收藏先后列出（可按频道筛选）。
 * 每次打开重新取消息；频道里删掉了的显示「已失效」，可以移除。点一个从它开始连着放列表里的视频。
 */
@Composable
fun TelegramSavedScreen(lib: com.aisub.player.LibraryViewModel, onBack: () -> Unit, play: (List<MediaEntry>, Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refs by remember { mutableStateOf(Telegram.savedVideos()) }
    var loaded by remember { mutableStateOf<Map<String, TgVideo?>?>(null) }
    LaunchedEffect(Unit) {
        Telegram.init(context)
        Telegram.start()
        if (!Telegram.awaitReady()) { loaded = emptyMap(); return@LaunchedEffect }
        loaded = Telegram.loadSaved(refs).associate { (s, v) -> s.key to v }
    }
    val libState by lib.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { lib.reloadStored() }
    val progress = remember(libState.history) {
        libState.history.filter { Telegram.isTelegram(it.entry.uri) }.associate { it.entry.uri to (it.positionMs to it.entry.durationMs) }
    }
    var channel by remember { mutableStateOf<Long?>(null) }
    val channels = refs.distinctBy { it.chatId }
    val shown = refs.filter { channel == null || it.chatId == channel }
    val openDetail = LocalOpenItemDetail.current

    fun remove(s: Telegram.SavedVideo) {
        val v = loaded?.get(s.key) ?: TgVideo(s.chatId, s.messageId, s.title, "", "", emptyList(), 0, 0, 0, null, null)
        Telegram.setSaved(v, s.chatTitle, false)
        refs = Telegram.savedVideos()
        if (channel != null && refs.none { it.chatId == channel }) channel = null
    }
    fun playable() = shown.mapNotNull { loaded?.get(it.key) }

    DetailScaffold("收藏夹", onBack) {
        Column(Modifier.fillMaxSize()) {
            if (channels.size > 1) androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { androidx.compose.material3.FilterChip(selected = channel == null, onClick = { channel = null }, label = { Text("全部 ${refs.size}") }) }
                items(channels.size) { i ->
                    val c = channels[i]
                    androidx.compose.material3.FilterChip(
                        selected = channel == c.chatId, onClick = { channel = c.chatId },
                        label = { Text("${c.chatTitle.ifBlank { "频道" }} ${refs.count { it.chatId == c.chatId }}", maxLines = 1) },
                    )
                }
            }
            when {
                refs.isEmpty() -> EmptyState("还没有收藏视频\n在频道里点视频右边的「♡」收藏", icon = AppIcons.Favorite)
                loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(shown, key = { _, s -> s.key }) { _, s ->
                        val v = loaded!![s.key]
                        if (v == null) Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title.ifBlank { "视频" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${s.chatTitle} · 已失效（消息被删除或没有权限）",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                )
                            }
                            TextButton(onClick = { remove(s) }) { Text("移除") }
                        } else VideoRow(
                            v, progress[v.uri],
                            onInfo = {
                                val list = playable()
                                openDetail(v.detailItem(), list.map { it.entry() }, list.indexOfFirst { it.uri == v.uri }.coerceAtLeast(0))
                            },
                            saved = true, onSave = { remove(s) }, source = s.chatTitle.takeIf { channel == null },
                        ) {
                            saveHistoryThumb(context, scope, v)
                            val list = playable()
                            play(list.map { it.entry() }, list.indexOfFirst { it.uri == v.uri }.coerceAtLeast(0))
                        }
                    }
                }
            }
        }
    }
}

/**
 * [watched]：看到的位置和总时长（毫秒），没看过是 null；[saved] 不是 null 时显示收藏按钮（♡）；
 * [source]：收藏夹里显示是哪个频道的
 */
@Composable
private fun VideoRow(
    v: TgVideo,
    watched: Pair<Long, Long>?,
    onInfo: (() -> Unit)?,
    saved: Boolean? = null,
    onSave: () -> Unit = {},
    source: String? = null,
    onClick: () -> Unit,
) {
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
                ?: Icon(
                    when (v.kind) { com.aisub.player.telegram.TgKind.PHOTO -> AppIcons.Image; com.aisub.player.telegram.TgKind.FILE -> AppIcons.File; else -> AppIcons.Movie },
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center),
                )
            if (v.durationSec > 0) Text(
                formatDuration(v.durationSec * 1000L), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(androidx.compose.ui.graphics.Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
            // 看过的：底下一条进度，看到片尾（最后 30 秒）的标「看完」
            val duration = watched?.second?.takeIf { it > 0 } ?: (v.durationSec * 1000L)
            if (watched != null && duration > 0 && watched.first > 0) {
                val finished = watched.first >= duration - 30_000
                if (finished) Text(
                    "看完", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                        .background(androidx.compose.ui.graphics.Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                )
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(androidx.compose.ui.graphics.Color(0x66000000))) {
                    Box(
                        Modifier.fillMaxWidth(if (finished) 1f else (watched.first.toFloat() / duration).coerceIn(0f, 1f)).height(3.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // 文件名一般带集数、画质：标题一样的几条靠它分开
            val name = v.fileName.substringBeforeLast('.')
            if (name.isNotBlank() && name !in v.title) Text(name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(v.date * 1000L))
            Text(
                listOfNotNull(source?.takeIf { it.isNotBlank() }, date, v.size.takeIf { it > 0 }?.let(::formatSize)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (saved != null) IconButton(onClick = onSave) {
            Icon(
                if (saved) AppIcons.Favorite else AppIcons.FavoriteBorder, if (saved) "取消收藏" else "收藏",
                tint = if (saved) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
            )
        }
        if (onInfo != null) IconButton(onClick = onInfo) { Icon(AppIcons.Info, "影视信息") }
    }
}

/**
 * 详情页（影视识别、下载字幕）用的条目。片名从哪认：文件名像片名（The.Matrix.1999.1080p.mkv）就用文件名，
 * 是 video_2024-01-01.mp4、纯编号这种就用说明的第一行。
 */
private fun TgVideo.detailItem(): com.aisub.player.remote.RemoteItem {
    val base = fileName.substringBeforeLast('.')
    val parsed = com.aisub.player.meta.NameParser.parse(fileName).title
    val generic = base.isBlank() || Regex("""(?i)^(video|vid|img|mov)[\s_-]?\d.*""").matches(base) ||
        parsed.count { it.isLetter() } < 2
    // 详情页按扩展名认视频：没有视频扩展名的补上
    val name = (if (generic) title else fileName).let { if (MediaEntry.isVideoName(it)) it else "$it.mp4" }
    return com.aisub.player.remote.RemoteItem(name, uri, isDir = false, size = size, playUrl = uri)
}

private enum class TgGlobalSearchMode(val label: String) {
    ALL("全部消息"),
    VIDEO("仅视频"),
    PUBLIC("公开频道"),
}

/**
 * 跨频道/群组 Telegram 全局搜索页面：支持从影视详情页跳转直达，搜已加入频道的消息与视频、
 * 搜公开频道/群，并自动提取消息中的磁力链接与网盘分享链接。
 */
@Composable
fun TelegramGlobalSearchScreen(
    initialQuery: String,
    lib: com.aisub.player.LibraryViewModel,
    onBack: () -> Unit,
    play: (List<MediaEntry>, Int) -> Unit,
    openChat: (Long, String) -> Unit,
    openTelegram: () -> Unit,
    openWeb: (String) -> Unit,
    openMagnet: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val auth by Telegram.auth.collectAsStateWithLifecycle()
    val libState by lib.state.collectAsStateWithLifecycle()
    val openDetail = LocalOpenItemDetail.current

    var queryInput by remember(initialQuery) { mutableStateOf(initialQuery) }
    var appliedQuery by remember(initialQuery) { mutableStateOf(initialQuery.trim()) }
    var mode by remember { mutableStateOf(TgGlobalSearchMode.ALL) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<com.aisub.player.telegram.TgSearchHit>>(emptyList()) }
    var publicChats by remember { mutableStateOf<List<TgChat>>(emptyList()) }
    var nextOffset by remember { mutableStateOf("") }
    var totalCount by remember { mutableStateOf(0) }
    var inspectedHit by remember { mutableStateOf<com.aisub.player.telegram.TgSearchHit?>(null) }
    var statusTip by remember { mutableStateOf<String?>(null) }

    val customTgSources = remember {
        com.aisub.player.meta.DiscoverSearchStore.sources(context)
            .filter { it.enabled && it.category == com.aisub.player.meta.SearchCategory.TELEGRAM }
    }

    LaunchedEffect(Unit) {
        Telegram.init(context)
        Telegram.start()
        lib.reloadStored()
    }

    val progress = remember(libState.history) {
        libState.history.filter { Telegram.isTelegram(it.entry.uri) }
            .associate { it.entry.uri to (it.positionMs to it.entry.durationMs) }
    }

    fun runSearch(reset: Boolean) {
        val q = appliedQuery.trim()
        if (q.isEmpty() || auth !is TgAuth.Ready) return
        scope.launch {
            if (reset) {
                loading = true
                error = null
                hits = emptyList()
                publicChats = emptyList()
                nextOffset = ""
                totalCount = 0
            } else {
                if (loadingMore || nextOffset.isEmpty()) return@launch
                loadingMore = true
            }
            runCatching {
                if (mode == TgGlobalSearchMode.PUBLIC) {
                    Telegram.searchPublic(q)
                } else {
                    Telegram.searchGlobal(
                        query = q,
                        offset = if (reset) "" else nextOffset,
                        limit = 40,
                        videosOnly = (mode == TgGlobalSearchMode.VIDEO),
                    )
                }
            }.onSuccess { result ->
                if (mode == TgGlobalSearchMode.PUBLIC) {
                    @Suppress("UNCHECKED_CAST")
                    publicChats = result as List<TgChat>
                } else {
                    val page = result as com.aisub.player.telegram.TgSearchPage
                    hits = if (reset) page.hits else (hits + page.hits).distinctBy { it.message.chatId to it.message.messageId }
                    nextOffset = page.nextOffset
                    totalCount = page.totalCount
                }
            }.onFailure { e ->
                error = e.message ?: "搜索失败"
            }
            loading = false
            loadingMore = false
        }
    }

    LaunchedEffect(appliedQuery, mode, auth) {
        if (auth is TgAuth.Ready && appliedQuery.isNotBlank()) {
            runSearch(reset = true)
        }
    }

    fun playableEntries(): List<MediaEntry> = hits.map { it.message }.filter { it.isVideo }.map { it.entry() }

    DetailScaffold("Telegram 全局搜索", onBack) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = queryInput,
                    onValueChange = { queryInput = it },
                    singleLine = true,
                    placeholder = { Text("输入片名、#标签 或 @频道名") },
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    trailingIcon = {
                        if (queryInput.isNotEmpty()) {
                            IconButton(onClick = { queryInput = ""; appliedQuery = "" }) {
                                Icon(AppIcons.Close, "清空")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { appliedQuery = queryInput.trim() },
                    ),
                    colors = appFieldColors(),
                    phoneLabel = "Telegram 搜索",
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { appliedQuery = queryInput.trim() },
                    enabled = queryInput.isNotBlank(),
                ) {
                    Text("搜索")
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TgGlobalSearchMode.entries.forEach { m ->
                    androidx.compose.material3.FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (mode != TgGlobalSearchMode.PUBLIC && totalCount > 0) {
                    Text(
                        "约 $totalCount 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            statusTip?.let { tip ->
                Text(
                    tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                auth !is TgAuth.Ready -> {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SurfaceCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val unavailable = (auth as? TgAuth.Unsupported)?.reason
                                Text(
                                    if (unavailable != null) "当前端暂无法使用内置 Telegram：$unavailable"
                                    else "尚未登录内置 Telegram 账号",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "登录 Telegram 后可在所有已加入的频道和群组里直接搜索片源并边下边播；也可以先复制关键词去 Telegram 客户端或自定义搜索站查找。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = openTelegram) { Text("前往 Telegram 登录") }
                                    TextButton(onClick = {
                                        val q = queryInput.trim()
                                        if (q.isNotEmpty()) {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(q))
                                            statusTip = "已复制关键词「$q」，可前往 Telegram 粘贴搜索"
                                        }
                                        runCatching {
                                            val intent = android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("tg://"),
                                            )
                                            context.startActivity(intent)
                                        }.onFailure {
                                            openWeb("https://web.telegram.org/")
                                        }
                                    }) {
                                        Text("复制关键词并唤起 TG")
                                    }
                                }
                            }
                        }
                        if (customTgSources.isNotEmpty() && queryInput.isNotBlank()) {
                            Text("自定义 Telegram 搜索源", style = MaterialTheme.typography.titleSmall)
                            customTgSources.forEach { src ->
                                Button(onClick = { openWeb(src.buildUrl(queryInput)) }) {
                                    Text(src.name)
                                }
                            }
                        }
                    }
                }
                appliedQuery.isBlank() -> {
                    EmptyState("输入关键词后点「搜索」，将在全部已加入的频道、群和对话中检索", icon = AppIcons.Search)
                }
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    EmptyState("搜索失败：${error}\n点此重试", icon = AppIcons.Telegram)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { runSearch(reset = true) }) { Text("重新搜索") }
                    }
                }
                mode == TgGlobalSearchMode.PUBLIC -> {
                    if (publicChats.isEmpty()) {
                        EmptyState("没有找到与「$appliedQuery」相关的公开频道或群组", icon = AppIcons.Telegram)
                    } else {
                        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                            itemsIndexed(publicChats, key = { _, c -> c.id }) { _, c ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { openChat(c.id, c.title) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ChatAvatar(c)
                                    Column(Modifier.weight(1f)) {
                                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val sub = listOfNotNull(
                                            c.username?.let { "@$it" },
                                            c.members.takeIf { it > 0 }?.let { "${countText(it)} 成员" },
                                        ).joinToString(" · ")
                                        if (sub.isNotEmpty()) {
                                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    Icon(AppIcons.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                hits.isEmpty() -> {
                    EmptyState("已加入的频道与群里没有找到「$appliedQuery」\n可以切换到「公开频道」试试", icon = AppIcons.Telegram)
                }
                else -> {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(hits, key = { _, h -> "${h.message.chatId}:${h.message.messageId}" }) { index, hit ->
                            if (index >= hits.size - 4 && nextOffset.isNotEmpty() && !loadingMore) {
                                LaunchedEffect(hits.size, nextOffset) { runSearch(reset = false) }
                            }
                            val v = hit.message
                            val magnets = remember(v.caption) { com.aisub.player.meta.MagnetLinks.extractMagnets(v.caption) }
                            val cloudLinks = remember(v.caption) { com.aisub.player.meta.MagnetLinks.extractCloudShareUrls(v.caption) }
                            Column(Modifier.fillMaxWidth()) {
                                if (v.isVideo) {
                                    VideoRow(
                                        v = v,
                                        watched = progress[v.uri],
                                        onInfo = {
                                            val entries = playableEntries()
                                            val idx = entries.indexOfFirst { it.uri == v.uri }.coerceAtLeast(0)
                                            openDetail(v.detailItem(), entries, idx)
                                        },
                                        saved = null,
                                        source = hit.chat.title,
                                    ) {
                                        saveHistoryThumb(context, scope, v)
                                        val entries = playableEntries()
                                        val idx = entries.indexOfFirst { it.uri == v.uri }.coerceAtLeast(0)
                                        play(entries, idx)
                                    }
                                } else {
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .clickable { inspectedHit = hit }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                            .format(java.util.Date(v.date * 1000L))
                                        Text(
                                            "${hit.chat.title} · $dateStr",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            v.caption.ifBlank { v.title },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 4,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (magnets.isNotEmpty() || cloudLinks.isNotEmpty() || !v.isVideo) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        TextButton(onClick = { openChat(hit.chat.id, hit.chat.title) }) {
                                            Text("进入频道")
                                        }
                                        if (magnets.isNotEmpty()) {
                                            TextButton(onClick = { openMagnet(magnets.first()) }) {
                                                Text("磁力 (${magnets.size})")
                                            }
                                        }
                                        if (cloudLinks.isNotEmpty()) {
                                            TextButton(onClick = { openWeb(cloudLinks.first()) }) {
                                                Text("网盘链接 (${cloudLinks.size})")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (loadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    inspectedHit?.let { hit ->
        val msgText = hit.message.caption.ifBlank { hit.message.title }
        val magnets = remember(msgText) { com.aisub.player.meta.MagnetLinks.extractMagnets(msgText) }
        val shares = remember(msgText) { com.aisub.player.meta.MagnetLinks.extractCloudShareUrls(msgText) }
        AppDialog(
            onDismissRequest = { inspectedHit = null },
            title = { Text(hit.chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(msgText, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (magnets.isNotEmpty()) {
                        Text("检测到磁力链接", style = MaterialTheme.typography.titleSmall)
                        magnets.forEach { m ->
                            TextButton(onClick = { inspectedHit = null; openMagnet(m) }) {
                                Text(com.aisub.player.meta.MagnetLinks.displayName(m), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (shares.isNotEmpty()) {
                        Text("检测到网盘链接", style = MaterialTheme.typography.titleSmall)
                        shares.forEach { u ->
                            TextButton(onClick = { inspectedHit = null; openWeb(u) }) {
                                Text(u, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    inspectedHit = null
                    openChat(hit.chat.id, hit.chat.title)
                }) {
                    Text("进入频道")
                }
            },
            dismissButton = {
                TextButton(onClick = { inspectedHit = null }) { Text("关闭") }
            },
        )
    }
}
