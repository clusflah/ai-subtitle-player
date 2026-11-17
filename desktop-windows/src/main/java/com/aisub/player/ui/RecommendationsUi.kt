package com.aisub.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.AppSettings
import com.aisub.player.meta.AiRecommendAngle
import com.aisub.player.meta.AiRecommendedItem
import com.aisub.player.meta.DiscoverItem
import com.aisub.player.meta.RecommendationEngine

/**
 * 影片详情页通用「相似作品推荐」+「AI 智能推荐」组件：
 * 可同时嵌入发现页详情、自定义媒体库详情、网盘/本地文件详情以及 Emby/Plex 媒体服务器详情页。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaRecommendationsSection(
    kind: String,
    title: String,
    originalTitle: String = "",
    year: Int? = null,
    tmdbId: Int? = null,
    doubanId: String? = null,
    genres: List<String> = emptyList(),
    directors: List<String> = emptyList(),
    cast: List<String> = emptyList(),
    overview: String = "",
    recentWatchedTitles: List<String> = emptyList(),
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onSelectWork: (DiscoverItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (title.isBlank()) return
    val context = LocalContext.current
    val tv = LocalTv.current
    val wide = LocalWideLayout.current
    val cardWidth = if (tv) 140.dp else if (wide) 132.dp else 110.dp

    // ---- 1. 相似作品状态 ----
    var similarItems by remember(title, tmdbId, doubanId) { mutableStateOf<List<DiscoverItem>>(emptyList()) }
    var similarLoading by remember(title, tmdbId, doubanId) { mutableStateOf(true) }
    var similarReloadNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(title, originalTitle, year, tmdbId, doubanId, settings.tmdbKey, settings.doubanEnabled, similarReloadNonce) {
        similarLoading = true
        similarItems = runCatching {
            RecommendationEngine.fetchSimilar(
                settings = settings,
                kind = kind,
                title = title,
                originalTitle = originalTitle,
                year = year,
                tmdbId = tmdbId,
                doubanId = doubanId,
                genres = genres,
                forceRefresh = similarReloadNonce > 0,
            )
        }.getOrDefault(emptyList())
        similarLoading = false
    }

    // ---- 2. AI 推荐状态 ----
    val resolvedChat = remember(settings) { RecommendationEngine.resolveChatTranslator(settings) }
    var selectedAngle by remember { mutableStateOf(AiRecommendAngle.SIMILAR_VIBE) }
    var customPrompt by remember { mutableStateOf("") }
    var editingPrompt by remember { mutableStateOf(false) }
    var choosingModel by remember { mutableStateOf(false) }

    var aiItems by remember(title) { mutableStateOf<List<AiRecommendedItem>>(emptyList()) }
    var aiModelLabel by remember(title) { mutableStateOf(resolvedChat?.first.orEmpty()) }
    var aiLoading by remember(title) { mutableStateOf(false) }
    var aiError by remember(title) { mutableStateOf<String?>(null) }
    var aiTriggerNonce by remember(title) { mutableIntStateOf(if (resolvedChat != null) 1 else 0) }
    var seenAiTitles by remember(title) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(title, selectedAngle, aiTriggerNonce, settings.aiRecommendProvider, settings.provider, settings.providerConfigs) {
        if (aiTriggerNonce <= 0) return@LaunchedEffect
        if (RecommendationEngine.resolveChatTranslator(settings) == null) {
            aiError = "尚未配置 AI 大模型密钥"
            return@LaunchedEffect
        }
        aiLoading = true
        aiError = null
        runCatching {
            RecommendationEngine.fetchAiRecommendations(
                context = context,
                settings = settings,
                kind = kind,
                title = title,
                originalTitle = originalTitle,
                year = year,
                genres = genres,
                directors = directors,
                cast = cast,
                overview = overview,
                recentWatchedTitles = recentWatchedTitles,
                angle = selectedAngle,
                customRequirement = customPrompt,
                excludeTitles = if (aiTriggerNonce > 1) seenAiTitles else emptyList(),
                forceRefresh = aiTriggerNonce > 1,
            )
        }.onSuccess { (label, list) ->
            aiModelLabel = label
            aiItems = list
            seenAiTitles = (seenAiTitles + list.map { it.item.title }).distinct()
        }.onFailure { e ->
            aiError = e.message ?: "AI 推荐请求失败"
        }
        aiLoading = false
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ==================== AI 智能推荐 ====================
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(AppIcons.AutoAwesome, null, tint = Orange, modifier = Modifier.size(19.dp))
                    Column(Modifier.weight(1f)) {
                        Text("AI 智能推荐", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (aiModelLabel.isNotBlank()) {
                            Text(
                                aiModelLabel,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TextButton(
                        onClick = { editingPrompt = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(if (customPrompt.isBlank()) "定制偏好" else "已设偏好", fontSize = 12.sp)
                    }
                    if (onUpdateSettings != null) {
                        IconButton(onClick = { choosingModel = true }, modifier = Modifier.size(30.dp)) {
                            Icon(AppIcons.Settings, "切换推荐模型", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (resolvedChat != null) {
                        IconButton(
                            onClick = { if (!aiLoading) aiTriggerNonce++ },
                            enabled = !aiLoading,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(AppIcons.Refresh, "换一批推荐", modifier = Modifier.size(17.dp), tint = Orange)
                        }
                    }
                }

                // 推荐角度切换胶囊
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiRecommendAngle.entries.forEach { angle ->
                        FilterChip(
                            selected = selectedAngle == angle,
                            onClick = {
                                selectedAngle = angle
                                if (aiTriggerNonce == 0 && resolvedChat != null) aiTriggerNonce = 1
                            },
                            label = { Text(angle.label, fontSize = 12.sp) },
                        )
                    }
                }

                if (customPrompt.isNotBlank()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "偏好：$customPrompt",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            AppIcons.Close,
                            "清除偏好",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    customPrompt = ""
                                    if (resolvedChat != null) aiTriggerNonce++
                                },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                when {
                    resolvedChat == null -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                "配置任意大模型（DeepSeek / Gemini / 通义千问 / 豆包 / 本地 Ollama）即可启用结合本片看点与个人口味的 AI 深度荐片。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    if (onUpdateSettings != null) choosingModel = true
                                    else onOpenSettings?.invoke()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text("配置 AI", fontSize = 12.sp)
                            }
                        }
                    }
                    aiLoading -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Orange)
                            Spacer(Modifier.width(10.dp))
                            Text("AI 正在分析《$title》并挑选片单…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    aiError != null && aiItems.isEmpty() -> {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                aiError!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { aiTriggerNonce++ }) {
                                Text("重试", fontSize = 12.sp)
                            }
                        }
                    }
                    aiItems.isEmpty() -> {
                        Button(
                            onClick = { aiTriggerNonce++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Color.White),
                        ) {
                            Icon(AppIcons.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("生成 AI 专属推荐")
                        }
                    }
                    else -> {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(aiItems, key = { it.item.id + it.reason.hashCode() }) { rec ->
                                AiRecommendCard(
                                    rec = rec,
                                    width = if (wide || tv) 256.dp else 228.dp,
                                    onClick = { onSelectWork(rec.item) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==================== 相似作品推荐 ====================
        SurfaceCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "相似作品推荐",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (!similarLoading) {
                        IconButton(onClick = { similarReloadNonce++ }, modifier = Modifier.size(28.dp)) {
                            Icon(AppIcons.Refresh, "刷新相似作品", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                when {
                    similarLoading -> {
                        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    similarItems.isEmpty() -> {
                        Text(
                            "暂未检索到同词条关联的相似作品，可使用上方 AI 智能推荐深度挖掘。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                    }
                    else -> {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(similarItems, key = { it.id }) { sim ->
                                DiscoverPosterCard(
                                    item = sim,
                                    width = cardWidth,
                                    onClick = { onSelectWork(sim) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingPrompt) {
        TextInputDialog(
            title = "定制 AI 推荐要求",
            label = "例如：节奏紧凑、结局治愈、2015年以后、不要恐怖元素",
            initial = customPrompt,
            confirm = "应用并推荐",
            onDismiss = { editingPrompt = false },
            onConfirm = { input ->
                customPrompt = input
                editingPrompt = false
                if (RecommendationEngine.resolveChatTranslator(settings) != null) {
                    aiTriggerNonce++
                }
            },
        )
    }

    if (choosingModel && onUpdateSettings != null) {
        TranslateModelDialog(
            settings = settings,
            title = "选择 AI 推荐模型",
            onDismiss = { choosingModel = false },
            forAiRecommend = true,
        ) { updated ->
            onUpdateSettings(updated)
            choosingModel = false
            aiTriggerNonce++
        }
    }
}

@Composable
private fun AiRecommendCard(
    rec: AiRecommendedItem,
    width: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val item = rec.item
    Row(
        Modifier
            .width(width)
            .tvFocusRing(RoundedCornerShape(12.dp), scale = true)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(68.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            NetImage(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                maxSize = 320,
                contentScale = ContentScale.Crop,
                fallback = AppIcons.Movie,
            )
            rec.inLibraryName?.let {
                Text(
                    "库内可播",
                    color = Color.White,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Orange)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    buildString {
                        append(if (item.kind == "tv") "剧集" else "电影")
                        item.year?.let { append(" · $it") }
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.rating?.let { r ->
                    Text(
                        "★ %.1f".format(r),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Orange,
                    )
                }
            }
            Text(
                rec.reason,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
