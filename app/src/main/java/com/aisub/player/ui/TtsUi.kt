package com.aisub.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aisub.player.AppSettings
import com.aisub.player.MainViewModel
import com.aisub.player.tts.TtsVoice
import kotlinx.coroutines.launch

fun genderLabel(gender: String) = when (gender) {
    "male" -> "男"
    "female" -> "女"
    else -> "未知"
}

/**
 * 语音选择：按目标语言在线拉取当前引擎的可用语音，可按性别筛选、逐个试听。
 * allowAuto 为 true 时第一项是「自动」（用该语言的第一个语音），选中时回调 null。
 */
@Composable
fun VoicePickerDialog(
    vm: MainViewModel,
    settings: AppSettings,
    title: String,
    current: String?,
    allowAuto: Boolean,
    initialGender: String? = null,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    var voices by remember { mutableStateOf<List<TtsVoice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var gender by remember { mutableStateOf(initialGender?.takeIf { it != "unknown" }) }
    var previewing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) {
        voices = null
        error = null
        try {
            voices = vm.ttsVoices(settings, refresh = reload > 0)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${settings.ttsEngine.label} · ${settings.targetLanguage}" + (settings.ttsLocale?.let { "（$it）" } ?: "（全部语言）"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "全部", "female" to "女声", "male" to "男声").forEach { (g, label) ->
                        FilterChip(selected = gender == g, onClick = { gender = g }, label = { Text(label) })
                    }
                }
                val list = voices
                when {
                    error != null -> Text("获取语音列表失败：$error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        val shown = list.filter { gender == null || it.gender == gender }
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            if (allowAuto) item {
                                VoiceRow("自动", "用该语言的第一个语音", current == null, previewing == "", onPreview = null) { onPick(null) }
                            }
                            if (shown.isEmpty()) item {
                                Text("没有符合条件的语音", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                            }
                            items(shown, key = { it.id }) { v ->
                                VoiceRow(
                                    v.name, listOf(v.locale, genderLabel(v.gender), v.detail.takeIf { d -> d.isNotEmpty() && !d.contains(',') && d != v.locale })
                                        .filterNotNull().joinToString(" · ") + "\n" + v.id,
                                    v.id == current, previewing == v.id,
                                    onPreview = {
                                        previewing = v.id
                                        scope.launch {
                                            try {
                                                vm.previewVoice(v.id, settings)
                                            } catch (e: Exception) {
                                                error = e.message
                                            }
                                            previewing = null
                                        }
                                    },
                                ) { onPick(v.id) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { reload++ }) { Text("刷新列表") } },
    )
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, loading: Boolean, onPreview: (() -> Unit)?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onPreview != null) {
            if (loading) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onPreview) { Icon(AppIcons.Volume, "试听") }
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

/**
 * 角色与配音：用大模型识别每句台词的角色，给每个角色选语音。
 * 旁白（没识别出角色的台词）用设置里的默认语音。
 */
@Composable
fun RolesPanel(vm: MainViewModel, state: com.aisub.player.UiState) {
    val settings = state.settings
    var picking by remember { mutableStateOf<com.aisub.player.subtitle.Role?>(null) }
    var pickingDefault by remember { mutableStateOf(false) }
    var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(settings.ttsEngine, settings.ttsLocale, state.roles) {
        names = runCatching { vm.ttsVoices(settings).associate { it.id to it.name } }.getOrDefault(emptyMap())
    }
    fun voiceLabel(id: String?) = if (id == null) "自动" else names[id]?.let { "$it（$id）" } ?: id
    val counts = remember(state.cues) { state.cues.groupingBy { it.speaker }.eachCount() }

    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("角色与配音", style = MaterialTheme.typography.titleLarge)
        Text(
            "由翻译服务的大模型根据台词判断说话人，再按性别自动分配${settings.ttsEngine.label}的语音；只看文字，可能分错，可以逐个更换。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val busy = state.roleStatus != null
            androidx.compose.material3.Button(onClick = vm::detectRoles, enabled = !busy && state.cues.isNotEmpty()) {
                Text(if (state.roles.isEmpty()) "识别角色" else "重新识别")
            }
            if (state.roles.isNotEmpty()) {
                androidx.compose.material3.OutlinedButton(onClick = vm::reassignVoices, enabled = !busy) { Text("按性别重新配音") }
            }
        }
        state.roleStatus?.let { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(s, style = MaterialTheme.typography.bodySmall)
            }
        }
        RoleRow("旁白 / 未识别", "${counts[null] ?: 0} 句 · 默认语音", voiceLabel(settings.ttsVoices[settings.ttsEngine])) { pickingDefault = true }
        state.roles.forEach { r ->
            val voice = r.voice?.takeIf { r.voiceEngine == settings.ttsEngine.name }
            RoleRow(r.name, "${genderLabel(r.gender)} · ${counts[r.id] ?: 0} 句", voiceLabel(voice ?: settings.ttsVoices[settings.ttsEngine])) { picking = r }
        }
    }

    picking?.let { r ->
        VoicePickerDialog(
            vm, settings, "「${r.name}」的语音", r.voice?.takeIf { r.voiceEngine == settings.ttsEngine.name }, allowAuto = false,
            initialGender = r.gender, onDismiss = { picking = null },
        ) { v ->
            if (v != null) vm.setRoleVoice(r.id, v)
            picking = null
        }
    }
    if (pickingDefault) {
        VoicePickerDialog(vm, settings, "默认语音", settings.ttsVoices[settings.ttsEngine], allowAuto = true, onDismiss = { pickingDefault = false }) { v ->
            vm.updateSettings(settings.copy(ttsVoices = if (v == null) settings.ttsVoices - settings.ttsEngine else settings.ttsVoices + (settings.ttsEngine to v)))
            pickingDefault = false
        }
    }
}

@Composable
private fun RoleRow(name: String, info: String, voice: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.People, null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(voice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(AppIcons.ArrowForward, "更换语音", modifier = Modifier.size(20.dp))
    }
}
