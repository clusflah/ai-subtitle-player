package com.aisub.player.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.sync.MultiDeviceSync
import com.aisub.player.sync.SyncConfig
import kotlinx.coroutines.launch

/**
 * 设置页「多端同步」折叠卡片：
 * 支持 WebDAV 云端自动/手动双向合并同步 + 局域网零配置双向直连同步（Android 手机 / Android TV / Windows 桌面互通）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiDeviceSyncCard(
    existingServers: List<Server> = emptyList(),
    flush: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtimeState by MultiDeviceSync.state.collectAsStateWithLifecycle()

    var cfg by remember { mutableStateOf(MultiDeviceSync.loadConfig(context)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var lanHostOpen by remember { mutableStateOf(false) }
    var lanClientOpen by remember { mutableStateOf(false) }

    fun updateCfg(next: SyncConfig) {
        cfg = next
        MultiDeviceSync.saveConfig(context, next)
    }

    val webdavCandidates = remember(existingServers) {
        existingServers.filter { it.type == ServerType.WEBDAV }
    }

    val summaryText = buildString {
        if (cfg.hasWebDavConfigured) {
            append(if (cfg.autoSync) "WebDAV 自动同步已开启" else "已配置 WebDAV 云同步")
        } else {
            append("支持 WebDAV 云端同步与局域网双向直连同步")
        }
        val lastAt = maxOf(cfg.lastSyncAt, runtimeState.lastSyncAt)
        if (lastAt > 0L) {
            append(" · 上次同步 ")
            append(DateUtils.getRelativeTimeSpanString(lastAt))
        }
    }

    CollapsibleCard("多端同步", summaryText) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // 1. 局域网双向直连同步（无需配置服务器，手机/电视/电脑在同 Wi-Fi 下一次点击双向合并）
            ListRow(
                title = "局域网搜索设备双向同步",
                subtitle = "搜索同一 Wi-Fi 下开启同步的手机、电视或 Windows 电脑，一键合并双方最新观看进度与媒体库",
                icon = AppIcons.Refresh,
                onClick = { flush(); lanClientOpen = true },
            )
            HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                title = "开启本机等待局域网同步",
                subtitle = "在电视或电脑上打开此项，用另一台设备搜索或输入 IP:端口 即可完成双向同步",
                icon = AppIcons.Live,
                onClick = { flush(); lanHostOpen = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 2. WebDAV 云端多端自动同步
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("WebDAV 云端多端同步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "支持坚果云、群晖 NAS、OpenList/AList（/dav）、Nextcloud 等任意 WebDAV 服务；自动在启动与退出播放时双向合并最新进度与海报墙。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (webdavCandidates.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        webdavCandidates.forEach { srv ->
                            FilterChip(
                                selected = cfg.webdavUrl == srv.baseUrl && cfg.webdavUser == srv.username,
                                onClick = {
                                    updateCfg(
                                        cfg.copy(
                                            webdavUrl = srv.baseUrl,
                                            webdavUser = srv.username,
                                            webdavPass = srv.password,
                                        ),
                                    )
                                },
                                label = { Text("复用「${srv.name}」", fontSize = 12.sp) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = cfg.webdavUrl,
                    onValueChange = { updateCfg(cfg.copy(webdavUrl = it)) },
                    label = { Text("WebDAV 地址") },
                    placeholder = { Text("例如 https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    phoneInput = true,
                    phoneLabel = "WebDAV 地址",
                    colors = appFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cfg.webdavUser,
                        onValueChange = { updateCfg(cfg.copy(webdavUser = it)) },
                        label = { Text("用户名") },
                        singleLine = true,
                        phoneInput = true,
                        phoneLabel = "WebDAV 用户名",
                        colors = appFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cfg.webdavPass,
                        onValueChange = { updateCfg(cfg.copy(webdavPass = it)) },
                        label = { Text("密码 / 应用密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        phoneInput = true,
                        phoneLabel = "WebDAV 密码",
                        colors = appFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        enabled = !testing && cfg.webdavUrl.isNotBlank(),
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = MultiDeviceSync.testWebDavConnection(cfg.webdavUrl, cfg.webdavUser, cfg.webdavPass)
                                testing = false
                            }
                        },
                    ) {
                        if (testing) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("测试连接")
                    }

                    Button(
                        enabled = !runtimeState.syncing && cfg.hasWebDavConfigured,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        onClick = {
                            flush()
                            scope.launch {
                                runCatching { MultiDeviceSync.syncWithWebDav(context) }
                                cfg = MultiDeviceSync.loadConfig(context)
                            }
                        },
                    ) {
                        if (runtimeState.syncing) {
                            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (runtimeState.syncing) "同步中…" else "立即云同步")
                    }
                }

                testResult?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("连接成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                runtimeState.error?.let { err ->
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                runtimeState.statusMessage?.takeIf { runtimeState.error == null }?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ListRow(
                title = "自动后台多端同步",
                subtitle = "打开应用、保存播放进度或刮削完媒体库后自动与 WebDAV 云端合并同步",
                trailing = {
                    Switch(
                        checked = cfg.autoSync,
                        onCheckedChange = { updateCfg(cfg.copy(autoSync = it)) },
                    )
                },
                onClick = { updateCfg(cfg.copy(autoSync = !cfg.autoSync)) },
            )

            // 3. 同步内容范围勾选
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "同步数据范围",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            ListRow(
                title = "观看历史、播放进度与收藏播放列表",
                subtitle = "按最新观看时间戳跨端合并续播位置、稍后观看、视频与阅读记录",
                leading = { Checkbox(checked = cfg.syncHistoryAndProgress, onCheckedChange = null) },
                onClick = { updateCfg(cfg.copy(syncHistoryAndProgress = !cfg.syncHistoryAndProgress)) },
            )
            ListRow(
                title = "首页自定义媒体库与刮削海报墙",
                subtitle = "一端完成云盘文件夹刮削后，其他手机、电视和电脑自动共享完整海报墙与剧集列表",
                leading = { Checkbox(checked = cfg.syncMediaLibraries, onCheckedChange = null) },
                onClick = { updateCfg(cfg.copy(syncMediaLibraries = !cfg.syncMediaLibraries)) },
            )
            ListRow(
                title = "网盘登录、Emby/Plex 媒体服务器与直播源",
                subtitle = "同步已添加的云存储、NAS、媒体服务器及 M3U 直播源收藏",
                leading = { Checkbox(checked = cfg.syncServersAndLive, onCheckedChange = null) },
                onClick = { updateCfg(cfg.copy(syncServersAndLive = !cfg.syncServersAndLive)) },
            )
            ListRow(
                title = "应用偏好设置与 AI 模型密钥",
                subtitle = "同步语音识别、字幕翻译 API Key 与字幕样式（含密钥明文，仅在信任的私人 WebDAV 开启）",
                leading = { Checkbox(checked = cfg.syncSettingsAndKeys, onCheckedChange = null) },
                onClick = { updateCfg(cfg.copy(syncSettingsAndKeys = !cfg.syncSettingsAndKeys)) },
            )
        }
    }

    if (lanHostOpen) {
        LanSyncHostDialog(onDismiss = { lanHostOpen = false })
    }
    if (lanClientOpen) {
        LanSyncClientDialog(onDismiss = { lanClientOpen = false })
    }
}

/** 从「我的」页快速唤起的多端同步快捷面板 */
@Composable
fun MultiDeviceSyncQuickDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { MultiDeviceSync.loadConfig(context) }
    val runtimeState by MultiDeviceSync.state.collectAsStateWithLifecycle()

    var lanHostOpen by remember { mutableStateOf(false) }
    var lanClientOpen by remember { mutableStateOf(false) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("多端同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "在 Android 手机、Android 电视与 Windows 桌面端之间双向合并观看进度、自定义刮削媒体库、网盘/Emby/Plex 服务器与收藏：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (cfg.hasWebDavConfigured) {
                    Button(
                        enabled = !runtimeState.syncing,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                runCatching { MultiDeviceSync.syncWithWebDav(context) }
                            }
                        },
                    ) {
                        if (runtimeState.syncing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (runtimeState.syncing) "正在与 WebDAV 云端同步…" else "立即执行 WebDAV 云端同步")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(AppIcons.Cloud, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("去设置配置 WebDAV 自动云同步")
                    }
                }

                ListRow(
                    title = "搜索局域网设备双向同步",
                    subtitle = "与同 Wi-Fi 下开启等待同步的设备一键互相同步",
                    icon = AppIcons.Refresh,
                    onClick = { lanClientOpen = true },
                )
                ListRow(
                    title = "开启本机等待局域网同步",
                    subtitle = "让手机、电视或电脑连接本机完成双向合并",
                    icon = AppIcons.Live,
                    onClick = { lanHostOpen = true },
                )

                runtimeState.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                runtimeState.statusMessage?.takeIf { runtimeState.error == null }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); onOpenSettings() }) { Text("同步详细设置") }
        },
    )

    if (lanHostOpen) {
        LanSyncHostDialog(onDismiss = { lanHostOpen = false })
    }
    if (lanClientOpen) {
        LanSyncClientDialog(onDismiss = { lanClientOpen = false })
    }
}

@Composable
private fun LanSyncHostDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var peerStatus by remember { mutableStateOf("正在等待同一局域网设备发起同步…") }
    val host = remember {
        runCatching {
            MultiDeviceSync.LanSyncHost(context.applicationContext) { msg ->
                peerStatus = msg
            }
        }.getOrNull()
    }
    DisposableEffect(host) {
        onDispose { host?.close() }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("等待局域网双向同步") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val addr = host?.address
                if (addr == null) {
                    Text("未检测到局域网 IP，请确认设备已连接 Wi-Fi 或网线。", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "请在另一台设备（手机 / 电视 / Windows 电脑）打开「多端同步 → 局域网搜索设备双向同步」，点击「${host.serviceName}」或输入下方地址：",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SelectionContainer {
                        Text(
                            addr,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Orange,
                        )
                    }
                    Text(peerStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun LanSyncClientDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finder = remember { MultiDeviceSync.LanPeerFinder(context.applicationContext) }
    DisposableEffect(finder) {
        finder.start()
        onDispose { finder.stop() }
    }
    val peers by finder.peers.collectAsStateWithLifecycle()
    var manualAddr by remember { mutableStateOf("") }
    var busyPeer by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun triggerSync(address: String) {
        busyPeer = address
        feedback = null
        scope.launch {
            runCatching {
                MultiDeviceSync.syncWithLanPeer(context, address)
            }.onSuccess { msg ->
                feedback = msg
                isError = false
            }.onFailure { e ->
                feedback = "同步失败：${e.message ?: e.javaClass.simpleName}"
                isError = true
            }
            busyPeer = null
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("局域网双向同步") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (peers.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "正在搜索局域网内打开「等待局域网同步」的设备…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text("发现的局域网设备：", style = MaterialTheme.typography.labelLarge)
                    peers.forEach { p ->
                        ListRow(
                            title = p.name,
                            subtitle = p.address,
                            icon = AppIcons.Live,
                            trailing = {
                                if (busyPeer == p.address) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text("双向同步", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            onClick = { if (busyPeer == null) triggerSync(p.address) },
                        )
                    }
                }

                Text(
                    "或直接输入对方设备上显示的 IP:端口（支持跨网段/Windows 防火墙未放行广播时直连）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualAddr,
                        onValueChange = { manualAddr = it },
                        placeholder = { Text("192.168.1.8:51234") },
                        singleLine = true,
                        phoneInput = true,
                        phoneLabel = "对端 IP:端口",
                        colors = appFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = manualAddr.isNotBlank() && busyPeer == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        onClick = { triggerSync(manualAddr) },
                    ) {
                        Text("同步")
                    }
                }

                feedback?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
