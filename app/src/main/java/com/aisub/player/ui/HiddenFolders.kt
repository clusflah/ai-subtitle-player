package com.aisub.player.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.LibraryViewModel

/** 确认隐藏一个文件夹；还没设密码时顺便提醒、可以先设密码 */
@Composable
internal fun HideFolderDialog(lib: LibraryViewModel, name: String, onDismiss: () -> Unit, onHide: () -> Unit) {
    val state by lib.state.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }
    if (settingPin) {
        PinDialog(setting = true, onDismiss = { settingPin = false }) { pin ->
            lib.setHiddenPin(pin)
            settingPin = false
            onHide()
        }
        return
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("隐藏「$name」") },
        text = {
            Text(
                "这个文件夹和里面的子文件夹不再出现在浏览、本机、首页、最近播放、播放列表和收藏里。" +
                    "以后在「⋮ → 隐藏的文件夹」里恢复。" +
                    if (state.hasHiddenPin) "" else "\n\n还没设密码，别人也能自己改回来；建议设一个，查看和恢复时要输入。",
            )
        },
        confirmButton = {
            if (state.hasHiddenPin) TextButton(onClick = onHide) { Text("隐藏") }
            else TextButton(onClick = { settingPin = true }) { Text("设密码并隐藏") }
        },
        dismissButton = {
            if (state.hasHiddenPin) TextButton(onClick = onDismiss) { Text("取消") }
            else TextButton(onClick = onHide) { Text("不设密码，直接隐藏") }
        },
    )
}

/** 输入密码（setting = true 时设置新密码，要输两遍） */
@Composable
internal fun PinDialog(setting: Boolean, wrong: Boolean = false, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(if (wrong) "密码不对" else null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(first) { runCatching { focus.requestFocus() } }
    val ok = pin.length >= 4
    fun submit() {
        if (!ok) return
        when {
            !setting -> onDone(pin)
            first == null -> { first = pin; pin = ""; error = null }
            first == pin -> onDone(pin)
            else -> { first = null; pin = ""; error = "两次输入的不一样，请重新设置" }
        }
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (!setting) "输入密码" else if (first == null) "设置密码" else "再输一遍") },
        text = {
            Column {
                OutlinedTextField(
                    pin, { v -> pin = v.filter { it.isDigit() }.take(12); error = null },
                    label = { Text("4 位以上数字") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = ok) { Text(if (setting && first == null) "下一步" else "确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 隐藏的文件夹：列表、取消隐藏、暂时显示、密码。设了密码时先要输对才进得来 */
@Composable
fun HiddenFoldersScreen(lib: LibraryViewModel, onBack: () -> Unit) {
    val state by lib.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 进来时有密码就先问；已经暂时显示（输过密码）就不再问
    var unlocked by remember { mutableStateOf(!state.hasHiddenPin || state.showHidden) }
    var wrong by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    if (!unlocked) {
        androidx.compose.runtime.key(attempt) {
            PinDialog(setting = false, wrong = wrong, onDismiss = onBack) { pin ->
                if (lib.checkHiddenPin(pin)) unlocked = true else { wrong = true; attempt++ }
            }
        }
        DetailScaffold("隐藏的文件夹", onBack) {}
        return
    }
    var pinDialog by remember { mutableStateOf(false) }
    if (pinDialog) {
        PinDialog(setting = true, onDismiss = { pinDialog = false }) { pin ->
            lib.setHiddenPin(pin)
            pinDialog = false
            Toast.makeText(context, "密码已设置", Toast.LENGTH_SHORT).show()
        }
    }
    DetailScaffold("隐藏的文件夹", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SurfaceCard {
                ListRow(
                    title = "暂时显示隐藏的文件夹",
                    subtitle = "打开后隐藏的文件夹照常显示，菜单里可以「取消隐藏」；重开 App 后自动恢复隐藏",
                    icon = if (state.showHidden) AppIcons.LockOpen else AppIcons.Lock,
                    trailing = { Switch(state.showHidden, onCheckedChange = { lib.setShowHidden(it) }) },
                    onClick = { lib.setShowHidden(!state.showHidden) },
                )
                CardDivider(inset = 70.dp)
                ListRow(
                    title = if (state.hasHiddenPin) "修改密码" else "设置密码",
                    subtitle = if (state.hasHiddenPin) "进这一页要先输入密码" else "没设密码时谁都能进这一页把文件夹改回来",
                    icon = AppIcons.Lock,
                    onClick = { pinDialog = true },
                )
                if (state.hasHiddenPin) {
                    CardDivider(inset = 70.dp)
                    ListRow(
                        title = "去掉密码",
                        icon = AppIcons.LockOpen, mutedIcon = true,
                        onClick = { lib.setHiddenPin(""); Toast.makeText(context, "密码已去掉", Toast.LENGTH_SHORT).show() },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SectionHeader("已隐藏（${state.hiddenFolders.size}）")
            if (state.hiddenFolders.isEmpty()) {
                Text(
                    "还没有隐藏的文件夹。在本机或网盘文件夹的「⋮」菜单里选「隐藏文件夹」。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            } else {
                SurfaceCard {
                    state.hiddenFolders.forEachIndexed { i, f ->
                        if (i > 0) CardDivider(inset = 70.dp)
                        val source = f.serverId?.let { id -> state.servers.firstOrNull { it.id == id }?.name } ?: "本机"
                        // 网盘的 path 是目录 ID，只显示来源
                        val where = if (f.serverId == null) "本机 · 内部存储/${f.path}" else if (f.path.startsWith("/")) "$source · ${f.path}" else source
                        ListRow(
                            title = f.title.ifEmpty { f.path },
                            subtitle = where,
                            icon = if (f.serverId == null) AppIcons.Folder else AppIcons.Cloud,
                            trailing = { TextButton(onClick = { lib.unhideFolder(f.serverId, f.path) }) { Text("取消隐藏") } },
                        )
                    }
                }
            }
        }
    }
}
