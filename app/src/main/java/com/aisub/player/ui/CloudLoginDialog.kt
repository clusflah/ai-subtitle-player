package com.aisub.player.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aisub.player.remote.Server
import com.aisub.player.remote.ServerType
import com.aisub.player.remote.cloud.AliyunClient
import com.aisub.player.remote.cloud.BaiduClient
import com.aisub.player.remote.cloud.Pan115Client
import com.aisub.player.remote.cloud.PikPakClient
import com.aisub.player.remote.cloud.QrSession
import com.aisub.player.remote.cloud.QrStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 网盘登录：115 / 阿里云盘扫码，百度网盘设备码或 refresh_token，PikPak 账号密码。 */
@Composable
fun CloudLoginDialog(type: ServerType, existing: Server?, onDismiss: () -> Unit, onLoggedIn: (Server) -> Unit) {
    val finishWithUrl: (String, String?) -> Unit = { creds, url ->
        val base = existing ?: Server(type = type, name = type.label, baseUrl = "")
        onLoggedIn(base.copy(extra = creds, baseUrl = url ?: base.baseUrl))
    }
    val finish: (String) -> Unit = { finishWithUrl(it, null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录${type.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (type) {
                    ServerType.PAN115 -> QrLogin(
                        start = { Pan115Client.startQr() },
                        poll = { s ->
                            when (val st = Pan115Client.pollQr(s)) {
                                QrStatus.SUCCESS -> st to Pan115Client.finishQr(s)
                                else -> st to null
                            }
                        },
                        onSuccess = finish,
                    )
                    ServerType.ALIYUN -> QrLogin(
                        start = { AliyunClient.startQr() },
                        poll = { s ->
                            val (st, code) = AliyunClient.pollQr(s)
                            if (st == QrStatus.SUCCESS && code != null) st to AliyunClient.finishQr(code) else st to null
                        },
                        onSuccess = finish,
                    )
                    ServerType.BAIDU -> BaiduLogin(finish)
                    ServerType.PIKPAK -> PikPakLogin(finish)
                    ServerType.EMBY -> EmbyLogin(existing?.baseUrl.orEmpty(), finishWithUrl)
                    ServerType.PLEX -> PlexLogin(finish)
                    ServerType.ONEDRIVE -> OneDriveLogin(finish)
                    ServerType.DROPBOX -> DropboxLogin(finish)
                    ServerType.GDRIVE -> GoogleLogin(finish)
                    else -> Text("不支持的类型")
                }
                Text(
                    "登录信息只保存在本机。网盘的第三方接口可能随时变化，账号有被风控的可能，请自行斟酌。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 通用扫码流程：取二维码 → 每 2 秒轮询 → 成功后回调凭据。 */
@Composable
private fun QrLogin(
    start: () -> QrSession,
    poll: (QrSession) -> Pair<QrStatus, String?>,
    onSuccess: (String) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<QrSession?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var expired by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        session = null
        expired = false
        try {
            val s = withContext(Dispatchers.IO) { start() }
            session = s
            status = s.hint
            while (true) {
                delay(2000)
                val (st, creds) = withContext(Dispatchers.IO) { poll(s) }
                when (st) {
                    QrStatus.WAITING -> {}
                    QrStatus.SCANNED -> status = "已扫码，请在手机上确认"
                    QrStatus.SUCCESS -> {
                        if (creds != null) onSuccess(creds)
                        return@LaunchedEffect
                    }
                    QrStatus.EXPIRED, QrStatus.CANCELED -> {
                        status = if (st == QrStatus.EXPIRED) "二维码已过期" else "已取消"
                        expired = true
                        return@LaunchedEffect
                    }
                }
            }
        } catch (e: Exception) {
            status = "出错了：${e.message}"
            expired = true
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            val img = session?.imageUrl
            if (img.isNullOrEmpty()) CircularProgressIndicator() else RemoteImage(img)
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        if (expired) OutlinedButton(onClick = { attempt++ }) { Text("刷新二维码") }
    }
}

@Composable
private fun RemoteImage(url: String) {
    val image by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
            }.getOrNull()
        }
    }
    val img = image
    if (img != null) Image(img, contentDescription = "二维码", modifier = Modifier.size(220.dp))
    else CircularProgressIndicator()
}

@Composable
private fun BaiduLogin(onSuccess: (String) -> Unit) {
    var useAppKey by remember { mutableStateOf(true) }
    var appKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    var refreshToken by remember { mutableStateOf("") }
    var renewApi by remember { mutableStateOf(BaiduClient.DEFAULT_RENEW_API) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = useAppKey, onClick = { useAppKey = true; started = false }, label = { Text("自有 AppKey 扫码") })
        FilterChip(selected = !useAppKey, onClick = { useAppKey = false }, label = { Text("refresh_token") })
    }
    if (useAppKey) {
        if (!started) {
            Text(
                "在 pan.baidu.com/union 创建应用后获得 AppKey 和 SecretKey。授权完全走百度官方接口。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
            )
            OutlinedTextField(appKey, { appKey = it }, label = { Text("AppKey") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                secretKey, { secretKey = it }, label = { Text("SecretKey") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { started = true }, enabled = appKey.isNotBlank() && secretKey.isNotBlank()) { Text("获取二维码") }
        } else {
            QrLogin(
                start = { BaiduClient.startDeviceCode(appKey.trim()) },
                poll = { s -> BaiduClient.pollDeviceCode(s, secretKey.trim()) },
                onSuccess = onSuccess,
            )
        }
    } else {
        Text(
            "粘贴已有的 refresh_token。token 由下面的续期接口刷新——默认是 OpenList 提供的公共服务 api.oplist.org（可在其网页上扫码获取 token）。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
        OutlinedTextField(refreshToken, { refreshToken = it }, label = { Text("refresh_token") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(renewApi, { renewApi = it }, label = { Text("续期接口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedButton(
            onClick = { onSuccess(BaiduClient.credsFromRefreshToken(refreshToken, renewApi)) },
            enabled = refreshToken.isNotBlank(),
        ) { Text("保存") }
    }
}

@Composable
private fun PikPakLogin(onSuccess: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var verifyUrl by remember { mutableStateOf<String?>(null) }
    var captcha by remember { mutableStateOf("") }

    OutlinedTextField(username, { username = it }, label = { Text("邮箱 / 手机号") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        password, { password = it }, label = { Text("密码") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
    )
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    verifyUrl?.let { url ->
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("打开验证页面") }
        OutlinedTextField(
            captcha, { captcha = it },
            label = { Text("验证后的跳转地址或 captcha_token（可选）") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        val creds = withContext(Dispatchers.IO) { PikPakClient.loginWithPassword(username, password, captcha) }
                        onSuccess(creds)
                    } catch (e: PikPakClient.VerificationRequired) {
                        verifyUrl = e.url
                        message = "PikPak 要求人机验证：点「打开验证页面」完成验证后，再点登录。"
                    } catch (e: Exception) {
                        message = e.message ?: e.toString()
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("登录") }
        if (busy) CircularProgressIndicator(Modifier.size(24.dp))
    }
}
