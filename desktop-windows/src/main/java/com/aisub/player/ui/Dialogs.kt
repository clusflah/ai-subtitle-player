package com.aisub.player.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aisub.player.BuildConfig
import com.aisub.player.media.Playlist
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "确定",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 默认全选原有内容，直接输入即可替换
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                field, { field = it }, label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPick: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(playlists.isEmpty()) }
    if (creating) {
        TextInputDialog("新建播放列表", "名称", onDismiss = onDismiss, confirm = "创建并加入", onConfirm = onCreate)
        return
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入播放列表") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                playlists.forEach { p ->
                    Text(
                        "${p.name}（${p.items.size}）",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text("新建…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 添加 / 编辑网络服务器。
 * @param hint 显示在顶部的说明（例如网盘需要先在 OpenList 里挂载）
 */
@Composable
fun ServerDialog(initial: Server, hint: String? = null, onDismiss: () -> Unit, onSave: (Server) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.baseUrl.isEmpty()) "添加网络位置" else "编辑网络位置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ServerType.WEBDAV, ServerType.OPENLIST).forEach { t ->
                        FilterChip(selected = s.type == t, onClick = { s = s.copy(type = t) }, leadingIcon = { ServerTypeIcon(t, size = 18.dp) }, label = { Text(t.label) })
                    }
                }
                OutlinedTextField(s.name, { s = s.copy(name = it) }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false)
                OutlinedTextField(
                    s.baseUrl, { s = s.copy(baseUrl = it) },
                    label = { Text("地址") },
                    placeholder = {
                        Text(if (s.type == ServerType.OPENLIST) "http://192.168.1.2:5244" else "https://nas.local:5006/dav")
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
                OutlinedTextField(
                    s.lanUrl, { s = s.copy(lanUrl = it) },
                    label = { Text("内网地址（可不填）") },
                    placeholder = {
                        Text(if (s.type == ServerType.OPENLIST) "http://192.168.1.2:5244" else "http://192.168.1.2:5005/dav")
                    },
                    supportingText = { Text("上面填外网域名时，在家能连上内网地址就自动走内网") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
                OutlinedTextField(s.username, { s = s.copy(username = it) }, label = { Text("用户名（可不填）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false)
                OutlinedTextField(
                    s.password, { s = s.copy(password = it) }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
                OutlinedTextField(
                    s.rootPath, { s = s.copy(rootPath = it) }, label = { Text("起始目录") },
                    placeholder = { Text("/") }, singleLine = true, modifier = Modifier.fillMaxWidth(), phoneInput = false,
                )
                // 电视：地址、密码用手机输入
                PhoneInputButton(
                    listOf(
                        PhoneField("name", "名称", s.name),
                        PhoneField("url", "地址", s.baseUrl),
                        PhoneField("lan", "内网地址（可不填）", s.lanUrl),
                        PhoneField("user", "用户名（可不填）", s.username),
                        PhoneField("pass", "密码", s.password, secret = true),
                        PhoneField("root", "起始目录", s.rootPath),
                    ),
                    title = s.type.label,
                ) { v ->
                    s = s.copy(
                        name = v["name"] ?: s.name, baseUrl = v["url"] ?: s.baseUrl, lanUrl = v["lan"] ?: s.lanUrl,
                        username = v["user"] ?: s.username, password = v["pass"] ?: s.password, rootPath = v["root"] ?: s.rootPath,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val root = s.rootPath.trim().ifEmpty { "/" }.let { if (it.startsWith('/')) it else "/$it" }
                    onSave(
                        s.copy(
                            name = s.name.trim().ifEmpty { s.baseUrl.trim() }, baseUrl = s.baseUrl.trim(),
                            lanUrl = s.lanUrl.trim(), rootPath = root,
                        ),
                    )
                },
                enabled = s.baseUrl.isHttpUrl() && (s.lanUrl.isBlank() || s.lanUrl.trim().isHttpUrl()),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun String.isHttpUrl() = startsWith("http://") || startsWith("https://")

private const val GITHUB_REPO_URL = "https://github.com/clusflah/ai-subtitle-player"

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    val openGitHub = {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL)))
            true
        }.getOrDefault(false) || runCatching {
            uriHandler.openUri(GITHUB_REPO_URL)
            true
        }.getOrDefault(false)
        if (!opened) {
            clipboard.setText(AnnotatedString(GITHUB_REPO_URL))
            copied = true
        }
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            val c = LocalPopupColors.current
            val linkShape = RoundedCornerShape(12.dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "译幕 Voxtitle ${BuildConfig.VERSION_NAME}（${com.aisub.player.download.Downloaders.EDITION}）",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "本地 / 云端语音识别生成字幕，大模型翻译。\n\n" +
                        "开源组件：Media3 ExoPlayer、sherpa-onnx、SenseVoice、Silero VAD、OkHttp、Apache Commons Compress。\n\n" +
                        "开源许可：AGPL-3.0",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusRing(linkShape)
                        .clip(linkShape)
                        .background(c.card)
                        .clickable(onClick = openGitHub)
                        .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "GitHub 项目地址",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            GITHUB_REPO_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(GITHUB_REPO_URL))
                            copied = true
                        },
                    ) {
                        Text(if (copied) "已复制" else "复制")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = openGitHub) { Text("打开 GitHub") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}
