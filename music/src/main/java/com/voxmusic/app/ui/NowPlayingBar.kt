package com.voxmusic.app.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxmusic.app.MusicViewModel
import kotlinx.coroutines.delay

/**
 * 底部迷你播放条（在「发现 / 我的」等非播放页时悬浮显示）：封面、歌名、播放/暂停、下一首。
 */
@Composable
fun NowPlayingBar(
    vm: MusicViewModel,
    bottomPadding: Dp,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    navigationBarInset: Boolean = true,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val music by vm.music.state.collectAsStateWithLifecycle()
    val player by vm.playerFlow.collectAsStateWithLifecycle()
    val entry = state.current
    var playing by remember { mutableStateOf(player.playWhenReady) }
    var progress by remember { mutableFloatStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            playing = player.playWhenReady
            if (playing) dismissed = false
            val d = player.duration
            progress = if (d > 0L) (player.currentPosition.toFloat() / d).coerceIn(0f, 1f) else 0f
            delay(400)
        }
    }
    if (entry == null || dismissed || player.mediaItemCount == 0 || state.playbackError != null) return

    val cover: ImageBitmap? = remember(music.cover) {
        music.cover?.takeIf { !it.isRecycled }?.asImageBitmap()
    }
    val glass = LocalLiquidGlassEnabled.current
    val palette = currentVisualPalette()
    val shape = RoundedCornerShape(if (glass) 24.dp else 14.dp)
    val hasBackdrop = glass && LocalGlassBackdrop.current != null
    val horizPad = if (glass) (if (navigationBarInset) 12.dp else 16.dp) else 10.dp

    Surface(
        modifier = (if (navigationBarInset) modifier.navigationBarsPadding() else modifier)
            .padding(start = horizPad, end = horizPad, top = 6.dp, bottom = bottomPadding)
            .fillMaxWidth()
            .let {
                if (glass) {
                    it.glassShadow(shape, GlassStyle.Floating)
                        .liquidGlass(
                            shape = shape,
                            style = GlassStyle.Floating,
                            tint = palette.dockTint,
                            sampleBackdrop = hasBackdrop,
                        )
                } else it
            },
        shape = shape,
        tonalElevation = if (glass) 0.dp else 6.dp,
        shadowElevation = if (glass) 0.dp else 6.dp,
        color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            Row(
                Modifier
                    .clickable(onClick = onOpen)
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(if (glass) 10.dp else 8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.size(42.dp))
                    else Icon(AppIcons.MusicNote, null, tint = MaterialTheme.colorScheme.outline)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        music.tags?.title ?: entry.displayTitle,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    music.tags?.artist?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { vm.togglePlay() }) {
                    Icon(if (playing) AppIcons.Pause else AppIcons.PlayArrow, if (playing) "暂停" else "播放")
                }
                IconButton(onClick = { vm.skipNext() }) {
                    Icon(AppIcons.SkipNext, "下一首", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { player.pause(); vm.savePosition(); dismissed = true }) {
                    Icon(AppIcons.Close, "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(Orange))
            }
        }
    }
}
