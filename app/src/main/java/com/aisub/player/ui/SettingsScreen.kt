package com.aisub.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.AppSettings
import com.aisub.player.AsrEngine
import com.aisub.player.MainViewModel
import com.aisub.player.Provider
import com.aisub.player.ProviderConfig
import com.aisub.player.asr.ModelCatalog
import com.aisub.player.subtitle.Pinyin
import com.aisub.player.translate.ModelList
import com.aisub.player.tts.TtsEngineType
import com.aisub.player.tts.TtsReader
import com.aisub.player.asrProblem
import com.aisub.player.withAsrValue
import kotlinx.coroutines.launch

private val PROVIDER_HINTS = mapOf(
    Provider.CLAUDE to "Anthropic 官方 API，翻译质量高",
    Provider.DEEPSEEK to "性价比高，国内可直连",
    Provider.GEMINI to "Google AI Studio 的 API Key",
    Provider.INDEX_TRANSLATE to "B 站开源翻译模型，公共接口免费、无需 Key（预览版）",
    Provider.OPENAI_COMPAT to "兼容接口：通义千问、Kimi、Ollama 等",
)

private val ASR_LANGUAGES = listOf(
    "auto" to "自动", "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语", "yue" to "粤语",
)

private val TARGET_LANGUAGES = listOf("简体中文", "繁體中文", "English", "日本語", "한국어")

/**
 * 设置页：按工作流程分组（语音识别 → 字幕翻译 → 字幕样式），
 * 只展开当前选中服务的配置项；每个服务可单独测试连接；顶部"保存"统一保存。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val initial = remember { vm.state.value.settings }
    var s by remember { mutableStateOf(initial) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = s != initial
    val leave = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(onBack = leave)

    // 连接测试结果：键为 "asr:<引擎>" 或 "tr:<服务商>"
    val testResults = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    fun runTest(key: String, block: suspend () -> String) {
        testing[key] = true
        scope.launch {
            testResults[key] = block()
            testing[key] = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = leave) { Icon(AppIcons.ArrowBack, "返回") } },
                title = { Text("设置") },
                actions = {
                    TextButton(onClick = { vm.updateSettings(s); onBack() }, enabled = dirty) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {

            // ---------------- 语音识别 ----------------
            SectionHeader("语音识别")
            SurfaceCard {
                AsrEngine.entries.forEachIndexed { i, engine ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val selected = s.asrEngine == engine
                    val key = "asr:${engine.name}"
                    ListRow(
                        title = engine.label,
                        subtitle = engine.hint,
                        leading = { RadioButton(selected = selected, onClick = { s = s.copy(asrEngine = engine) }) },
                        trailing = { AsrStatus(engine, s, vm, testResults[key]) },
                        onClick = { s = s.copy(asrEngine = engine) },
                    )
                    AnimatedVisibility(selected) {
                        ConfigBlock {
                            if (engine == AsrEngine.LOCAL) {
                                LocalModels(s, vm) { s = it }
                            } else {
                                engine.fields.forEach { f ->
                                    ConfigField(
                                        value = s.asrConfigs[engine]?.get(f.key) ?: "",
                                        onChange = { s = s.withAsrValue(engine, f.key, it) },
                                        label = f.label,
                                        placeholder = f.placeholder.ifEmpty { f.default },
                                        secret = f.secret,
                                    )
                                }
                                TestRow(testing[key] == true, testResults[key]) { runTest(key) { vm.testAsr(s) } }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SurfaceCard {
                Column(Modifier.padding(16.dp)) {
                    Text("视频语言", style = MaterialTheme.typography.bodyLarge)
                    Text("选错会降低准确率；不确定时选「自动」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ASR_LANGUAGES.forEach { (code, label) ->
                            FilterChip(selected = s.asrLanguage == code, onClick = { s = s.copy(asrLanguage = code) }, label = { Text(label) })
                        }
                    }
                }
            }

            // ---------------- 字幕翻译 ----------------
            SectionHeader("字幕翻译")
            SurfaceCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = s.targetLanguage, onValueChange = { s = s.copy(targetLanguage = it) },
                        label = { Text("目标语言") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TARGET_LANGUAGES.forEach { lang ->
                            FilterChip(selected = s.targetLanguage == lang, onClick = { s = s.copy(targetLanguage = lang) }, label = { Text(lang) })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = "识别时自动翻译",
                    subtitle = "边识别边翻译，攒够 20 句发一次请求",
                    trailing = { Switch(checked = s.autoTranslate, onCheckedChange = { s = s.copy(autoTranslate = it) }) },
                    onClick = { s = s.copy(autoTranslate = !s.autoTranslate) },
                )
            }
            Spacer(Modifier.height(12.dp))
            SurfaceCard {
                Provider.entries.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    val selected = s.provider == p
                    val key = "tr:${p.name}"
                    ListRow(
                        title = p.label,
                        subtitle = PROVIDER_HINTS[p],
                        leading = { RadioButton(selected = selected, onClick = { s = s.copy(provider = p) }) },
                        trailing = { ProviderStatus(p, s, testResults[key]) },
                        onClick = { s = s.copy(provider = p) },
                    )
                    AnimatedVisibility(selected) {
                        ConfigBlock {
                            val raw = s.providerConfigs[p] ?: ProviderConfig()
                            if (p.editableBaseUrl) {
                                ConfigField(raw.baseUrl, { s = s.withConfig(p, raw.copy(baseUrl = it)) }, "Base URL", p.defaultBaseUrl)
                            }
                            ConfigField(
                                raw.apiKey, { s = s.withConfig(p, raw.copy(apiKey = it)) },
                                if (p.needsApiKey) "API Key" else "API Key（可不填）", "", secret = true,
                            )
                            val cfg = s.configOf(p)
                            ModelField(
                                raw.model, { s = s.withConfig(p, raw.copy(model = it)) },
                                p.defaultModel.ifEmpty { "例如 qwen-plus、moonshot-v1-8k" },
                                // Key 或地址变了，之前拉到的列表作废
                                listKey = "${p.name}|${cfg.baseUrl}|${cfg.apiKey}",
                                fetch = { ModelList.fetch(p, cfg) },
                            )
                            TestRow(testing[key] == true, testResults[key]) { runTest(key) { vm.testTranslator(s) } }
                        }
                    }
                }
            }

            // ---------------- 在线字幕 ----------------
            SectionHeader("在线字幕")
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("射手网（中文字幕多）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在 assrt.net 注册登录后，到「用户面板」复制 API Token",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConfigField(s.assrtToken, { s = s.copy(assrtToken = it) }, "API Token", "", secret = true)
                    Spacer(Modifier.height(8.dp))
                    Text("OpenSubtitles（外语片多）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在 opensubtitles.com 注册后，到 opensubtitles.com/consumers 新建 API Key。账号密码可不填，不登录每天限 5 次下载",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConfigField(s.osApiKey, { s = s.copy(osApiKey = it) }, "API Key", "", secret = true)
                    ConfigField(s.osUsername, { s = s.copy(osUsername = it) }, "用户名（可选）", "")
                    ConfigField(s.osPassword, { s = s.copy(osPassword = it) }, "密码（可选）", "", secret = true)
                    ConfigField(s.subtitleLanguages, { s = s.copy(subtitleLanguages = it) }, "搜索语言", "zh-cn,en")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("zh-cn,en" to "简中 + 英", "zh-cn,zh-tw" to "简中 + 繁中", "en" to "仅英文", "zh-cn,en,ja" to "简中 + 英 + 日").forEach { (v, label) ->
                            FilterChip(selected = s.subtitleLanguages == v, onClick = { s = s.copy(subtitleLanguages = v) }, label = { Text(label) })
                        }
                    }
                }
            }

            // ---------------- 影视信息 ----------------
            SectionHeader("影视信息")
            SurfaceCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("主数据源", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "详情页先读视频旁边的 NFO 和图片，缺的内容从主数据源取，再用其他已开启的来源补全",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.aisub.player.meta.MetaSource.entries.forEach { src ->
                            FilterChip(
                                selected = s.metaPrimary == src.name, onClick = { s = s.copy(metaPrimary = src.name) },
                                label = { Text(src.label) },
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TMDB（简介、海报、演职员）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在 themoviedb.org 注册后，到「设置 → API」免费申请，填 API Key 或 Read Access Token 都可以。" +
                            "国内连不上时可以填反代地址",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConfigField(s.tmdbKey, { s = s.copy(tmdbKey = it) }, "API Key", "", secret = true)
                    ConfigField(s.tmdbBaseUrl, { s = s.copy(tmdbBaseUrl = it) }, "接口地址（可选）", com.aisub.player.meta.Tmdb.DEFAULT_BASE)
                    ConfigField(s.tmdbImageBase, { s = s.copy(tmdbImageBase = it) }, "图片地址（可选）", com.aisub.player.meta.Tmdb.DEFAULT_IMAGE_BASE)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = "豆瓣",
                    subtitle = "评分、中文简介、海报、演职员，不用 Key；非官方接口，可能被限流",
                    trailing = { Switch(checked = s.doubanEnabled, onCheckedChange = { s = s.copy(doubanEnabled = it) }) },
                    onClick = { s = s.copy(doubanEnabled = !s.doubanEnabled) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = "Bangumi 番组计划",
                    subtitle = "动画的评分、简介和分集标题，不用 Key",
                    trailing = { Switch(checked = s.bangumiEnabled, onCheckedChange = { s = s.copy(bangumiEnabled = it) }) },
                    onClick = { s = s.copy(bangumiEnabled = !s.bangumiEnabled) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TheTVDB（剧集分集信息）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在 thetvdb.com 注册后到「API Keys」申请；订阅用户的 Key 还要填 PIN。不填则不使用",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConfigField(s.tvdbKey, { s = s.copy(tvdbKey = it) }, "API Key", "", secret = true)
                    ConfigField(s.tvdbPin, { s = s.copy(tvdbPin = it) }, "PIN（可选）", "", secret = true)
                    Spacer(Modifier.height(8.dp))
                    Text("Fanart.tv（标题图、高清背景图）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "在 fanart.tv 注册后到个人页面的「API」申请个人 Key。剧集需要 TheTVDB 编号（TMDB 或 TheTVDB 能查到）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConfigField(s.fanartKey, { s = s.copy(fanartKey = it) }, "API Key", "", secret = true)
                }
            }

            // ---------------- 字幕样式 ----------------
            SectionHeader("字幕样式")
            SurfaceCard {
                Column(Modifier.padding(16.dp)) {
                    Text("显示内容", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    DisplayModeSelector(s.showOriginal, s.showTranslation) { o, t -> s = s.copy(showOriginal = o, showTranslation = t) }

                    Spacer(Modifier.height(16.dp))
                    Text("字号 ${(s.subtitleScale * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = s.subtitleScale, onValueChange = { s = s.copy(subtitleScale = it) }, valueRange = 0.7f..1.8f, steps = 10)
                    Text("离底部 ${s.subtitleOffset}dp", style = MaterialTheme.typography.bodyLarge)
                    Slider(
                        value = s.subtitleOffset.toFloat(), onValueChange = { s = s.copy(subtitleOffset = it.toInt()) },
                        valueRange = 8f..160f,
                    )
                    SubtitlePreview(s)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = "中文译文加拼音",
                    subtitle = when {
                        !Pinyin.available -> "需要 Android 10 及以上"
                        !s.targetIsChinese -> "目标语言是中文时可用"
                        else -> "在每个字上方标注拼音，适合学中文；多音字可能不准"
                    },
                    trailing = {
                        Switch(
                            checked = s.showPinyin, onCheckedChange = { s = s.copy(showPinyin = it) },
                            enabled = Pinyin.available && s.targetIsChinese,
                        )
                    },
                    onClick = { if (Pinyin.available && s.targetIsChinese) s = s.copy(showPinyin = !s.showPinyin) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListRow(
                    title = "字幕列表跟随播放",
                    subtitle = "自动滚动到正在播放的句子",
                    trailing = { Switch(checked = s.followPlayback, onCheckedChange = { s = s.copy(followPlayback = it) }) },
                    onClick = { s = s.copy(followPlayback = !s.followPlayback) },
                )
            }

            // ---------------- 朗读译文 ----------------
            SectionHeader("朗读译文")
            TtsSettings(s, vm) { s = it }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃修改？") },
            text = { Text("设置有改动还没保存。") },
            confirmButton = { TextButton(onClick = { vm.updateSettings(s); onBack() }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("放弃") } },
        )
    }
}

/** 朗读（TTS）设置：开关、引擎、Azure 配置、默认语音、原声音量。 */
@Composable
private fun TtsSettings(s: AppSettings, vm: MainViewModel, onChange: (AppSettings) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    SurfaceCard {
        ListRow(
            title = "播放时朗读译文",
            subtitle = "跟随字幕时间朗读；识别角色后可以给每个角色配不同的语音（播放页「字幕」面板）",
            trailing = { Switch(checked = s.ttsEnabled, onCheckedChange = { onChange(s.copy(ttsEnabled = it)) }) },
            onClick = { onChange(s.copy(ttsEnabled = !s.ttsEnabled)) },
        )
        TtsEngineType.entries.forEach { engine ->
            HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val selected = s.ttsEngine == engine
            ListRow(
                title = engine.label,
                subtitle = engine.hint,
                leading = { RadioButton(selected = selected, onClick = { onChange(s.copy(ttsEngine = engine)) }) },
                onClick = { onChange(s.copy(ttsEngine = engine)) },
            )
            AnimatedVisibility(selected && engine == TtsEngineType.AZURE) {
                ConfigBlock {
                    val asrKey = s.asrConfigs[AsrEngine.AZURE]?.get("key").orEmpty()
                    ConfigField(
                        s.ttsAzureKey, { onChange(s.copy(ttsAzureKey = it)) }, "Speech 资源 Key",
                        if (asrKey.isNotBlank()) "留空则使用语音识别里的 Azure Key" else "", secret = true,
                    )
                    ConfigField(s.ttsAzureRegion, { onChange(s.copy(ttsAzureRegion = it)) }, "区域（Region）", s.ttsAzure().second.ifEmpty { "eastasia" })
                    ConfigField(s.ttsAzureEndpoint, { onChange(s.copy(ttsAzureEndpoint = it)) }, "自定义接口地址（可不填）", "中国区：https://chinanorth3.tts.speech.azure.cn")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ListRow(
            title = "默认语音",
            subtitle = (s.ttsVoices[s.ttsEngine] ?: "自动") + " · 旁白和没识别出角色的台词用它；按目标语言「${s.targetLanguage}」在线获取",
            trailing = { Icon(AppIcons.ArrowForward, null) },
            onClick = { picking = true },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(16.dp)) {
            Text("朗读时原声音量 ${(s.ttsDuckVolume * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
            Slider(value = s.ttsDuckVolume, onValueChange = { onChange(s.copy(ttsDuckVolume = it)) }, valueRange = 0f..1f, steps = 9)
            Text(
                "语音比字幕长时会自动加快（最多 ${TtsReader.MAX_SPEED} 倍）；下一句开始时上一句还没读完会被打断",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (picking) {
        VoicePickerDialog(
            vm, s, "默认语音", s.ttsVoices[s.ttsEngine], allowAuto = true,
            onDismiss = { picking = false },
        ) { v ->
            onChange(s.copy(ttsVoices = if (v == null) s.ttsVoices - s.ttsEngine else s.ttsVoices + (s.ttsEngine to v)))
            picking = false
        }
    }
}

/** 选中服务下方展开的配置区。 */
@Composable
private fun ConfigBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** 输入框；密钥类默认隐藏，可点"显示"查看。 */
@Composable
private fun ConfigField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, secret: Boolean = false) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        singleLine = true,
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) ({ TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") } }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 模型输入框：可以手动填，也可以点右侧箭头从服务商拉取模型列表下拉选择。 */
@Composable
private fun ModelField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    listKey: String,
    fetch: suspend () -> List<String>,
) {
    var models by remember(listKey) { mutableStateOf<List<String>?>(null) }
    var loading by remember(listKey) { mutableStateOf(false) }
    var error by remember(listKey) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                val list = fetch()
                models = list
                if (list.isEmpty()) error = "服务商没有返回可用的模型" else expanded = true
            } catch (e: Exception) {
                error = "获取模型列表失败：${e.message}"
            }
            loading = false
        }
    }
    Box {
        OutlinedTextField(
            value = value, onValueChange = onChange,
            label = { Text("模型") },
            placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
            singleLine = true,
            trailingIcon = {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { if (models.isNullOrEmpty()) load() else expanded = !expanded }) {
                        Icon(if (expanded) AppIcons.ArrowDropUp else AppIcons.ArrowDropDown, "选择模型")
                    }
                }
            },
            supportingText = { Text(error ?: "点右侧箭头从服务商获取模型列表", color = if (error != null) MaterialTheme.colorScheme.error else Color.Unspecified) },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            DropdownMenuItem(
                text = { Text("重新获取", color = MaterialTheme.colorScheme.primary) },
                leadingIcon = { Icon(AppIcons.Refresh, null) },
                onClick = { expanded = false; load() },
            )
            HorizontalDivider()
            models.orEmpty().forEach { m ->
                DropdownMenuItem(
                    text = { Text(m) },
                    trailingIcon = if (m == value) ({ Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { onChange(m); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun TestRow(running: Boolean, result: String?, onTest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onTest, enabled = !running) { Text("测试连接") }
        Spacer(Modifier.size(12.dp))
        if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    if (result != null && !running) {
        Text(
            result, style = MaterialTheme.typography.bodySmall,
            color = if (result.startsWith("连接成功") || result.endsWith("已就绪")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun AsrStatus(engine: AsrEngine, s: AppSettings, vm: MainViewModel, testResult: String?) {
    when {
        testResult != null && testResult.startsWith("连接失败") -> StatusChip("连接失败", StatusKind.ERROR)
        engine == AsrEngine.LOCAL -> StatusChip("免费", StatusKind.OK)
        s.copy(asrEngine = engine).asrProblem() == null -> StatusChip("已配置", StatusKind.OK)
        else -> StatusChip("未配置", StatusKind.NEUTRAL)
    }
}

@Composable
private fun ProviderStatus(p: Provider, s: AppSettings, testResult: String?) {
    when {
        testResult != null && testResult.startsWith("连接失败") -> StatusChip("连接失败", StatusKind.ERROR)
        s.copy(provider = p).translatorProblem() == null -> StatusChip(if (p.needsApiKey) "已配置" else "免费", StatusKind.OK)
        else -> StatusChip("未配置", StatusKind.NEUTRAL)
    }
}

/** 本地模型列表：选择、下载状态、删除。 */
@Composable
private fun LocalModels(s: AppSettings, vm: MainViewModel, onChange: (AppSettings) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    ModelCatalog.all.forEach { m ->
        val installed = remember(version, m.id) { vm.isModelInstalled(m) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = s.localModel == m.id, onClick = { onChange(s.copy(localModel = m.id)) })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(6.dp))
                    if (installed) StatusChip("已下载", StatusKind.OK) else StatusChip("${m.sizeMb}MB", StatusKind.NEUTRAL)
                }
                Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (installed) TextButton(onClick = { vm.deleteModel(m); version++ }) { Text("删除") }
        }
    }
    ConfigField(s.modelBaseUrl, { onChange(s.copy(modelBaseUrl = it)) }, "模型下载地址（可换镜像）", "")
}

/** 双语 / 原文 / 译文 三选一。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayModeSelector(showOriginal: Boolean, showTranslation: Boolean, onChange: (Boolean, Boolean) -> Unit) {
    val modes = listOf(Triple("双语", true, true), Triple("原文", true, false), Triple("译文", false, true))
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (label, o, t) ->
            SegmentedButton(
                selected = showOriginal == o && showTranslation == t,
                onClick = { onChange(o, t) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SubtitlePreview(s: AppSettings) {
    // 按 16:9 视频的大致比例缩小预览，位置随"离底部"同步变化
    Box(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(150.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Color(0xFF263238)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.padding(bottom = (s.subtitleOffset / 2).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (s.showTranslation) {
                if (s.pinyinActive) PinyinText("这是一句翻译后的字幕", Color.White, (18 * s.subtitleScale).sp, center = true)
                else Text("这是一句翻译后的字幕", color = Color.White, fontSize = (18 * s.subtitleScale).sp)
            }
            if (s.showOriginal) Text("This is the original line.", color = Color(0xFFE0E0E0), fontSize = (14 * s.subtitleScale).sp)
        }
    }
}
