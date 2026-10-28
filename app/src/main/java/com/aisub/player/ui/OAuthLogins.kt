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

/** 打开浏览器；没有浏览器（很多电视）时不闪退，返回 false。 */
private fun openBrowser(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

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
internal fun EmbyLogin(initialUrl: String, initialLan: String, onSuccess: (creds: String, url: String, lanUrl: String) -> Unit) {
    var url by remember { mutableStateOf(initialUrl) }
    var lan by remember { mutableStateOf(initialLan) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val (state, run) = rememberRun()
    Hint("Emby 和 Jellyfin 都可以，填服务器地址（例如 http://192.168.1.2:8096）和账号。")
    PhoneInputButton(
        listOf(PhoneField("url", "服务器地址", url), PhoneField("lan", "内网地址（可不填）", lan), PhoneField("user", "用户名", username), PhoneField("pass", "密码", secret = true)),
        title = "登录 Emby / Jellyfin",
    ) { v ->
        url = v["url"].orEmpty(); lan = v["lan"].orEmpty(); username = v["user"].orEmpty(); password = v["pass"].orEmpty()
    }
    OutlinedTextField(url, { url = it }, label = { Text("服务器地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        lan, { lan = it }, label = { Text("内网地址（可不填）") }, placeholder = { Text("http://192.168.1.2:8096") },
        supportingText = { Text("上面填外网域名时，在家能连上内网地址就自动走内网") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
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
                    fun fix(u: String) = u.trim().trimEnd('/').let { if (it.isEmpty() || it.startsWith("http")) it else "http://$it" }
                    val fixed = fix(url)
                    val fixedLan = fix(lan)
                    val creds = withContext(Dispatchers.IO) { EmbyClient.login(fixed, fixedLan, username.trim(), password) }
                    onSuccess(creds, fixed, fixedLan)
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
    val tv = LocalTv.current
    if (s == null && !failed) CircularProgressIndicator()
    if (showCode) s?.state?.get("user_code")?.let { Text(it, style = MaterialTheme.typography.headlineMedium) }
    // 电视：授权页显示成二维码，用手机扫码完成（电视上一般没有浏览器，遥控器打字也慢）
    if (tv && s != null && !failed && !s.content.isNullOrEmpty()) {
        ScanWithPhone(
            s.content,
            if (showCode) "用手机扫码打开验证页面，输入上面的代码并登录；完成后这里会自动继续。"
            else "用手机扫码打开授权页面，登录并允许访问；完成后这里会自动继续。",
        )
    }
    if (!tv || showCode) Text(status, style = MaterialTheme.typography.bodyMedium)
    else if (failed) Text(status, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s != null && !failed && (!tv || canOpenBrowser(context))) {
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
        PhoneInputButton(listOf(PhoneField("url", "服务器地址", url), PhoneField("token", "X-Plex-Token", token)), title = "连接 Plex 服务器") { v ->
            url = v["url"].orEmpty(); token = v["token"].orEmpty()
        }
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
        PhoneInputButton(listOf(PhoneField("client_id", "应用程序(客户端) ID", clientId)), title = "登录 OneDrive") { v ->
            clientId = v["client_id"].orEmpty()
            if (clientId.isNotBlank()) started = true
        }
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
    // 电视：整个授权在手机上做，授权码也在手机上粘贴，不用遥控器打字
    var onPhone by remember { mutableStateOf(false) }
    if (LocalTv.current) {
        if (!onPhone) OutlinedButton(onClick = { onPhone = true }) { Text("用手机完成授权") }
        else PhoneOAuthPanel(
            title = "登录 Dropbox",
            caption = "用手机扫码打开（手机和电视要在同一个局域网），按网页上的步骤授权，提交后这里自动完成登录。",
            idField = PhoneField("app_key", "① App key", appKey),
            secretField = null,
            authorizeUrl = DropboxClient.authorizeUrl("__CID__", pkce),
            stepsHtml = "② 填好 App key 后点下面的按钮，在 Dropbox 页面点「允许」，复制显示的授权码",
            codeField = PhoneField("code", "③ 粘贴授权码"),
            onCancel = { onPhone = false },
            onSubmit = { v ->
                val key = v["app_key"].orEmpty().ifBlank { throw java.io.IOException("没有填 App key") }
                val creds = withContext(Dispatchers.IO) { DropboxClient.exchangeCode(key, extractAuthCode(v["code"].orEmpty()), pkce) }
                onSuccess(creds)
            },
        )
        if (!canOpenBrowser(context)) return
    }
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
    // 电视：Google 授权后只会跳回「发起授权那台设备」的 127.0.0.1。在手机上授权时跳回的是手机自己，电视收不到，
    // 所以改成在手机上打开电视的临时网页：去授权 → 手机显示打不开的 127.0.0.1 页面 → 复制它的地址粘回网页提交。
    // （Google 给电视用的设备码登录只允许 drive.file 权限，看不到网盘里已有的文件，不能用。）
    var onPhone by remember { mutableStateOf(false) }
    val phonePkce = remember { Pkce() }
    val phoneRedirect = remember { "http://127.0.0.1:${(20000..60000).random()}" }
    if (LocalTv.current) {
        if (!onPhone) OutlinedButton(onClick = { onPhone = true }) { Text("用手机完成授权") }
        else PhoneOAuthPanel(
            title = "登录 Google Drive",
            caption = "用手机扫码打开（手机和电视要在同一个局域网），按网页上的步骤授权，提交后这里自动完成登录。",
            idField = PhoneField("client_id", "① 客户端 ID", clientId),
            secretField = PhoneField("client_secret", "客户端密钥", secret, secret = true),
            authorizeUrl = GoogleDriveClient.authorizeUrl("__CID__", phoneRedirect, phonePkce),
            stepsHtml = "② 点下面的按钮，选择账号并允许访问。最后手机会打开一个<b>无法访问</b>的 127.0.0.1 页面，这是正常的：" +
                "复制那个页面地址栏里的<b>完整地址</b>，回到这个网页粘贴",
            codeField = PhoneField("redirect", "③ 粘贴授权后的地址"),
            onCancel = { onPhone = false },
            onSubmit = { v ->
                val id = v["client_id"].orEmpty().ifBlank { throw java.io.IOException("没有填客户端 ID") }
                val code = extractAuthCode(v["redirect"].orEmpty())
                val creds = withContext(Dispatchers.IO) {
                    GoogleDriveClient.exchangeCode(id, v["client_secret"].orEmpty(), code, phoneRedirect, phonePkce)
                }
                onSuccess(creds)
            },
        )
        // 有浏览器的电视也可以直接在电视上授权（回环地址就是电视自己）
        if (!canOpenBrowser(context)) return
    }
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
