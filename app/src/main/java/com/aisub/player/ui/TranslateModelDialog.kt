package com.aisub.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.AppSettings
import com.aisub.player.Provider
import com.aisub.player.translate.ModelList
import kotlinx.coroutines.launch

/** 这些服务没有「模型」可选（离线翻译不用；Azure 翻译那一栏填的是区域） */
private val Provider.hasModel get() = this != Provider.OFFLINE_TRANSLATOR && this != Provider.AZURE_TRANSLATOR

/** 翻译前选的服务和模型（对话框里的临时状态，确认后写回设置） */
@Stable
class TranslatePick(private val settings: AppSettings) {
    /** 已经配置好、能直接用的翻译服务 */
    val ready: List<Provider> = Provider.entries.filter { settings.copy(provider = it).translatorProblem() == null }
    var provider by mutableStateOf(settings.provider)
    var model by mutableStateOf(settings.configOf(settings.provider).model)
    var models by mutableStateOf<List<String>>(emptyList())
    var error by mutableStateOf<String?>(null)

    val valid: Boolean get() = provider in ready && (!provider.hasModel || model.isNotBlank())

    fun pick(p: Provider) {
        provider = p
        model = settings.configOf(p).model
        models = emptyList()
        error = null
    }

    /** 把选择写进设置：选的服务成为默认，模型存到这个服务的配置里 */
    fun applyTo(s: AppSettings): AppSettings {
        val withModel = if (provider.hasModel) {
            s.withConfig(provider, (s.providerConfigs[provider] ?: s.configOf(provider)).copy(model = model.trim()))
        } else s
        return withModel.copy(provider = provider)
    }
}

@Composable
fun rememberTranslatePick(settings: AppSettings) = remember(settings) { TranslatePick(settings) }

/** 翻译服务（点选）+ 模型（手填或从服务商拉取列表） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslateModelPicker(pick: TranslatePick, settings: AppSettings) {
    var listOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        if (pick.ready.isEmpty()) {
            Text("还没有配置好的翻译服务，请先在「设置 → 字幕翻译」里填写。", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pick.ready.forEach { p ->
                FilterChip(selected = pick.provider == p, onClick = { pick.pick(p) }, label = { Text(p.label) })
            }
        }
        if (pick.provider.hasModel && pick.provider in pick.ready) {
            Box(Modifier.padding(top = 4.dp)) {
                OutlinedTextField(
                    value = pick.model, onValueChange = { pick.model = it },
                    label = { Text("翻译模型") }, singleLine = true,
                    trailingIcon = {
                        if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else IconButton(onClick = {
                            if (pick.models.isNotEmpty()) { listOpen = true; return@IconButton }
                            loading = true
                            pick.error = null
                            val p = pick.provider
                            scope.launch {
                                runCatching { ModelList.fetch(p, settings.configOf(p)) }
                                    .onSuccess { pick.models = it; listOpen = it.isNotEmpty(); if (it.isEmpty()) pick.error = "服务商没有返回模型列表" }
                                    .onFailure { pick.error = "获取模型列表失败：${it.message}" }
                                loading = false
                            }
                        }) { Icon(AppIcons.ArrowDropDown, "列出模型") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                AppMenu(expanded = listOpen, onDismissRequest = { listOpen = false }) {
                    pick.models.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { pick.model = m; listOpen = false })
                    }
                }
            }
        }
        pick.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}

/**
 * 翻译前选翻译服务和模型（翻译已有字幕、重新翻译时用；生成字幕时合并在选语言的对话框里）。
 * 选好后保存为默认（下次打开就是它），再开始翻译。
 */
@Composable
fun TranslateModelDialog(
    settings: AppSettings,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (AppSettings) -> Unit,
) {
    val pick = rememberTranslatePick(settings)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                TranslateModelPicker(pick, settings)
            }
        },
        confirmButton = { TextButton(enabled = pick.valid, onClick = { onConfirm(pick.applyTo(settings)) }) { Text("开始翻译") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
