package com.aisub.player.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.aisub.player.media.Downloads
import com.aisub.player.remote.RemoteItem
import kotlinx.coroutines.launch

/** 看图：左右滑动切换，双指缩放，双击放大 / 还原，单击显示 / 隐藏顶栏。 */
@Composable
fun ImageViewerScreen(
    serverId: String?,
    images: List<RemoteItem>,
    start: Int,
    onBack: () -> Unit,
    openDetail: (RemoteItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    var bars by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { images[it].path }) { page ->
            ZoomableImage(images[page].playUrl) { bars = !bars }
        }
        AnimatedVisibility(bars, enter = fadeIn(), exit = fadeOut()) {
            val current = images.getOrNull(pager.currentPage)
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, "返回", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current?.name.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (images.size > 1) Text("${pager.currentPage + 1} / ${images.size}", color = Color(0xCCFFFFFF), style = MaterialTheme.typography.bodySmall)
                }
                if (current != null) {
                    IconButton(onClick = { openDetail(current) }) { Icon(AppIcons.Info, "详情", tint = Color.White) }
                    if (serverId != null) {
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    Downloads.enqueue(context, listOf(Downloads.Job(current, "")))
                                    Toast.makeText(context, "已加入下载，保存到 Download/${Downloads.ROOT}", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "下载失败：${e.message ?: e}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Icon(AppIcons.Download, "下载", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(url: String?, onTap: () -> Unit) {
    val context = LocalContext.current
    // first：是否加载完成；second：图片（失败为 null）
    val state by produceState<Pair<Boolean, ImageBitmap?>>(false to url?.let { ImageLoader.cached(it, FULL_SIZE) }, url) {
        value = true to (value.second ?: url?.let { ImageLoader.load(context, it, FULL_SIZE) })
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan * scale
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                    },
                )
            }
            // 没放大时不处理拖动，交给外层左右翻页
            .transformable(transform, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        val img = state.second
        when {
            img != null -> Image(
                img, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            !state.first -> CircularProgressIndicator(color = Color.White)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(AppIcons.Image, null, tint = Color.Gray, modifier = Modifier.size(androidx.compose.ui.unit.Dp(48f)))
                Text("图片加载失败", color = Color.Gray, modifier = Modifier.padding(top = androidx.compose.ui.unit.Dp(8f)))
            }
        }
    }
}

private const val FULL_SIZE = 2400
