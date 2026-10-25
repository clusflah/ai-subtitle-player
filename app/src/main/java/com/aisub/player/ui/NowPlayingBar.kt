package com.aisub.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisub.player.MainViewModel
import kotlinx.coroutines.delay

/**
 * 离开播放页后音乐还在放时，页面底部的「正在播放」小条：封面、歌名、播放 / 暂停、关闭。
 * 点小条回到播放页；关闭是暂停并收起，下次开始播放时再出现。
 */
@Composable
fun NowPlayingBar(main: MainViewModel, bottomPadding: Dp, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by main.state.collectAsStateWithLifecycle()
    val music by main.music.state.collectAsStateWithLifecycle()
    val player by main.playerFlow.collectAsStateWithLifecycle()
    val entry = state.current
    var playing by remember { mutableStateOf(player.isPlaying) }
    var progress by remember { mutableFloatStateOf(0f) }
    var dismissed by remember { mutableStateOf(!player.isPlaying) }
    LaunchedEffect(player) {
        while (true) {
            playing = player.playWhenReady
            if (playing) dismissed = false
            val d = player.duration
            progress = if (d > 0) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(500)
        }
    }
    if (entry == null || !main.playingMusic || dismissed || player.mediaItemCount == 0 || state.playbackError != null) return
    val cover: ImageBitmap? = remember(music.cover) { music.cover?.asImageBitmap() }
    Surface(
        modifier.navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = bottomPadding).fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(Modifier.clickable(onClick = onOpen).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
                    else Icon(AppIcons.MusicNote, null, tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(music.tags?.title ?: entry.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    music.tags?.artist?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = { main.togglePlay() }) {
                    Icon(if (playing) AppIcons.Pause else AppIcons.PlayArrow, if (playing) "暂停" else "播放")
                }
                IconButton(onClick = { player.pause(); main.savePosition(); dismissed = true }) {
                    Icon(AppIcons.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Orange))
            }
        }
    }
}
