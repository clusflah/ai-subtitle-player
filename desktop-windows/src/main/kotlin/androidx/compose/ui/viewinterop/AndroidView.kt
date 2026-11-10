package androidx.compose.ui.viewinterop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aisub.player.desktop.DesktopPlayerEngine
import kotlin.math.roundToInt

@Composable
fun <T : View> AndroidView(
    factory: (Context) -> T,
    modifier: Modifier = Modifier,
    onReset: ((T) -> Unit)? = null,
    onRelease: (T) -> Unit = {},
    update: (T) -> Unit = {},
) {
    val context = LocalContext.current
    val view = remember { factory(context) }
    androidx.compose.runtime.DisposableEffect(view) {
        onDispose {
            onRelease(view)
        }
    }
    SideEffect {
        update(view)
    }

    when (view) {
        is PlayerView -> {
            val resizeMode = view.resizeMode
            Canvas(
                modifier = modifier
                    .background(Color.Black)
                    .fillMaxSize()
                    .onSizeChanged { sz ->
                        DesktopPlayerEngine.updateDisplaySize(sz.width, sz.height)
                    },
            ) {
                // 仅在 Draw 阶段读取 frameTick，每帧只触发 Canvas 重绘，跳过 Composable 重组与布局
                @Suppress("UNUSED_VARIABLE")
                val tick = DesktopPlayerEngine.frameTick.longValue
                val img = DesktopPlayerEngine.currentDrawBitmap ?: return@Canvas
                val cw = size.width
                val ch = size.height
                val bw = img.width.toFloat()
                val bh = img.height.toFloat()
                if (cw <= 0f || ch <= 0f || bw <= 0f || bh <= 0f) return@Canvas

                val dstW: Int
                val dstH: Int
                when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                        dstW = cw.roundToInt()
                        dstH = ch.roundToInt()
                    }
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                        val s = maxOf(cw / bw, ch / bh)
                        dstW = (bw * s).roundToInt()
                        dstH = (bh * s).roundToInt()
                    }
                    AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH -> {
                        val s = cw / bw
                        dstW = cw.roundToInt()
                        dstH = (bh * s).roundToInt()
                    }
                    else -> {
                        val s = minOf(cw / bw, ch / bh)
                        dstW = (bw * s).roundToInt()
                        dstH = (bh * s).roundToInt()
                    }
                }
                val dstX = ((cw - dstW) / 2f).roundToInt()
                val dstY = ((ch - dstH) / 2f).roundToInt()
                clipRect {
                    drawImage(
                        image = img,
                        dstOffset = IntOffset(dstX, dstY),
                        dstSize = IntSize(dstW.coerceAtLeast(1), dstH.coerceAtLeast(1)),
                        filterQuality = FilterQuality.Low,
                    )
                }
            }
        }
        is WebView -> {
            DesktopWebPageView(view = view, context = context, modifier = modifier)
        }
        else -> {
            Box(modifier)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopWebPageView(
    view: WebView,
    context: Context,
    modifier: Modifier = Modifier,
) {
    val currentUrl = view.url.orEmpty()
    val pageTitle = view.title?.takeIf { it.isNotBlank() } ?: currentUrl.ifBlank { "网页浏览" }
    val loading = view.loadingState
    val error = view.errorState
    val mangaPages = view.extractedMangaPages
    val prevUrl = view.extractedPrevUrl
    val nextUrl = view.extractedNextUrl
    val tocUrl = view.extractedTocUrl
    val allLinks = view.extractedLinks

    var filterQuery by remember(currentUrl) { mutableStateOf("") }
    val filteredLinks = remember(allLinks, filterQuery) {
        val q = filterQuery.trim()
        if (q.isEmpty()) allLinks
        else allLinks.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.subtitle.contains(q, ignoreCase = true) ||
                it.url.contains(q, ignoreCase = true)
        }
    }
    val chapterLinks = remember(filteredLinks) { filteredLinks.filter { it.isChapter } }
    val otherLinks = remember(filteredLinks) { filteredLinks.filterNot { it.isChapter } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pageTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (currentUrl.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = currentUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                            )
                        }
                    }

                    if (error != null) {
                        Text(
                            text = "加载失败：$error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    if (mangaPages.size >= 3) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { view.onOpenReaderCallback?.invoke() },
                            ) {
                                Text("📖 进入漫画阅读模式（共 ${mangaPages.size} 页）")
                            }
                            if (!prevUrl.isNullOrBlank()) {
                                FilledTonalButton(onClick = { view.loadUrl(prevUrl) }) {
                                    Text("⬅ 上一话")
                                }
                            }
                            if (!tocUrl.isNullOrBlank()) {
                                FilledTonalButton(onClick = { view.loadUrl(tocUrl) }) {
                                    Text("📑 章节目录")
                                }
                            }
                            if (!nextUrl.isNullOrBlank()) {
                                FilledTonalButton(onClick = { view.loadUrl(nextUrl) }) {
                                    Text("下一话 ➡")
                                }
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        view.onGoHomeCallback?.let { goHome ->
                            FilledTonalButton(onClick = goHome) {
                                Text("🏠 网页主页（收藏 / 历史）")
                            }
                        }
                        if (view.canGoBack()) {
                            OutlinedButton(onClick = { view.goBack() }) {
                                Text("⬅ 后退")
                            }
                        }
                        OutlinedButton(onClick = { view.reload() }) {
                            Text("🔄 刷新")
                        }
                        if (currentUrl.isNotBlank()) {
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                            }) {
                                Text("🌐 在系统浏览器中打开")
                            }
                        }
                    }
                }
            }
        }

        if (allLinks.size > 6) {
            item {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    placeholder = { Text("筛选章节、漫画名或链接…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (chapterLinks.isNotEmpty()) {
            item {
                Text(
                    text = "章节列表（${chapterLinks.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FlowRow(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        chapterLinks.forEach { link ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { view.loadUrl(link.url) },
                            ) {
                                Text(
                                    text = link.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (otherLinks.isNotEmpty()) {
            item {
                Text(
                    text = "页面内容与导航（${otherLinks.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column {
                        otherLinks.take(150).forEachIndexed { idx, link ->
                            if (idx > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { view.loadUrl(link.url) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = link.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (link.subtitle.isNotBlank()) {
                                    Text(
                                        text = link.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = link.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
