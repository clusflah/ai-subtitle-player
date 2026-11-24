package com.voxmusic.app.ui

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
import com.voxmusic.app.AppSettings
import com.voxmusic.app.Provider
import com.voxmusic.app.translate.ModelList
import kotlinx.coroutines.launch

/** 这些服务没有「模型」可选（离线翻译不用；Azure 翻译那一栏填的是区域） */
private val Provider.hasModel get() = this != Provider.AZURE_TRANSLATOR

/** AI 推荐仅展示对话生成大模型，排除 Index-Translate 专用翻译模型与 Azure 机器翻译 */
private val Provider.isAiRecommendCapable: Boolean
    get() = this != Provider.INDEX_TRANSLATE &&
        this != Provider.AZURE_TRANSLATOR

/** 选服务和模型（对话框里的临时状态，保存后写回设置） */
@Stable
class TranslatePick(
    private val settings: AppSettings,
    val forAiRecommend: Boolean = false,
) {
    /** 已经配置好、能直接用的服务列表 */
    val ready: List<Provider> = Provider.entries.filter { p ->
        (!forAiRecommend || p.isAiRecommendCapable) &&
            settings.copy(provider = p).translatorProblem() == null
    }

    private val initialProvider: Provider = run {
        val preferred = if (forAiRecommend) {
            settings.aiRecommendProvider ?: settings.provider
        } else {
            settings.provider
        }
        if (preferred in ready) preferred else ready.firstOrNull() ?: preferred
    }

    var provider by mutableStateOf(initialProvider)
    var model by mutableStateOf(settings.configOf(initialProvider).model)
    var models by mutableStateOf<List<String>>(emptyList())
    var error by mutableStateOf<String?>(null)

    val valid: Boolean get() = provider in ready && (!provider.hasModel || model.isNotBlank())

    fun pick(p: Provider) {
        provider = p
        model = settings.configOf(p).model
        models = emptyList()
        error = null
    }

    /** 把选择写进设置：推荐模式仅更新 AI 推荐模型配置，不覆盖用户正在使用的离线/专用字幕翻译服务 */
    fun applyTo(s: AppSettings): AppSettings {
        val withModel = if (provider.hasModel) {
            s.withConfig(provider, (s.providerConfigs[provider] ?: s.configOf(provider)).copy(model = model.trim()))
        } else s
        return if (forAiRecommend) {
            withModel.copy(aiRecommendProvider = provider)
        } else {
            withModel.copy(provider = provider)
        }
    }
}

@Composable
fun rememberTranslatePick(
    settings: AppSettings,
    forAiRecommend: Boolean = false,
) = remember(settings, forAiRecommend) { TranslatePick(settings, forAiRecommend) }

/** 服务商（点选）+ 模型（手填或从服务商拉取列表） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslateModelPicker(pick: TranslatePick, settings: AppSettings) {
    var listOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        if (pick.ready.isEmpty()) {
            val hint = if (pick.forAiRecommend) {
                "还没有配置可用的 AI 推荐大模型，请先在「我的 → AI 与歌词翻译」里配置 DeepSeek、Gemini、阿里云百炼、火山方舟、Claude、自定义或本地模型。"
            } else {
                "还没有配置好的翻译服务，请先在「我的 → AI 与歌词翻译」里填写。"
            }
            Text(hint, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            pick.ready.forEach { p ->
                FilterChip(
                    selected = pick.provider == p,
                    onClick = { pick.pick(p) },
                    label = { Text(p.label, maxLines = 1, softWrap = false) },
                )
            }
        }
        if (pick.provider.hasModel && pick.provider in pick.ready) {
            Box(Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = pick.model, onValueChange = { pick.model = it },
                    label = { Text(if (pick.forAiRecommend) "推荐模型" else "翻译模型") }, singleLine = true,
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
 * 选择模型与服务商弹窗（字幕翻译及 AI 推荐影片共用；AI 推荐场景自动过滤纯翻译引擎）。
 */
@Composable
fun TranslateModelDialog(
    settings: AppSettings,
    title: String,
    onDismiss: () -> Unit,
    forAiRecommend: Boolean = false,
    confirmText: String = "保存",
    onConfirm: (AppSettings) -> Unit,
) {
    val pick = rememberTranslatePick(settings, forAiRecommend = forAiRecommend)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                TranslateModelPicker(pick, settings)
            }
        },
        confirmButton = { TextButton(enabled = pick.valid, onClick = { onConfirm(pick.applyTo(settings)) }) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
