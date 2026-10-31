package com.aisub.player.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aisub.player.backup.Backup
import com.aisub.player.backup.BackupFile
import com.aisub.player.backup.BackupReceiver
import com.aisub.player.backup.BackupSection
import com.aisub.player.backup.ReceiverFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BackupDialog { SEND, RECEIVE, EXPORT }

/**
 * 设置页的「备份与迁移」：导出 / 导入备份文件，手机直接发到电视（或另一台手机）。
 * flush：导出前先把设置页里还没保存的改动存下来。
 */
@Composable
internal fun BackupCard(flush: () -> Unit) {
    val context = LocalContext.current
    val tv = LocalTv.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    var incoming by remember { mutableStateOf<BackupFile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportSections by remember { mutableStateOf(BackupSection.entries.toSet()) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                withContext(Dispatchers.IO) {
                    val text = Backup.export(context, exportSections)
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
                "已导出。文件里有明文的密钥和登录信息，请妥善保管"
            }.getOrElse { "导出失败：${it.message}" }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }.toString(Charsets.UTF_8).let(Backup::parse)
                }
            }.onSuccess { incoming = it }.onFailure { message = "导入失败：${it.message}" }
        }
    }

    CollapsibleCard("备份与迁移", "导出、导入设置、密钥、直播源和网盘登录；手机设置好后一键发到电视") {
        if (!tv) {
            ListRow(
                title = "发送到电视",
                subtitle = "先在电视上打开「设置 → 备份与迁移 → 接收备份」，手机和电视在同一个局域网",
                icon = AppIcons.Live,
                onClick = { flush(); dialog = BackupDialog.SEND },
            )
            CardDivider(inset = 70.dp)
        }
        ListRow(
            title = "接收备份",
            subtitle = if (tv) "用手机上的译幕「发送到电视」，或用手机扫码上传备份文件" else "从另一台手机接收",
            icon = AppIcons.Phone,
            onClick = { dialog = BackupDialog.RECEIVE },
        )
        CardDivider(inset = 70.dp)
        ListRow(
            title = "导出备份文件",
            subtitle = "存成 JSON 文件，换手机或在别的设备上导入",
            icon = AppIcons.Download,
            onClick = { flush(); dialog = BackupDialog.EXPORT },
        )
        CardDivider(inset = 70.dp)
        ListRow(
            title = "从文件导入",
            subtitle = "选择译幕导出的备份文件",
            icon = AppIcons.File,
            onClick = { runCatching { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }.onFailure { message = "这台设备没有文件选择器，请用「接收备份」" } },
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }

    when (dialog) {
        BackupDialog.EXPORT -> SectionPickDialog(
            title = "导出备份", confirm = "导出", initial = exportSections,
            onDismiss = { dialog = null },
        ) { picked ->
            exportSections = picked
            dialog = null
            exporter.launch("译幕备份-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json")
        }
        BackupDialog.SEND -> SendDialog(onDismiss = { dialog = null })
        BackupDialog.RECEIVE -> ReceiveDialog(onDismiss = { dialog = null }) { dialog = null; incoming = it }
        null -> {}
    }
    incoming?.let { file -> ImportDialog(file, onDismiss = { incoming = null }) }
}

/** 选要带上的几类数据 */
@Composable
private fun SectionChecklist(selected: Set<BackupSection>, available: Set<BackupSection> = BackupSection.entries.toSet(), detail: (BackupSection) -> String = { it.hint }, onChange: (Set<BackupSection>) -> Unit) {
    Column {
        BackupSection.entries.filter { it in available }.forEach { s ->
            ListRow(
                title = s.label,
                subtitle = detail(s),
                leading = { Checkbox(checked = s in selected, onCheckedChange = null) },
                onClick = { onChange(if (s in selected) selected - s else selected + s) },
            )
        }
    }
}

@Composable
private fun SectionPickDialog(title: String, confirm: String, initial: Set<BackupSection>, onDismiss: () -> Unit, onConfirm: (Set<BackupSection>) -> Unit) {
    var picked by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { SectionChecklist(picked) { picked = it } } },
        confirmButton = { TextButton(onClick = { onConfirm(picked) }, enabled = picked.isNotEmpty()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 手机：找局域网里打开了「接收备份」的电视，选中后发过去 */
@Composable
private fun SendDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finder = remember { ReceiverFinder(context.applicationContext) }
    DisposableEffect(finder) {
        finder.start()
        onDispose { finder.stop() }
    }
    val found by finder.found.collectAsState()
    var sections by remember { mutableStateOf(BackupSection.entries.toSet()) }
    var manual by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }

    fun send(address: String, label: String) {
        sending = label
        result = null
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { Backup.export(context, sections) }
                ReceiverFinder.send(address, text)
            }.onSuccess { result = it.ifBlank { "已发送，请在电视上确认导入" }; ok = true }
                .onFailure { result = "发送失败：${it.message}"; ok = false }
            sending = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送到电视") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionChecklist(sections) { sections = it }
                Text("局域网里的电视", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (found.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在查找…请在电视上打开「设置 → 备份与迁移 → 接收备份」", style = MaterialTheme.typography.bodySmall)
                    }
                }
                found.forEach { r ->
                    ListRow(
                        title = r.name, subtitle = r.address, icon = AppIcons.Live,
                        trailing = { if (sending == r.name) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                        onClick = { if (sending == null && sections.isNotEmpty()) send(r.address, r.name) },
                    )
                }
                Text("找不到的话，输入电视上显示的地址：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        manual, { manual = it }, singleLine = true, placeholder = { Text("192.168.1.5:41234") },
                        modifier = Modifier.weight(1f), colors = appFieldColors(),
                    )
                    OutlinedButton(onClick = { send(manual, manual) }, enabled = manual.isNotBlank() && sending == null && sections.isNotEmpty()) { Text("发送") }
                }
                result?.let { Text(it, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (ok) "完成" else "关闭") } },
    )
}

/** 电视：开端口等手机发来备份，或手机扫码上传文件 */
@Composable
private fun ReceiveDialog(onDismiss: () -> Unit, onReceived: (BackupFile) -> Unit) {
    val context = LocalContext.current
    val receiver = remember { runCatching { BackupReceiver(context.applicationContext) }.getOrNull() }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(receiver) { onDispose { receiver?.close() } }
    LaunchedEffect(receiver) {
        if (receiver?.address == null) return@LaunchedEffect
        try {
            val text = withContext(Dispatchers.IO) { receiver.await() }
            onReceived(Backup.parse(text))
        } catch (e: IOException) {
            error = e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("接收备份") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val url = receiver?.uploadUrl
                if (receiver == null || url == null) {
                    Text("没有连到局域网（Wi-Fi 或有线），没法接收。", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "在手机上的译幕里打开「设置 → 备份与迁移 → 发送到电视」，选「${receiver.name}」；找不到时输入地址 ${receiver.address}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ScanWithPhone(url, "手机上没装译幕：先在别的设备导出备份文件存到手机，再扫码在浏览器里上传") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("等待发送…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 预览收到的备份，选要导入的几类，确认后写入并重启 */
@Composable
private fun ImportDialog(file: BackupFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val available = file.sections.keys
    var picked by remember { mutableStateOf(available) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("导入备份") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val from = listOfNotNull(
                    file.device.ifBlank { null }?.let { "来自 $it" },
                    file.exportedAt.takeIf { it > 0 }?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it)) },
                ).joinToString("，")
                if (from.isNotEmpty()) Text(from, style = MaterialTheme.typography.bodyMedium)
                if (available.isEmpty()) Text("备份里没有数据", color = MaterialTheme.colorScheme.error)
                SectionChecklist(picked, available, detail = { file.summary(it) }) { picked = it }
                Text(
                    "设置会整体换成备份里的；直播源、服务器、播放列表等和这台设备已有的合并，不会删掉已有的。" +
                        "本地识别模型、离线翻译包需要在这台设备上另外下载。导入后译幕会重新启动。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { Backup.import(context, file, picked) } }
                            .onSuccess { restartApp(context) }
                            .onFailure { error = "导入失败：${it.message}"; busy = false }
                    }
                },
            ) { Text(if (busy) "正在导入…" else "导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

/** 重新启动应用：各页面、网盘客户端都重新读取导入的数据 */
private fun restartApp(context: Context) {
    val pm = context.packageManager
    val intent = (pm.getLaunchIntentForPackage(context.packageName) ?: pm.getLeanbackLaunchIntentForPackage(context.packageName))
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
