package com.aisub.player.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aisub.player.remote.cloud.DropboxClient
import com.aisub.player.remote.cloud.EmbyClient
import com.aisub.player.remote.cloud.GoogleDriveClient
import com.aisub.player.remote.cloud.LoopbackReceiver
import com.aisub.player.remote.cloud.OneDriveClient
import com.aisub.player.remote.cloud.Pkce
import com.aisub.player.remote.cloud.PlexClient
import com.aisub.player.remote.cloud.QrSession
import com.aisub.player.remote.cloud.QrStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Emby / Plex / OneDrive / Dropbox / Google Drive 的登录界面，由 CloudLoginDialog 调用。

/** 说明文字（申请步骤等）。 */
@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

@Composable
private fun ErrorText(text: String?) {
    if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun openBrowser(context: Context, url: String) =
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** 在后台执行一个登录步骤，记录进行中状态和错误。 */
private class Runner {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun rememberRun(): Pair<Runner, (suspend () -> Unit) -> Unit> {
    val scope = rememberCoroutineScope()
    val r = remember { Runner() }
    return r to { block ->
        r.busy = true
        r.error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                r.error = e.message ?: e.toString()
            } finally {
                r.busy = false
            }
        }
    }
}

@Composable
internal fun EmbyLogin(initialUrl: String, onSuccess: (String, String?) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val (state, run) = rememberRun()
    Hint("Emby 和 Jellyfin 都可以，填服务器地址（例如 http://192.168.1.2:8096）和账号。")
    OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        password, { password = it }, label = { Text("密码") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(state.error)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            enabled = !state.busy && url.isNotBlank() && username.isNotBlank(),
            onClick = {
                run {
                    val fixed = url.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
                    val creds = withContext(Dispatchers.IO) { EmbyClient.login(fixed, username.trim(), password) }
                    onSuccess(creds, fixed)
                }
            },
        ) { Text("登录") }
        if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
    }
}

/** 打开浏览器授权、在后台轮询结果（Plex PIN、OneDrive 设备码）。 */
@Composable
private fun BrowserPollLogin(
    start: () -> QrSession,
    poll: (QrSession) -> Pair<QrStatus, String?>,
    onSuccess: (String) -> Unit,
    showCode: Boolean,
) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<QrSession?>(null) }
    var status by remember { mutableStateOf("正在准备…") }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        session = null
        failed = false
        try {
            val s = withContext(Dispatchers.IO) { start() }
            session = s
            status = s.hint
            while (true) {
                delay(3000)
                val (st, creds) = withContext(Dispatchers.IO) { poll(s) }
                when (st) {
                    QrStatus.SUCCESS -> {
                        if (creds != null) onSuccess(creds)
                        return@LaunchedEffect
                    }
                    QrStatus.EXPIRED, QrStatus.CANCELED -> {
                        status = if (st == QrStatus.EXPIRED) "授权已过期，请重试" else "已取消"
                        failed = true
                        return@LaunchedEffect
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            status = "出错了：${e.message}"
            failed = true
        }
    }
    val s = session
    if (s == null && !failed) CircularProgressIndicator()
    if (showCode) s?.state?.get("user_code")?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
    Text(status, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s != null && !failed) {
            OutlinedButton(onClick = {
                s.state["user_code"]?.let { code ->
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                }
                openBrowser(context, s.content.orEmpty())
            }) { Text(if (showCode) "复制代码并打开浏览器" else "打开浏览器") }
        }
        if (failed) OutlinedButton(onClick = { attempt++ }) { Text("重试") }
    }
}

@Composable
internal fun PlexLogin(onSuccess: (String) -> Unit) {
    var manual by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !manual, onClick = { manual = false }, label = { Text("plex.tv 账号") })
        FilterChip(selected = manual, onClick = { manual = true }, label = { Text("地址 + 令牌") })
    }
    if (!manual) {
        BrowserPollLogin(start = { PlexClient.startPin() }, poll = { PlexClient.pollPin(it) }, onSuccess = onSuccess, showCode = false)
    } else {
        var url by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        Hint("局域网直连：填服务器地址（例如 http://192.168.1.2:32400）和 X-Plex-Token。")
        OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("X-Plex-Token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { onSuccess(PlexClient.manual(url, token)) }, enabled = url.isNotBlank() && token.isNotBlank()) { Text("保存") }
    }
}

@Composable
internal fun OneDriveLogin(onSuccess: (String) -> Unit) {
    var clientId by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    if (!started) {
        Hint(
            "需要先免费注册一个应用：在 portal.azure.com 打开「应用注册」→ 新注册，账户类型选「任何组织目录中的帐户和个人 Microsoft 帐户」；" +
                "在「身份验证」里打开「允许公共客户端流」。然后复制「应用程序(客户端) ID」填到下面。",
        )
        OutlinedTextField(clientId, { clientId = it }, label = { Text("应用程序(客户端) ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { started = true }, enabled = clientId.isNotBlank()) { Text("下一步") }
    } else {
        BrowserPollLogin(
            start = { OneDriveClient.startDeviceCode(clientId.trim()) },
            poll = { OneDriveClient.pollDeviceCode(it) },
            onSuccess = onSuccess, showCode = true,
        )
    }
}

@Composable
internal fun DropboxLogin(onSuccess: (String) -> Unit) {
    val context = LocalContext.current
    val pkce = remember { Pkce() }
    var appKey by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf(false) }
    val (state, run) = rememberRun()
    Hint(
        "需要先免费建一个应用：在 dropbox.com/developers/apps 选 Create app → Scoped access → Full Dropbox；" +
            "在 Permissions 里勾选 files.metadata.read、files.content.read、files.content.write 并提交，然后复制 App key。",
    )
    OutlinedTextField(appKey, { appKey = it }, label = { Text("App key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(
        enabled = appKey.isNotBlank(),
        onClick = {
            opened = true
            openBrowser(context, DropboxClient.authorizeUrl(appKey.trim(), pkce))
        },
    ) { Text("打开浏览器授权") }
    if (opened) {
        Hint("在浏览器里点「允许」后，Dropbox 会显示一段授权码，复制粘贴到这里：")
        OutlinedTextField(code, { code = it }, label = { Text("授权码") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ErrorText(state.error)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = !state.busy && code.isNotBlank(),
                onClick = {
                    run {
                        val creds = withContext(Dispatchers.IO) { DropboxClient.exchangeCode(appKey.trim(), code, pkce) }
                        onSuccess(creds)
                    }
                },
            ) { Text("完成") }
            if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}

@Composable
internal fun GoogleLogin(onSuccess: (String) -> Unit) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf<LoopbackReceiver?>(null) }
    val (state, run) = rememberRun()
    DisposableEffect(Unit) { onDispose { receiver?.cancel() } }
    Hint(
        "需要先免费建一个 OAuth 客户端：在 console.cloud.google.com 新建项目 → 启用 Google Drive API → " +
            "「OAuth 同意屏幕」选外部，并把自己的 Google 账号加为测试用户 → 「凭据」新建 OAuth 客户端 ID，类型选「桌面应用」。" +
            "测试状态下授权 7 天后需要重新登录；在同意屏幕点「发布应用」可以去掉这个限制。",
    )
    OutlinedTextField(clientId, { clientId = it }, label = { Text("客户端 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        secret, { secret = it }, label = { Text("客户端密钥") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
    ErrorText(state.error)
    if (state.busy) Hint("请在浏览器里选择账号并允许访问，完成后会自动回到这里…")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            enabled = !state.busy && clientId.isNotBlank() && secret.isNotBlank(),
            onClick = {
                val pkce = Pkce()
                val r = LoopbackReceiver()
                receiver = r
                openBrowser(context, GoogleDriveClient.authorizeUrl(clientId.trim(), r.redirectUri, pkce))
                run {
                    val creds = withContext(Dispatchers.IO) {
                        val code = r.awaitCode()
                        GoogleDriveClient.exchangeCode(clientId.trim(), secret.trim(), code, r.redirectUri, pkce)
                    }
                    onSuccess(creds)
                }
            },
        ) { Text("打开浏览器授权") }
        if (state.busy) CircularProgressIndicator(Modifier.size(24.dp))
    }
}
