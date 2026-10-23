package com.aisub.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    AlertDialog(
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
    AlertDialog(
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.baseUrl.isEmpty()) "添加网络位置" else "编辑网络位置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ServerType.WEBDAV, ServerType.OPENLIST).forEach { t ->
                        FilterChip(selected = s.type == t, onClick = { s = s.copy(type = t) }, label = { Text(t.label) })
                    }
                }
                OutlinedTextField(s.name, { s = s.copy(name = it) }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    s.baseUrl, { s = s.copy(baseUrl = it) },
                    label = { Text("地址") },
                    placeholder = {
                        Text(if (s.type == ServerType.OPENLIST) "http://192.168.1.2:5244" else "https://nas.local:5006/dav")
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    s.lanUrl, { s = s.copy(lanUrl = it) },
                    label = { Text("内网地址（可不填）") },
                    placeholder = {
                        Text(if (s.type == ServerType.OPENLIST) "http://192.168.1.2:5244" else "http://192.168.1.2:5005/dav")
                    },
                    supportingText = { Text("上面填外网域名时，在家能连上内网地址就自动走内网") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(s.username, { s = s.copy(username = it) }, label = { Text("用户名（可不填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    s.password, { s = s.copy(password = it) }, label = { Text("密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    s.rootPath, { s = s.copy(rootPath = it) }, label = { Text("起始目录") },
                    placeholder = { Text("/") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
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

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("译幕 Voxtitle ${BuildConfig.VERSION_NAME}（${com.aisub.player.download.Downloaders.EDITION}）")
                Text(
                    "本地 / 云端语音识别生成字幕，大模型翻译。\n\n" +
                        "开源组件：Media3 ExoPlayer、sherpa-onnx、SenseVoice、Silero VAD、OkHttp、Apache Commons Compress。\n\n" +
                        "本应用以 AGPL-3.0 许可证开源，源代码：\nhttps://github.com/clusflah/ai-subtitle-player",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好") } },
    )
}
