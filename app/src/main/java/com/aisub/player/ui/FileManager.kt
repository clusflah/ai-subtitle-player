package com.aisub.player.ui

import android.app.Activity
import android.os.Build
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aisub.player.LibraryViewModel
import com.aisub.player.media.FileOps
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.formatDuration
import com.aisub.player.media.formatSize

/**
 * 文件管理：重命名、移动、删除、分享、详情。
 * 处理 Android 10+ 的系统授权弹窗：用户同意后自动继续刚才的操作，然后刷新媒体库。
 */
class FileManager internal constructor(
    private val context: android.content.Context,
    private val lib: LibraryViewModel,
    private val launch: (IntentSenderRequest) -> Unit,
    private val onAddToPlaylist: (List<MediaEntry>) -> Unit,
    private val scope: CoroutineScope,
) {
    internal var dialog by mutableStateOf<FileDialog?>(null)

    /** 用户在系统弹窗里同意后要执行的操作；null 表示系统已经替我们完成（例如删除）。 */
    internal var pending: (() -> Unit)? = null

    internal fun onPermissionResult(ok: Boolean) {
        val op = pending
        pending = null
        if (!ok) return
        if (op != null) run(op) else finished("已完成")
    }

    private fun run(op: () -> Unit, success: String = "已完成") {
        try {
            op()
            finished(success)
        } catch (e: FileOps.NeedsPermission) {
            pending = op
            launch(IntentSenderRequest.Builder(e.intent).build())
        } catch (e: Exception) {
            Toast.makeText(context, "操作失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun finished(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        lib.refreshMedia()
    }

    /** Android 11+ 先申请写权限，同意后再执行。 */
    private fun withWriteAccess(entries: List<MediaEntry>, op: () -> Unit, success: String) {
        val request = FileOps.writeRequest(context, entries)
        if (request != null) {
            pending = { op() }
            launch(IntentSenderRequest.Builder(request).build())
        } else {
            run(op, success)
        }
    }

    fun share(entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        context.startActivity(FileOps.shareIntent(entries))
    }

    fun delete(entries: List<MediaEntry>) {
        val local = entries.filter(FileOps::isLocal)
        if (local.isEmpty()) return
        // Android 11+ 由系统弹窗确认；旧系统先自己确认一次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                FileOps.delete(context, local)
            } catch (e: FileOps.NeedsPermission) {
                pending = null
                launch(IntentSenderRequest.Builder(e.intent).build())
            }
        } else {
            dialog = FileDialog.ConfirmDelete(local)
        }
    }

    internal fun confirmDelete(entries: List<MediaEntry>) = run({ FileOps.delete(context, entries) }, "已删除 ${entries.size} 个文件")

    fun rename(entry: MediaEntry) {
        dialog = FileDialog.Rename(entry)
    }

    internal fun doRename(entry: MediaEntry, name: String) =
        withWriteAccess(listOf(entry), { FileOps.rename(context, entry, name) }, "已重命名")

    fun move(entries: List<MediaEntry>) {
        val local = entries.filter(FileOps::isLocal)
        if (local.isEmpty()) return
        val folders = (lib.state.value.videos + lib.state.value.audios).map { it.folder }.distinct()
        dialog = FileDialog.Move(local, FileOps.moveTargets(local, folders))
    }

    internal fun doMove(entries: List<MediaEntry>, dir: String) =
        withWriteAccess(entries, { entries.forEach { FileOps.move(context, it, dir) } }, "已移动到 $dir")

    fun copy(entries: List<MediaEntry>) {
        val local = entries.filter(FileOps::isLocal)
        if (local.isEmpty()) return
        val folders = (lib.state.value.videos + lib.state.value.audios).map { it.folder }.distinct()
        dialog = FileDialog.Move(local, FileOps.moveTargets(local, folders), copy = true)
    }

    /** 正在复制时显示的进度（0～1），null 表示没有在复制。 */
    internal var copyProgress by mutableStateOf<Float?>(null)

    internal fun doCopy(entries: List<MediaEntry>, dir: String) {
        if (copyProgress != null) return
        val total = entries.sumOf { it.sizeBytes }.coerceAtLeast(1)
        copyProgress = 0f
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var done = 0L
                    for (e in entries) {
                        FileOps.copy(context, e, dir) { n -> copyProgress = ((done + n).toFloat() / total).coerceAtMost(1f) }
                        done += e.sizeBytes
                    }
                }
                finished("已复制 ${entries.size} 个文件到 $dir")
            } catch (e: Exception) {
                Toast.makeText(context, "复制失败：${e.message}", Toast.LENGTH_LONG).show()
                lib.refreshMedia()
            }
            copyProgress = null
        }
    }

    fun renameFolder(path: String, entries: List<MediaEntry>) {
        if ('/' !in path) {
            Toast.makeText(context, "系统目录不能重命名", Toast.LENGTH_SHORT).show()
            return
        }
        dialog = FileDialog.RenameFolder(path, entries.filter(FileOps::isLocal))
    }

    internal fun doRenameFolder(path: String, entries: List<MediaEntry>, name: String) =
        withWriteAccess(entries, { FileOps.renameFolder(context, entries, path, name) }, "已重命名")

    /** 文件夹的"⋮"菜单。 */
    fun folderMenu(path: String, entries: List<MediaEntry>, onPlayAll: () -> Unit): List<MenuAction> = listOf(
        MenuAction("全部播放", onClick = onPlayAll),
        MenuAction("全部加入播放列表") { onAddToPlaylist(entries) },
        MenuAction("分享全部") { share(entries) },
        MenuAction("重命名文件夹") { renameFolder(path, entries) },
        MenuAction("移动全部到…") { move(entries) },
        MenuAction("删除文件夹（${entries.size} 个媒体文件）") { delete(entries) },
    )

    fun details(entry: MediaEntry) {
        dialog = FileDialog.Info(FileOps.details(context, entry))
    }

    /** 单个文件的"⋮"菜单。 */
    fun menu(entry: MediaEntry): List<MenuAction> {
        val local = FileOps.isLocal(entry)
        return listOfNotNull(
            MenuAction("加入播放列表") { onAddToPlaylist(listOf(entry)) },
            MenuAction("分享") { share(listOf(entry)) }.takeIf { local },
            MenuAction("重命名") { rename(entry) }.takeIf { local },
            MenuAction("移动到…") { move(listOf(entry)) }.takeIf { local },
            MenuAction("复制到…") { copy(listOf(entry)) }.takeIf { local },
            MenuAction("详细信息") { details(entry) }.takeIf { local },
            MenuAction("删除") { delete(listOf(entry)) }.takeIf { local },
        )
    }
}

internal sealed interface FileDialog {
    data class Rename(val entry: MediaEntry) : FileDialog
    data class Move(val entries: List<MediaEntry>, val targets: List<String>, val copy: Boolean = false) : FileDialog
    data class RenameFolder(val path: String, val entries: List<MediaEntry>) : FileDialog
    data class Info(val details: FileOps.Details) : FileDialog
    data class ConfirmDelete(val entries: List<MediaEntry>) : FileDialog
}

@Composable
fun rememberFileManager(lib: LibraryViewModel, onAddToPlaylist: (List<MediaEntry>) -> Unit): FileManager {
    val context = LocalContext.current
    var holder by remember { mutableStateOf<FileManager?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        holder?.onPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    val scope = rememberCoroutineScope()
    val fm = remember(lib) { FileManager(context, lib, { launcher.launch(it) }, onAddToPlaylist, scope) }
    holder = fm
    FileDialogs(fm)
    return fm
}

@Composable
private fun FileDialogs(fm: FileManager) {
    fm.copyProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在复制…") },
            text = { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }
    when (val d = fm.dialog) {
        null -> {}
        is FileDialog.Rename -> TextInputDialog(
            title = "重命名", label = "文件名（扩展名会保留）",
            initial = d.entry.title.substringBeforeLast('.'), confirm = "确定",
            onDismiss = { fm.dialog = null },
        ) { name -> fm.dialog = null; fm.doRename(d.entry, name) }
        is FileDialog.RenameFolder -> TextInputDialog(
            title = "重命名文件夹", label = "新名字（其中 ${d.entries.size} 个媒体文件会一起移过去）",
            initial = d.path.substringAfterLast('/'), confirm = "确定",
            onDismiss = { fm.dialog = null },
        ) { name -> fm.dialog = null; fm.doRenameFolder(d.path, d.entries, name) }
        is FileDialog.Move -> {
            var custom by remember { mutableStateOf("") }
            val go: (String) -> Unit = { dir -> fm.dialog = null; if (d.copy) fm.doCopy(d.entries, dir) else fm.doMove(d.entries, dir) }
            AlertDialog(
                onDismissRequest = { fm.dialog = null },
                title = { Text("${if (d.copy) "复制" else "移动"} ${d.entries.size} 个文件到") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        d.targets.forEach { dir ->
                            Text(
                                dir, style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth().clickable { go(dir) }.padding(vertical = 12.dp),
                            )
                        }
                        OutlinedTextField(
                            custom, { custom = it }, singleLine = true,
                            label = { Text("新建子文件夹，例如 ${d.targets.firstOrNull() ?: "Movies"}/英语") },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { go(custom) }, enabled = custom.isNotBlank()) { Text(if (d.copy) "复制到新文件夹" else "移动到新文件夹") }
                },
                dismissButton = { TextButton(onClick = { fm.dialog = null }) { Text("取消") } },
            )
        }
        is FileDialog.Info -> {
            val x = d.details
            val rows = listOfNotNull(
                "名称" to x.name,
                "位置" to "内部存储/${x.folder}",
                "大小" to formatSize(x.sizeBytes),
                formatDuration(x.durationMs).takeIf { it.isNotEmpty() }?.let { "时长" to it },
                if (x.width > 0) "分辨率" to "${x.width}×${x.height}" else null,
                x.mime.takeIf { it.isNotEmpty() }?.let { "类型" to it },
                if (x.dateAdded > 0) "添加时间" to DateUtils.formatDateTime(null, x.dateAdded, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR) else null,
            )
            AlertDialog(
                onDismissRequest = { fm.dialog = null },
                title = { Text("详细信息") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.forEach { (k, v) ->
                            Column {
                                Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(v, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { fm.dialog = null }) { Text("关闭") } },
            )
        }
        is FileDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { fm.dialog = null },
            title = { Text("删除 ${d.entries.size} 个文件？") },
            text = { Text("文件会从手机里永久删除，不能恢复。") },
            confirmButton = {
                TextButton(onClick = { fm.dialog = null; fm.confirmDelete(d.entries) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { fm.dialog = null }) { Text("取消") } },
        )
    }
}
