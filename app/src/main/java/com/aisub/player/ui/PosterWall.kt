package com.aisub.player.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.aisub.player.AppSettings
import com.aisub.player.meta.MetaRepository
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aisub.player.media.MediaEntry
import com.aisub.player.remote.RemoteItem

/**
 * Emby / Plex 风格的海报墙：竖版海报网格，右上角显示集数或未看集数，左下角显示评分。
 * 单集截图、媒体库封面是横版的，大部分条目是横版时改用宽一点的格子。
 */
@Composable
fun PosterWall(items: List<RemoteItem>, settings: AppSettings, menu: (RemoteItem) -> List<MenuAction>, onClick: (RemoteItem) -> Unit) {
    val wide = items.count { (it.meta?.posterRatio ?: (2f / 3f)) > 1f } > items.size / 2
    LazyVerticalGrid(
        GridCells.Adaptive(if (LocalTv.current) (if (wide) 220.dp else 150.dp) else if (wide) 168.dp else 112.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        items(items, key = { (if (it.isDir) "d:" else "f:") + it.path }) { item ->
            PosterCard(item, wide, settings, menu(item)) { onClick(item) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(item: RemoteItem, wide: Boolean, settings: AppSettings, menu: List<MenuAction>, onClick: () -> Unit) {
    val meta = item.meta
    val title = meta?.title ?: if (item.isDir) item.name else item.name.substringBeforeLast('.')
    val scope = rememberCoroutineScope()
    // 服务器没有海报或加载失败时，到网上（TMDB / 豆瓣）找一张；只找一次
    var poster by remember(item.path) { mutableStateOf(meta?.posterUrl) }
    var searchedOnline by remember(item.path) { mutableStateOf(false) }
    Column(Modifier.tvFocusRing(MaterialTheme.shapes.medium, scale = true).clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f).clip(MaterialTheme.shapes.medium)) {
            NetImage(
                poster, Modifier.fillMaxSize(), maxSize = if (wide) 480 else 360,
                fallback = when {
                    item.isDir -> AppIcons.Folder
                    MediaEntry.isAudioName(item.name) -> AppIcons.MusicNote
                    else -> AppIcons.Movie
                },
                onError = {
                    if (!searchedOnline) {
                        searchedOnline = true
                        scope.launch { MetaRepository.poster(settings, item)?.let { poster = it } }
                    }
                },
            )
            meta?.badge?.let { badge ->
                Text(
                    badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            meta?.rating?.let { r ->
                Row(
                    Modifier.align(Alignment.BottomStart).padding(5.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(11.dp))
                    Text(" %.1f".format(r), color = Color.White, fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(start = 2.dp, top = 2.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                meta?.year?.let {
                    Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (menu.isNotEmpty()) OverflowMenu(menu, compact = true)
        }
    }
}

/** 列表里的非媒体文件（图片、文本、其他）：图片显示缩略图，其他显示类型图标。 */
@Composable
fun FileRow(item: RemoteItem, menu: List<MenuAction>, onClick: () -> Unit) {
    val isImage = MediaEntry.isImageName(item.name)
    ListRow(
        title = item.name,
        subtitle = if (item.size > 0) com.aisub.player.media.formatSize(item.size) else null,
        titleMaxLines = Int.MAX_VALUE,
        leading = {
            if (isImage) {
                NetImage(item.playUrl, Modifier.size(width = 80.dp, height = 45.dp).clip(MaterialTheme.shapes.small), maxSize = 240, fallback = AppIcons.Image)
            } else {
                Box(Modifier.size(width = 80.dp, height = 45.dp), contentAlignment = Alignment.Center) {
                    IconBadge(if (MediaEntry.isTextName(item.name)) AppIcons.Description else AppIcons.File, muted = true)
                }
            }
        },
        trailing = if (menu.isEmpty()) null else ({ OverflowMenu(menu) }),
        onClick = onClick,
    )
}
