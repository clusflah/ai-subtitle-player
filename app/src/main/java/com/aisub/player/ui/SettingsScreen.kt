package com.aisub.player.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.AppSettings
import com.aisub.player.AsrEngine
import com.aisub.player.MainViewModel
import com.aisub.player.Provider
import com.aisub.player.ProviderConfig
import com.aisub.player.asr.AsrLanguages
import com.aisub.player.asr.ModelCatalog
import com.aisub.player.asrLanguageWarning
import com.aisub.player.subtitle.Pinyin
import com.aisub.player.translate.ModelList
import com.aisub.player.translate.OfflineTranslator
import com.aisub.player.tts.TtsEngineType
import com.aisub.player.tts.TtsReader
import com.aisub.player.asrProblem
import com.aisub.player.streamingAvailable
import com.aisub.player.supportsStreaming
import com.aisub.player.withAsrValue
import com.aisub.player.asrValue
import com.aisub.player.GEMINI_STREAMING_NOTE
import kotlinx.coroutines.launch

/** 离线翻译：没有要填的，只说明用法；没装时给下载地址。 */
@Composable
private fun OfflineTranslatorInfo() {
    val context = LocalContext.current
    val installed = remember { OfflineTranslator.isInstalled(context) }
    Text(
        if (installed) "已安装。语言包在 Offline Translator 里下载（中文、英语、日语等，每个几十 MB）；原文语言按「语音识别」里选的视频语言，选「自动」时由它自己判断"
        else "还没有安装。它是开源 App，下载安装后在里面下载要用的语言包（原文语言和目标语言都要）",
        style = MaterialTheme.typography.bodySmall,
        color = if (installed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
    )
    if (!installed) {
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/DavidVentura/offline-translator/releases")))
            }
        }) { Text("去下载 Offline Translator") }
    }
}

/** 本地模型常见的服务地址（在手机上运行时） */
private val LOCAL_LLM_SERVERS = listOf(
    "Ollama" to "http://127.0.0.1:11434/v1",
    "LM Studio" to "http://127.0.0.1:1234/v1",
    "llama.cpp" to "http://127.0.0.1:8080/v1",
)

private val PROVIDER_HINTS = mapOf(
    Provider.CLAUDE to "Anthropic 官方 API，翻译质量高",
    Provider.DEEPSEEK to "性价比高，国内可直连",
    Provider.GEMINI to "Google AI Studio 的 API Key",
    Provider.INDEX_TRANSLATE to "B 站开源翻译模型，公共接口免费、无需 Key（预览版）",
    Provider.ALIYUN to "通义千问，国内直连、速度快；API Key 在百炼控制台获取",
    Provider.VOLC to "豆包，国内直连、速度快；模型一栏可填模型名或接入点 ID（ep-…）",
    Provider.AZURE_TRANSLATOR to "机器翻译：最快、最便宜，译文偏直译；区域填资源所在区域，如 eastasia，全局资源填 global",
    Provider.LOCAL to "Ollama、LM Studio、llama.cpp 等在本机或局域网电脑上运行的模型，免费、不用 Key，字幕不出本地。" +
        "电脑上运行时把地址改成 http://电脑IP:11434/v1（LM Studio 是 1234 端口），Ollama 要设置 OLLAMA_HOST=0.0.0.0",
    Provider.OFFLINE_TRANSLATOR to "另外安装的开源 App，在手机上运行 Firefox 翻译用的 Bergamot 模型：完全离线、免费、最快，字幕不会离开手机；译文偏直译，不能识别角色",
    Provider.OPENAI_COMPAT to "兼容 OpenAI 接口的服务：Kimi、OpenRouter 等",
)

/** 语音识别里能从服务商获取模型列表的引擎 */
private val ASR_MODEL_LISTS = setOf(AsrEngine.GEMINI, AsrEngine.OPENAI)

private fun Set<String>.toggle(key: String) = if (key in this) this - key else this + key

/** 服务行尾的箭头：配置项展开时朝上，收起时朝下 */
@Composable
private fun FoldArrow(expanded: Boolean) {
    Icon(
        if (expanded) AppIcons.ArrowDropUp else AppIcons.ExpandMore, if (expanded) "收起" else "展开设置",
        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp).size(20.dp),
    )
}

internal val TARGET_LANGUAGES = listOf("简体中文", "繁體中文", "English", "日本語", "한국어")

/**
 * 设置页：按工作流程分组（语音识别 → 字幕翻译 → 字幕样式…），每组可折叠，收起时显示当前配置摘要；
 * 组内只展开当前选中服务的配置项；每个服务可单独测试连接。改动即时保存，不用点「保存」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val initial = remember { vm.state.value.settings }
    var s by remember { mutableStateOf(initial) }
    // 改了就存（输入框连续打字时稍等一下再存）
    LaunchedEffect(s) {
        if (s != vm.state.value.settings) {
            kotlinx.coroutines.delay(400)
            vm.updateSettings(s)
        }
    }
    val leave = {
        if (s != vm.state.value.settings) vm.updateSettings(s)
        onBack()
    }
    BackHandler(onBack = leave)

    // 选中的服务再点一下收起它的配置项（键同测试结果），换一个服务时自动展开
    // 点服务这一行只展开 / 收起它的设置，选中用左边的圆钮：设置和选用互不影响。一开始展开选中的那个
    var expanded by remember { mutableStateOf(setOf("asr:${initial.asrEngine.name}", "tr:${initial.provider.name}")) }
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
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp, bottom = 32.dp)) {

            // ---------------- 语音识别 ----------------
            val asrProblem = s.asrProblem()
            CollapsibleCard(
                "语音识别",
                s.asrEngine.label + " · 视频语言：" + AsrLanguages.label(s.asrLanguage) +
                    (asrProblem?.let { " · $it" } ?: ""),
                warn = asrProblem != null,
            ) {
                AsrEngine.entries.forEachIndexed { i, engine ->
                    if (i > 0) CardDivider(inset = 64.dp)
                    val selected = s.asrEngine == engine
                    val key = "asr:${engine.name}"
                    ListRow(
                        title = engine.label,
                        subtitle = engine.hint,
                        subtitleMaxLines = if (key in expanded) Int.MAX_VALUE else 2,
                        leading = { RadioButton(selected = selected, onClick = { s = s.copy(asrEngine = engine) }) },
                        trailing = { Row(verticalAlignment = Alignment.CenterVertically) { AsrStatus(engine, s, vm, testResults[key]); FoldArrow(key in expanded) } },
                        onClick = { expanded = expanded.toggle(key) },
                    )
                    AnimatedVisibility(key in expanded) {
                        ConfigBlock {
                            if (engine == AsrEngine.LOCAL) {
                                LocalModels(s, vm) { s = it }
                            } else {
                                engine.fields.forEach { f ->
                                    if ((f.key == "model" || f.key == "liveModel") && engine in ASR_MODEL_LISTS) {
                                        val live = f.key == "liveModel"
                                        val apiKey = s.asrValue(engine, "apiKey")
                                        val endpoint = s.asrValue(engine, if (engine == AsrEngine.GEMINI) "endpoint" else "baseUrl")
                                        ModelField(
                                            s.asrConfigs[engine]?.get(f.key) ?: "", { s = s.withAsrValue(engine, f.key, it) },
                                            f.placeholder.ifEmpty { f.default },
                                            listKey = "${engine.name}|$live|$endpoint|$apiKey",
                                            fetch = { ModelList.fetchAsr(engine, apiKey, endpoint, live) },
                                            label = f.label,
                                        )
                                    } else {
                                        ConfigField(
                                            value = s.asrConfigs[engine]?.get(f.key) ?: "",
                                            onChange = { s = s.withAsrValue(engine, f.key, it) },
                                            label = f.label,
                                            placeholder = f.placeholder.ifEmpty { f.default },
                                            secret = f.secret,
                                        )
                                    }
                                }
                                // 电视：Key 这类长串用手机粘贴
                                PhoneInputButton(
                                    engine.fields.map { f -> PhoneField(f.key, f.label, s.asrConfigs[engine]?.get(f.key).orEmpty(), f.secret) },
                                    title = engine.label,
                                ) { v -> s = v.entries.fold(s) { acc, (k, value) -> acc.withAsrValue(engine, k, value) } }
                                TestRow(testing[key] == true, testResults[key]) { runTest(key) { vm.testAsr(s.copy(asrEngine = engine)) } }
                            }
                        }
                    }
                }
                CardDivider()
                ListRow(
                    title = "流式识别（直播和远程视频）",
                    subtitle = when {
                        !s.asrEngine.supportsStreaming -> "支持 Azure、火山豆包、阿里、Gemini；当前引擎照用分段识别"
                        !s.streamingAvailable -> "Azure 流式识别要在下面选定视频语言，选「自动」时照用分段识别"
                        s.asrEngine == AsrEngine.GEMINI -> "打开后直播和网盘、NAS 上的视频边播边识别，用上面填的 live 模型。注意：$GEMINI_STREAMING_NOTE；" +
                            "也不能识别的同时翻译，要翻译得在「字幕翻译」里另选服务。关闭时照用分段识别"
                        else -> "打开后直播和网盘、NAS 上的视频边播边识别，一两秒出字；需要在控制台开通对应的流式（实时）语音识别。关闭时照用分段识别"
                    },
                    trailing = { Switch(checked = s.streamingAsr, onCheckedChange = { s = s.copy(streamingAsr = it) }) },
                    onClick = { s = s.copy(streamingAsr = !s.streamingAsr) },
                )
                CardDivider()
                Column(Modifier.padding(16.dp)) {
                    Text("视频语言", style = MaterialTheme.typography.bodyLarge)
                    Text("选错会降低准确率；不确定时选「自动」", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var custom by remember { mutableStateOf(!AsrLanguages.isCommon(s.asrLanguage)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsrLanguages.COMMON.forEach { (code, label) ->
                            FilterChip(
                                selected = !custom && s.asrLanguage == code,
                                onClick = { custom = false; s = s.copy(asrLanguage = code) },
                                label = { Text(label) },
                            )
                        }
                        FilterChip(selected = custom, onClick = { custom = true }, label = { Text("自定义…") })
                    }
                    if (custom) {
                        var text by remember { mutableStateOf(if (AsrLanguages.isCommon(s.asrLanguage)) "" else s.asrLanguage) }
                        val parsed = AsrLanguages.normalize(text)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { v ->
                                text = v
                                AsrLanguages.normalize(v)?.let { s = s.copy(asrLanguage = it) }
                            },
                            label = { Text("语言代码") },
                            placeholder = { Text("如 sv、cs、he，可带地区：pt-BR、es-MX") },
                            supportingText = {
                                Text(
                                    when {
                                        text.isBlank() -> "ISO 639-1 两位字母代码（瑞典语 sv、捷克语 cs、希伯来语 he、马来语 ms…）"
                                        parsed == null -> "格式不对：两三位字母，可加「-地区」"
                                        else -> "识别为：" + AsrLanguages.label(parsed)
                                    },
                                )
                            },
                            isError = text.isNotBlank() && parsed == null,
                            singleLine = true,
                            colors = appFieldColors(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    s.asrLanguageWarning()?.let { warn ->
                        Text(warn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            // ---------------- 字幕翻译 ----------------
            val trProblem = s.translatorProblem()
            CollapsibleCard(
                "字幕翻译",
                "翻译成${s.targetLanguage} · ${s.provider.label}" + (if (s.autoTranslate) " · 识别时自动翻译" else "") +
                    (trProblem?.let { " · $it" } ?: ""),
                warn = trProblem != null,
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = s.targetLanguage, onValueChange = { s = s.copy(targetLanguage = it) },
                        label = { Text("目标语言") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.small, colors = appFieldColors(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TARGET_LANGUAGES.forEach { lang ->
                            FilterChip(selected = s.targetLanguage == lang, onClick = { s = s.copy(targetLanguage = lang) }, label = { Text(lang) })
                        }
                    }
                }
                CardDivider()
                ListRow(
                    title = "识别时自动翻译",
                    subtitle = "边识别边翻译，攒够 20 句发一次请求",
                    trailing = { Switch(checked = s.autoTranslate, onCheckedChange = { s = s.copy(autoTranslate = it) }) },
                    onClick = { s = s.copy(autoTranslate = !s.autoTranslate) },
                )
                CardDivider()
                ListRow(
                    title = "在视频旁保存字幕文件",
                    subtitle = "识别、翻译或编辑后，在本地视频的文件夹里存一个同名的双语 .srt，别的播放器打开视频也能显示；网络视频不保存",
                    trailing = { Switch(checked = s.autoSaveSrt, onCheckedChange = { s = s.copy(autoSaveSrt = it) }) },
                    onClick = { s = s.copy(autoSaveSrt = !s.autoSaveSrt) },
                )
                CardDivider()
                Provider.entries.forEachIndexed { i, p ->
                    if (i > 0) CardDivider(inset = 64.dp)
                    val selected = s.provider == p
                    val key = "tr:${p.name}"
                    ListRow(
                        title = p.label,
                        subtitle = PROVIDER_HINTS[p],
                        subtitleMaxLines = if (key in expanded) Int.MAX_VALUE else 2,
                        leading = { RadioButton(selected = selected, onClick = { s = s.copy(provider = p) }) },
                        trailing = { Row(verticalAlignment = Alignment.CenterVertically) { ProviderStatus(p, s, testResults[key]); FoldArrow(key in expanded) } },
                        onClick = { expanded = expanded.toggle(key) },
                    )
                    AnimatedVisibility(key in expanded) {
                        ConfigBlock {
                            val raw = s.providerConfigs[p] ?: ProviderConfig()
                            if (p.editableBaseUrl) {
                                ConfigField(raw.baseUrl, { s = s.withConfig(p, raw.copy(baseUrl = it)) }, "Base URL", p.defaultBaseUrl)
                                if (p == Provider.LOCAL) {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        LOCAL_LLM_SERVERS.forEach { (label, url) ->
                                            FilterChip(
                                                selected = s.configOf(p).baseUrl == url,
                                                onClick = { s = s.withConfig(p, raw.copy(baseUrl = url)) },
                                                label = { Text(label) },
                                            )
                                        }
                                    }
                                }
                            }
                            if (p == Provider.OFFLINE_TRANSLATOR) {
                                OfflineTranslatorInfo()
                            } else {
                                ConfigField(
                                    raw.apiKey, { s = s.withConfig(p, raw.copy(apiKey = it)) },
                                    if (p.needsApiKey) "API Key" else "API Key（可不填）", "", secret = true,
                                )
                                val cfg = s.configOf(p)
                                // Azure 机器翻译没有模型，这一栏存的是资源区域
                                if (p == Provider.AZURE_TRANSLATOR) ConfigField(
                                    raw.model, { s = s.withConfig(p, raw.copy(model = it)) }, "区域（Region）",
                                    p.defaultModel.ifEmpty { "eastasia；全局资源填 global" },
                                ) else ModelField(
                                    raw.model, { s = s.withConfig(p, raw.copy(model = it)) },
                                    p.defaultModel.ifEmpty { if (p == Provider.LOCAL) "例如 qwen3:4b、gemma3:4b" else "例如 qwen-plus、moonshot-v1-8k" },
                                    // Key 或地址变了，之前拉到的列表作废
                                    listKey = "${p.name}|${cfg.baseUrl}|${cfg.apiKey}",
                                    fetch = { ModelList.fetch(p, cfg) },
                                )
                            }
                            if (p != Provider.OFFLINE_TRANSLATOR) {
                                PhoneInputButton(
                                    listOfNotNull(
                                        PhoneField("baseUrl", "Base URL", raw.baseUrl).takeIf { p.editableBaseUrl },
                                        PhoneField("apiKey", "API Key", raw.apiKey, secret = true),
                                        PhoneField("model", if (p == Provider.AZURE_TRANSLATOR) "区域（Region）" else "模型", raw.model),
                                    ),
                                    title = p.label,
                                ) { v ->
                                    val c = s.providerConfigs[p] ?: ProviderConfig()
                                    s = s.withConfig(p, c.copy(baseUrl = v["baseUrl"] ?: c.baseUrl, apiKey = v["apiKey"] ?: c.apiKey, model = v["model"] ?: c.model))
                                }
                            }
                            TestRow(testing[key] == true, testResults[key]) { runTest(key) { vm.testTranslator(s.copy(provider = p)) } }
                        }
                    }
                }
            }

            // ---------------- 在线字幕 ----------------
            CollapsibleCard(
                "在线字幕",
                listOf("射手网" to s.assrtToken, "OpenSubtitles" to s.osApiKey)
                    .joinToString(" · ") { (name, key) -> name + if (key.isBlank()) " 未配置" else " 已配置" },
            ) {
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
                    PhoneInputButton(
                        listOf(
                            PhoneField("assrt", "射手网 API Token", s.assrtToken, secret = true),
                            PhoneField("osKey", "OpenSubtitles API Key", s.osApiKey, secret = true),
                            PhoneField("osUser", "OpenSubtitles 用户名（可选）", s.osUsername),
                            PhoneField("osPass", "OpenSubtitles 密码（可选）", s.osPassword, secret = true),
                        ),
                        title = "在线字幕",
                    ) { v ->
                        s = s.copy(
                            assrtToken = v["assrt"] ?: s.assrtToken, osApiKey = v["osKey"] ?: s.osApiKey,
                            osUsername = v["osUser"] ?: s.osUsername, osPassword = v["osPass"] ?: s.osPassword,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("zh-cn,en" to "简中 + 英", "zh-cn,zh-tw" to "简中 + 繁中", "en" to "仅英文", "zh-cn,en,ja" to "简中 + 英 + 日").forEach { (v, label) ->
                            FilterChip(selected = s.subtitleLanguages == v, onClick = { s = s.copy(subtitleLanguages = v) }, label = { Text(label) })
                        }
                    }
                }
            }

            // ---------------- 影视信息 ----------------
            CollapsibleCard(
                "影视信息",
                "主数据源：" + (com.aisub.player.meta.MetaSource.entries.firstOrNull { it.name == s.metaPrimary }?.label ?: s.metaPrimary) +
                    " · 已开启：" + listOfNotNull(
                        "TMDB".takeIf { s.tmdbKey.isNotBlank() }, "豆瓣".takeIf { s.doubanEnabled }, "Bangumi".takeIf { s.bangumiEnabled },
                        "TheTVDB".takeIf { s.tvdbKey.isNotBlank() }, "Fanart".takeIf { s.fanartKey.isNotBlank() },
                    ).ifEmpty { listOf("无") }.joinToString("、"),
            ) {
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
                CardDivider()
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
                    PhoneInputButton(
                        listOf(
                            PhoneField("key", "API Key", s.tmdbKey, secret = true),
                            PhoneField("base", "接口地址（可选）", s.tmdbBaseUrl),
                            PhoneField("image", "图片地址（可选）", s.tmdbImageBase),
                        ),
                        title = "TMDB",
                    ) { v -> s = s.copy(tmdbKey = v["key"] ?: s.tmdbKey, tmdbBaseUrl = v["base"] ?: s.tmdbBaseUrl, tmdbImageBase = v["image"] ?: s.tmdbImageBase) }
                }
                CardDivider()
                ListRow(
                    title = "豆瓣",
                    subtitle = "评分、中文简介、海报、演职员，不用 Key；非官方接口，可能被限流",
                    trailing = { Switch(checked = s.doubanEnabled, onCheckedChange = { s = s.copy(doubanEnabled = it) }) },
                    onClick = { s = s.copy(doubanEnabled = !s.doubanEnabled) },
                )
                CardDivider()
                ListRow(
                    title = "Bangumi 番组计划",
                    subtitle = "动画的评分、简介和分集标题，不用 Key",
                    trailing = { Switch(checked = s.bangumiEnabled, onCheckedChange = { s = s.copy(bangumiEnabled = it) }) },
                    onClick = { s = s.copy(bangumiEnabled = !s.bangumiEnabled) },
                )
                CardDivider()
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
                    PhoneInputButton(
                        listOf(
                            PhoneField("tvdbKey", "TheTVDB API Key", s.tvdbKey, secret = true),
                            PhoneField("tvdbPin", "TheTVDB PIN（可选）", s.tvdbPin, secret = true),
                            PhoneField("fanart", "Fanart.tv API Key", s.fanartKey, secret = true),
                        ),
                        title = "TheTVDB 与 Fanart.tv",
                    ) { v -> s = s.copy(tvdbKey = v["tvdbKey"] ?: s.tvdbKey, tvdbPin = v["tvdbPin"] ?: s.tvdbPin, fanartKey = v["fanart"] ?: s.fanartKey) }
                }
            }

            // ---------------- 字幕样式 ----------------
            CollapsibleCard(
                "字幕样式",
                when {
                    s.showOriginal && s.showTranslation -> "双语"
                    s.showOriginal -> "只显示原文"
                    else -> "只显示译文"
                } + " · 字号 ${(s.subtitleScale * 100).toInt()}%" + (if (s.pinyinActive) " · 拼音" else ""),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("显示内容", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    DisplayModeSelector(s.showOriginal, s.showTranslation) { o, t -> s = s.copy(showOriginal = o, showTranslation = t) }

                    Spacer(Modifier.height(8.dp))
                    SubtitleStyleEditor(s) { s = it }
                }
                CardDivider()
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
                CardDivider()
                ListRow(
                    title = "字幕列表跟随播放",
                    subtitle = "自动滚动到正在播放的句子",
                    trailing = { Switch(checked = s.followPlayback, onCheckedChange = { s = s.copy(followPlayback = it) }) },
                    onClick = { s = s.copy(followPlayback = !s.followPlayback) },
                )
            }

            // ---------------- 播放 ----------------
            CollapsibleCard(
                "播放",
                listOfNotNull(
                    s.preferredAudioLanguages.takeIf { it.isNotEmpty() }?.let { "音轨 " + (LANGUAGE_CHOICES.firstOrNull { c -> c.first == it }?.second ?: it) },
                    s.preferredSubtitleLanguages.takeIf { it.isNotEmpty() }?.let { "字幕 " + (LANGUAGE_CHOICES.firstOrNull { c -> c.first == it }?.second ?: it) },
                    if (s.autoSkip) "自动跳过片头片尾" else null,
                ).joinToString(" · ").ifEmpty { "音轨、字幕选择与片头片尾" },
            ) {
                LanguageRow("首选音轨语言", s.preferredAudioLanguages) { s = s.copy(preferredAudioLanguages = it) }
                CardDivider()
                LanguageRow("首选视频自带字幕语言", s.preferredSubtitleLanguages) { s = s.copy(preferredSubtitleLanguages = it) }
                CardDivider()
                ListRow(
                    title = "已有 AI / 外挂字幕时不开自带字幕",
                    subtitle = "视频已经识别、导入或下载过字幕时，自带字幕保持关闭，避免两套字幕叠在一起",
                    trailing = { Switch(checked = s.preferLoadedSubtitles, onCheckedChange = { s = s.copy(preferLoadedSubtitles = it) }) },
                    onClick = { s = s.copy(preferLoadedSubtitles = !s.preferLoadedSubtitles) },
                )
                CardDivider()
                ListRow(
                    title = "自动跳过片头片尾",
                    subtitle = "关掉后播到片头片尾只显示「跳过」按钮。在播放页「更多 → 片头片尾与跳过」里标记或管理",
                    trailing = { Switch(checked = s.autoSkip, onCheckedChange = { s = s.copy(autoSkip = it) }) },
                    onClick = { s = s.copy(autoSkip = !s.autoSkip) },
                )
                CardDivider()
                ListRow(
                    title = "自动识别片头片尾",
                    subtitle = "从文件夹播放本地剧集时，和同一部剧的另一集比对声音找出片头片尾（每集只比一次）；网络文件流量大，需在播放页手动开始",
                    trailing = { Switch(checked = s.autoDetectIntro, onCheckedChange = { s = s.copy(autoDetectIntro = it) }) },
                    onClick = { s = s.copy(autoDetectIntro = !s.autoDetectIntro) },
                )
                CardDivider()
                ListRow(
                    title = "音乐后台播放",
                    subtitle = "播放音乐时回桌面、切到别的应用或锁屏，继续播放；通知栏、锁屏、耳机按键可以暂停和切歌",
                    trailing = { Switch(checked = s.musicBackground, onCheckedChange = { s = s.copy(musicBackground = it) }) },
                    onClick = { s = s.copy(musicBackground = !s.musicBackground) },
                )
                CardDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("播放视频时离开应用", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "回桌面或切到别的应用时：暂停；继续播放声音（锁屏也继续）；或者缩成悬浮窗继续看，悬浮窗可拖动、双击放大",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.aisub.player.VideoLeaveAction.entries.forEach { a ->
                            FilterChip(selected = s.videoLeaveAction == a, onClick = { s = s.copy(videoLeaveAction = a) }, label = { Text(a.label) })
                        }
                    }
                }
                CardDivider()
                ListRow(
                    title = "音乐自动在线获取歌词和封面",
                    subtitle = "歌曲没有内嵌歌词、封面时，按歌名和歌手到 LRCLIB、网易云音乐、iTunes 查找（会把歌名、歌手发给这些网站）",
                    trailing = { Switch(checked = s.musicOnlineLookup, onCheckedChange = { s = s.copy(musicOnlineLookup = it) }) },
                    onClick = { s = s.copy(musicOnlineLookup = !s.musicOnlineLookup) },
                )
            }

            // ---------------- 视频下载（下载版） ----------------
            if (vm.linkDownloader != null) {
                CollapsibleCard(
                    "视频下载",
                    "最高画质：" + (if (s.dlQuality > 0) "${s.dlQuality}p" else "不限") +
                        (if (s.dlAutoAsr) " · 没字幕时 AI 识别" else "") +
                        vm.cookieJar.sites().let { if (it.isEmpty()) "" else " · 已登录 " + it.joinToString("、") },
                ) {
                LinkDownloadSettings(
                    quality = s.dlQuality, autoAsr = s.dlAutoAsr,
                    onQuality = { s = s.copy(dlQuality = it) }, onAutoAsr = { s = s.copy(dlAutoAsr = it) },
                    vm = vm,
                )
                }
            }

            // ---------------- 朗读译文 ----------------
            CollapsibleCard(
                "朗读译文",
                if (s.ttsEnabled) "开启 · ${s.ttsEngine.label} · 默认语音：${s.ttsVoices[s.ttsEngine] ?: "自动"}" else "关闭",
            ) {
                TtsSettings(s, vm) { s = it }
            }
        }
    }
}

/** 朗读（TTS）设置：开关、引擎、Azure 配置、默认语音、原声音量。 */
@Composable
private fun TtsSettings(s: AppSettings, vm: MainViewModel, onChange: (AppSettings) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column {
        ListRow(
            title = "播放时朗读译文",
            subtitle = "跟随字幕时间朗读；识别角色后可以给每个角色配不同的语音（播放页「字幕」面板）",
            trailing = { Switch(checked = s.ttsEnabled, onCheckedChange = { onChange(s.copy(ttsEnabled = it)) }) },
            onClick = { onChange(s.copy(ttsEnabled = !s.ttsEnabled)) },
        )
        var ttsExpanded by remember { mutableStateOf(s.ttsEngine == TtsEngineType.AZURE) }
        TtsEngineType.entries.forEach { engine ->
            CardDivider(inset = 64.dp)
            val selected = s.ttsEngine == engine
            val hasConfig = engine == TtsEngineType.AZURE
            ListRow(
                title = engine.label,
                subtitle = engine.hint,
                leading = { RadioButton(selected = selected, onClick = { onChange(s.copy(ttsEngine = engine)) }) },
                trailing = if (hasConfig) ({ FoldArrow(ttsExpanded) }) else null,
                // 有设置的（Azure）点这一行展开设置；没有设置的点这一行就是选用
                onClick = { if (hasConfig) ttsExpanded = !ttsExpanded else onChange(s.copy(ttsEngine = engine)) },
            )
            AnimatedVisibility(hasConfig && ttsExpanded) {
                ConfigBlock {
                    val asrKey = s.asrConfigs[AsrEngine.AZURE]?.get("key").orEmpty()
                    ConfigField(
                        s.ttsAzureKey, { onChange(s.copy(ttsAzureKey = it)) }, "Speech 资源 Key",
                        if (asrKey.isNotBlank()) "留空则使用语音识别里的 Azure Key" else "", secret = true,
                    )
                    ConfigField(s.ttsAzureRegion, { onChange(s.copy(ttsAzureRegion = it)) }, "区域（Region）", s.ttsAzure().second.ifEmpty { "eastasia" })
                    ConfigField(s.ttsAzureEndpoint, { onChange(s.copy(ttsAzureEndpoint = it)) }, "自定义接口地址（可不填）", "中国区：https://chinanorth3.tts.speech.azure.cn")
                    PhoneInputButton(
                        listOf(
                            PhoneField("key", "Speech 资源 Key", s.ttsAzureKey, secret = true),
                            PhoneField("region", "区域（Region）", s.ttsAzureRegion),
                            PhoneField("endpoint", "自定义接口地址（可不填）", s.ttsAzureEndpoint),
                        ),
                        title = "Azure 朗读",
                    ) { v ->
                        onChange(s.copy(ttsAzureKey = v["key"] ?: s.ttsAzureKey, ttsAzureRegion = v["region"] ?: s.ttsAzureRegion, ttsAzureEndpoint = v["endpoint"] ?: s.ttsAzureEndpoint))
                    }
                }
            }
        }
        CardDivider()
        ListRow(
            title = "默认语音",
            subtitle = (s.ttsVoices[s.ttsEngine] ?: "自动") + " · 旁白和没识别出角色的台词用它；按目标语言「${s.targetLanguage}」在线获取",
            trailing = { Icon(AppIcons.ArrowForward, null) },
            onClick = { picking = true },
        )
        CardDivider()
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
        shape = MaterialTheme.shapes.small,
        colors = appFieldColors(),
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
    label: String = "模型",
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
            label = { Text(label) },
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
            shape = MaterialTheme.shapes.small,
            colors = appFieldColors(),
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

/** 首选语言：常用语言里选一个，"跟随视频"为不指定 */
private val LANGUAGE_CHOICES = listOf(
    "" to "跟随视频默认", "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语",
    "fr" to "法语", "de" to "德语", "es" to "西班牙语", "ru" to "俄语",
)

@Composable
private fun LanguageRow(title: String, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = LANGUAGE_CHOICES.firstOrNull { it.first == value }?.second ?: value
    Box {
        ListRow(title = title, subtitle = label, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LANGUAGE_CHOICES.forEach { (code, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onChange(code); open = false })
            }
        }
    }
}
