package com.aisub.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aisub.player.AppSettings
import com.aisub.player.AsrEngine
import com.aisub.player.Provider
import com.aisub.player.ProviderConfig

private val PROVIDER_HINTS = mapOf(
    Provider.CLAUDE to "Anthropic 官方 API，翻译质量高",
    Provider.DEEPSEEK to "性价比高，国内可直连",
    Provider.GEMINI to "Google AI Studio 的 API Key",
    Provider.INDEX_TRANSLATE to "B 站开源的专用翻译模型，公共接口免费、无需 Key（预览版，不保证稳定）",
    Provider.OPENAI_COMPAT to "任意 OpenAI 兼容接口：通义千问、Kimi、Ollama 等",
)

private val ASR_LANGUAGES = listOf(
    "auto" to "自动", "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语", "yue" to "粤语",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(initial: AppSettings, onDismiss: () -> Unit, onSave: (AppSettings) -> Unit) {
    var s by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSave(s) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("设置") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Section("语音识别")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AsrEngine.entries.forEach { e ->
                        FilterChip(selected = s.asrEngine == e, onClick = { s = s.copy(asrEngine = e) }, label = { Text(e.label) })
                    }
                }
                Text(
                    when (s.asrEngine) {
                        AsrEngine.LOCAL -> "在手机上离线识别，免费；支持中、英、日、韩、粤语"
                        AsrEngine.AZURE -> "上传音频到 Azure 识别，省电、准确率高，按时长计费"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text("视频语言", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ASR_LANGUAGES.take(3).forEach { (code, label) ->
                        FilterChip(selected = s.asrLanguage == code, onClick = { s = s.copy(asrLanguage = code) }, label = { Text(label) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ASR_LANGUAGES.drop(3).forEach { (code, label) ->
                        FilterChip(selected = s.asrLanguage == code, onClick = { s = s.copy(asrLanguage = code) }, label = { Text(label) })
                    }
                }
                when (s.asrEngine) {
                    AsrEngine.LOCAL -> OutlinedTextField(
                        value = s.modelBaseUrl,
                        onValueChange = { s = s.copy(modelBaseUrl = it) },
                        label = { Text("模型下载地址（可换镜像）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AsrEngine.AZURE -> {
                        OutlinedTextField(
                            value = s.azureKey,
                            onValueChange = { s = s.copy(azureKey = it) },
                            label = { Text("Azure 语音 Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = s.azureRegion,
                            onValueChange = { s = s.copy(azureRegion = it) },
                            label = { Text("区域（Region）") },
                            placeholder = { Text("例如 eastasia、southeastasia") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = s.azureEndpoint,
                            onValueChange = { s = s.copy(azureEndpoint = it) },
                            label = { Text("自定义 Endpoint（可不填）") },
                            placeholder = { Text("中国区：https://chinanorth3.api.cognitive.azure.cn") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Section("翻译")
                OutlinedTextField(
                    value = s.targetLanguage,
                    onValueChange = { s = s.copy(targetLanguage = it) },
                    label = { Text("目标语言") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("识别时自动翻译", Modifier.weight(1f))
                    Switch(checked = s.autoTranslate, onCheckedChange = { s = s.copy(autoTranslate = it) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Provider.entries.forEach { p ->
                        FilterChip(selected = s.provider == p, onClick = { s = s.copy(provider = p) }, label = { Text(p.label) })
                    }
                }
                PROVIDER_HINTS[s.provider]?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }

                val p = s.provider
                val raw = s.providerConfigs[p] ?: ProviderConfig()
                if (p.editableBaseUrl) {
                    OutlinedTextField(
                        value = raw.baseUrl,
                        onValueChange = { s = s.withConfig(p, raw.copy(baseUrl = it)) },
                        label = { Text("Base URL") },
                        placeholder = { Text(p.defaultBaseUrl) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = raw.apiKey,
                    onValueChange = { s = s.withConfig(p, raw.copy(apiKey = it)) },
                    label = { Text(if (p.needsApiKey) "API Key" else "API Key（可不填）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = raw.model,
                    onValueChange = { s = s.withConfig(p, raw.copy(model = it)) },
                    label = { Text("模型") },
                    placeholder = { Text(p.defaultModel.ifEmpty { "例如 gpt-5-mini、qwen-plus" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
