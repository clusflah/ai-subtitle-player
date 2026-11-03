package com.aisub.player.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aisub.player.media.formatDuration
import com.aisub.player.media.formatSize
import com.aisub.player.telegram.Telegram
import com.aisub.player.telegram.TgKind
import com.aisub.player.telegram.TgSender
import com.aisub.player.telegram.TgVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 「全部消息」像官方客户端那样显示：新消息在下面，气泡、发送人名字和头像、回复引用、表情回应、日期分隔

/** 官方的 7 种名字颜色（红、橙、紫、绿、青、蓝、粉），深色主题用亮一点的 */
private val NAME_COLORS = listOf(0xFFCC5049, 0xFFD67722, 0xFF955CDB, 0xFF40A920, 0xFF309EBA, 0xFF368AD1, 0xFFC7508B).map(::Color)
private val NAME_COLORS_DARK = listOf(0xFFFF8E86, 0xFFFFA357, 0xFFB18FFF, 0xFF6FD25A, 0xFF4FD0E3, 0xFF5AB4F5, 0xFFFF7EB6).map(::Color)

/** 同一个人这么久之内连着发的算一组（只第一条显示名字、最后一条显示头像） */
private const val RUN_GAP_SEC = 10 * 60

@Composable
internal fun TgChatMessages(
    /** 新的在前（和加载顺序一样）；倒着排，第一条在最下面 */
    messages: List<TgVideo>,
    state: LazyListState,
    loading: Boolean,
    error: String?,
    loadMore: () -> Unit,
    progress: Map<String, Pair<Long, Long>>,
    saved: Set<String>,
    onClick: (TgVideo) -> Unit,
    onLongClick: (TgVideo) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val names = if (dark) NAME_COLORS_DARK else NAME_COLORS
    val bubble = if (dark) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLowest
    val dayFormat = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()) }
    fun day(v: TgVideo) = dayFormat.format(java.util.Date(v.date * 1000L))
    fun sameRun(older: TgVideo, newer: TgVideo): Boolean {
        val a = older.meta?.sender?.id ?: return false
        return a == newer.meta?.sender?.id && newer.date - older.date < RUN_GAP_SEC && day(older) == day(newer)
    }
    LazyColumn(
        state = state, reverseLayout = true,
        contentPadding = PaddingValues(vertical = 6.dp),
        modifier = Modifier.fillMaxSize()
            .background(if (dark) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        itemsIndexed(messages, key = { _, v -> v.messageId }) { i, v ->
            if (i >= messages.size - 3) LaunchedEffect(messages.size) { loadMore() }
            val older = messages.getOrNull(i + 1)
            val newer = messages.getOrNull(i - 1)
            Column {
                if (older == null || day(older) != day(v)) DateChip(v.date)
                Bubble(
                    v, names, bubble,
                    first = older == null || !sameRun(older, v),
                    last = newer == null || !sameRun(v, newer),
                    watched = progress[v.uri],
                    saved = v.isVideo && "${v.chatId}/${v.messageId}" in saved,
                    onClick = { onClick(v) }, onLongClick = { onLongClick(v) },
                )
            }
        }
        if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
        error?.let { e -> item { TextButton(onClick = loadMore, modifier = Modifier.padding(16.dp)) { Text("加载失败（$e），点这里重试") } } }
    }
}

/** 一天的第一条消息上面：「11月4日」，不是今年的带年份 */
@Composable
private fun DateChip(date: Int) {
    val d = java.util.Date(date * 1000L)
    val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val year = java.util.Calendar.getInstance().apply { time = d }.get(java.util.Calendar.YEAR)
    val text = java.text.SimpleDateFormat(if (year == thisYear) "M月d日" else "yyyy年M月d日", java.util.Locale.getDefault()).format(d)
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = Color.White,
            modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Bubble(
    v: TgVideo,
    names: List<Color>,
    background: Color,
    first: Boolean,
    last: Boolean,
    watched: Pair<Long, Long>?,
    saved: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val meta = v.meta
    // 群里显示发送人；频道帖都是频道发的，不用
    val group = meta != null && !meta.isChannelPost
    val sender = meta?.sender
    val media = v.kind == TgKind.PHOTO || v.kind == TgKind.VIDEO
    val text = if (v.kind == TgKind.FILE && v.caption == v.fileName) "" else v.caption.trim()
    // 右下角：频道帖的阅读数、已编辑、时间
    val footer = listOfNotNull(
        if (saved) "♥" else null,
        meta?.views?.takeIf { it > 0 }?.let { "👁 " + countLabel(it) },
        meta?.authorSignature?.ifBlank { null },
        if (meta?.edited == true) "已编辑" else null,
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(v.date * 1000L)),
    ).joinToString(" ")
    val shape = RoundedCornerShape(topStart = if (first) 16.dp else 6.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = if (last) 4.dp else 6.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 48.dp, top = if (first) 4.dp else 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (group) {
            if (last && sender != null) SenderAvatar(sender, names) else Spacer(Modifier.width(34.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(
            Modifier.then(if (media) Modifier.width(260.dp) else Modifier.widthIn(min = 64.dp, max = 300.dp))
                .clip(shape).background(background)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            val header = Modifier.padding(horizontal = 10.dp).padding(top = 6.dp)
            if (group && first && sender != null) Text(
                sender.name, color = names[sender.colorIndex], style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = header,
            )
            meta?.forwardFrom?.let {
                Text("转发自 $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = header)
            }
            meta?.replyName?.let { name ->
                val c = names[meta.replyColorIndex]
                Row(
                    header.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.12f)),
                ) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(c))
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(name, color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        meta.replyText?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            val hasHeader = (group && first && sender != null) || meta?.forwardFrom != null || meta?.replyName != null
            when {
                media -> Box(Modifier.padding(top = if (hasHeader) 6.dp else 0.dp)) {
                    MediaThumb(v, meta?.mediaWidth ?: 0, meta?.mediaHeight ?: 0, watched)
                    if (text.isEmpty()) Footer(footer, Color.White, Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                v.kind == TgKind.FILE -> Row(
                    Modifier.padding(start = 10.dp, end = 12.dp, top = if (hasHeader) 6.dp else 8.dp, bottom = if (text.isEmpty()) 0.dp else 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(AppIcons.File, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(v.fileName.ifBlank { v.title }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (v.size > 0) Text(formatSize(v.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (text.isNotEmpty()) {
                // 时间放在最后一行的右边：正文后面垫一段同样宽的透明字，放得下就不另起一行
                Box(Modifier.padding(start = 10.dp, end = 10.dp, top = if (hasHeader || media || v.kind == TgKind.FILE) 4.dp else 6.dp, bottom = 6.dp)) {
                    Text(
                        buildAnnotatedString {
                            append(text)
                            withStyle(SpanStyle(color = Color.Transparent, fontSize = MaterialTheme.typography.labelSmall.fontSize)) { append("　 $footer") }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Footer(footer, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.align(Alignment.BottomEnd))
                }
            } else if (!media) Footer(footer, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.align(Alignment.End).padding(end = 10.dp, bottom = 6.dp))
            if (meta != null && meta.reactions.isNotEmpty()) FlowRow(
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                meta.reactions.forEach { (emoji, count) ->
                    Text(
                        "$emoji $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Footer(text: String, color: Color, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, modifier = modifier)
}

/** 图片、视频：按原比例（太高太扁的裁一下），视频带时长、播放键和看过的进度 */
@Composable
private fun MediaThumb(v: TgVideo, width: Int, height: Int, watched: Pair<Long, Long>?) {
    val mini = remember(v.messageId) { v.mini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val thumb by produceState<ImageBitmap?>(null, v.messageId) {
        val file = v.thumb ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { Telegram.thumbnailPath(file)?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    val ratio = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.6f, 1.9f) else 16f / 9
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        (thumb ?: mini)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (v.isVideo) {
            Box(Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Icon(AppIcons.PlayArrow, "播放", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            val label = listOfNotNull(v.durationSec.takeIf { it > 0 }?.let { formatDuration(it * 1000L) }, v.size.takeIf { it > 0 }?.let(::formatSize)).joinToString(" · ")
            if (label.isNotEmpty()) Text(
                label, style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0x66000000), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            val duration = watched?.second?.takeIf { it > 0 } ?: (v.durationSec * 1000L)
            if (watched != null && duration > 0 && watched.first > 0) {
                val fraction = if (watched.first >= duration - 30_000) 1f else (watched.first.toFloat() / duration).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                    Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

/** 发送人头像：先模糊小图，下好换清楚的；没头像的用名字第一个字，底色是名字颜色 */
@Composable
private fun SenderAvatar(s: TgSender, names: List<Color>) {
    val mini = remember(s.id) { s.photoMini?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    val photo by produceState<ImageBitmap?>(null, s.id) {
        val file = s.photo ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { Telegram.thumbnailPath(file)?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }.getOrNull()
        }
    }
    Box(Modifier.size(34.dp).clip(CircleShape).background(names[s.colorIndex]), contentAlignment = Alignment.Center) {
        val img = photo ?: mini
        if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(firstGrapheme(s.name), style = MaterialTheme.typography.titleSmall, color = Color.White)
    }
}

/** 1234 → 1.2K，12345 → 1.2万（和官方中文版一样） */
private fun countLabel(n: Int): String = when {
    n >= 10_000 -> "%.1f万".format(n / 10_000f).replace(".0万", "万")
    n >= 1_000 -> "%.1fK".format(n / 1_000f).replace(".0K", "K")
    else -> n.toString()
}
