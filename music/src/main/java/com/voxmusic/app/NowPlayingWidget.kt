package com.voxmusic.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.MusicAppStore
import com.voxmusic.app.music.Lyrics

/**
 * 桌面小组件「正在播放」：支持 4×1、2×2、4×2、4×3 自适应布局、同步歌词和播放控制。
 */
@UnstableApi
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = render(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LIKE -> {
                currentEntry(context)?.let { MusicAppStore(context).toggleLiked(it) }
                render(context)
            }
            ACTION_TOGGLE, ACTION_PREV, ACTION_NEXT -> {
                val p = PlaybackService.session?.player ?: run {
                    render(context)
                    if (intent.action == ACTION_TOGGLE) startBackground(context)
                    return
                }
                when (intent.action) {
                    ACTION_TOGGLE -> if (p.isPlaying) p.pause() else {
                        if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                        p.play()
                    }
                    ACTION_PREV -> p.seekToPrevious()
                    ACTION_NEXT -> p.seekToNext()
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun startBackground(context: Context) {
        runCatching { ContextCompat.startForegroundService(context, playIntent(context)) }
    }

    private enum class Size(val layout: Int, val card: Boolean, val lyricLines: Int) {
        SMALL(R.layout.widget_np_small, card = false, lyricLines = 1),
        SQUARE(R.layout.widget_np_square, card = false, lyricLines = 1),
        CARD2(R.layout.widget_np_card2, card = true, lyricLines = 2),
        CARD3(R.layout.widget_np_card3, card = true, lyricLines = 3),
    }

    companion object {
        private const val ACTION_TOGGLE = "com.voxmusic.app.widget.TOGGLE"
        private const val ACTION_PREV = "com.voxmusic.app.widget.PREV"
        private const val ACTION_NEXT = "com.voxmusic.app.widget.NEXT"
        private const val ACTION_LIKE = "com.voxmusic.app.widget.LIKE"

        private const val LYRIC_COLOR = 0xFFFFB347.toInt()
        private const val DIM_COLOR = 0x99FFFFFF.toInt()

        private var player: Player? = null
        private var appContext: Context? = null
        private var coverData: ByteArray? = null
        private var cover: Bitmap? = null
        private var lyrics: Lyrics? = null
        private val handler = Handler(Looper.getMainLooper())
        private val nextLine = Runnable { appContext?.let(::renderLyric) }

        private val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    )
                ) appContext?.let(::render)
            }
        }

        fun attach(context: Context, p: Player) {
            player?.removeListener(listener)
            appContext = context.applicationContext
            player = p
            p.addListener(listener)
            render(context)
        }

        fun detach(context: Context) {
            player?.removeListener(listener)
            player = null
            coverData = null
            cover = null
            lyrics = null
            render(context)
        }

        fun setLyrics(context: Context, l: Lyrics?) {
            if (l == lyrics) return
            lyrics = l
            appContext = context.applicationContext
            renderLyric(context)
        }

        fun refresh(context: Context) = render(context)

        private fun ids(context: Context, manager: AppWidgetManager) =
            manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))

        private fun sizeOf(manager: AppWidgetManager, id: Int): Size {
            val o = manager.getAppWidgetOptions(id)
            val width = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            return when {
                width == 0 || height == 0 -> Size.CARD2
                height < 110 -> Size.SMALL
                width < 220 -> Size.SQUARE
                height < 230 -> Size.CARD2
                else -> Size.CARD3
            }
        }

        private fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = ids(context, manager)
            if (ids.isEmpty()) return handler.removeCallbacks(nextLine)
            for (id in ids) manager.updateAppWidget(id, views(context, sizeOf(manager, id)))
        }

        private fun renderLyric(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = ids(context, manager)
            val p = player?.takeIf { it.currentMediaItem != null }
            handler.removeCallbacks(nextLine)
            if (ids.isEmpty() || p == null) return
            for (id in ids) {
                val size = sizeOf(manager, id)
                val v = RemoteViews(context.packageName, size.layout)
                fillLyrics(v, size, p)
                manager.partiallyUpdateAppWidget(id, v)
            }
            scheduleNextLine(p)
        }

        private fun line(l: Lyrics?, index: Int): String? =
            l?.lines?.getOrNull(index)?.text?.trim()?.takeIf { it.isNotEmpty() }

        private fun fillLyrics(v: RemoteViews, size: Size, p: Player) {
            val l = lyrics?.takeIf { it.synced && it.lines.isNotEmpty() }
            val index = l?.indexAt(p.currentPosition) ?: -1
            val current = line(l, index)
            if (!size.card) {
                v.setTextViewText(R.id.widget_lyric, current ?: artistOf(p))
                v.setTextColor(R.id.widget_lyric, if (current != null) LYRIC_COLOR else DIM_COLOR)
                return
            }
            v.setTextViewText(R.id.widget_lyric, current ?: if (l == null) "暂无歌词" else "♪")
            v.setTextColor(R.id.widget_lyric, if (current != null) LYRIC_COLOR else DIM_COLOR)
            v.setTextViewText(R.id.widget_lyric_next, line(l, index + 1) ?: "")
            if (size.lyricLines >= 3) v.setTextViewText(R.id.widget_lyric_prev, line(l, index - 1) ?: "")
        }

        private fun scheduleNextLine(p: Player) {
            handler.removeCallbacks(nextLine)
            val l = lyrics?.takeIf { it.synced } ?: return
            if (!p.isPlaying) return
            val pos = p.currentPosition
            val next = l.lines.getOrNull(l.indexAt(pos) + 1)?.timeMs ?: return
            val speed = p.playbackParameters.speed.takeIf { it > 0f } ?: 1f
            handler.postDelayed(nextLine, ((next - pos) / speed).toLong().coerceAtLeast(100L))
        }

        private fun artistOf(p: Player): String = p.mediaMetadata.artist?.toString()?.trim().orEmpty()

        private fun currentEntry(context: Context): MediaEntry? {
            val p = PlaybackService.session?.player ?: player
            val item = p?.currentMediaItem
            if (item != null && item.mediaId.isNotEmpty()) {
                val title = p.mediaMetadata.title?.toString() ?: item.mediaMetadata.title?.toString() ?: item.mediaId.substringAfterLast('/')
                return MediaEntry(item.mediaId, title)
            }
            return WidgetPlayback.recentSongs(context).firstOrNull()?.entry
        }

        private fun views(context: Context, size: Size): RemoteViews {
            val v = RemoteViews(context.packageName, size.layout)
            val p = player?.takeIf { it.currentMediaItem != null }
            val recent = if (p == null) WidgetPlayback.recentSongs(context).firstOrNull() else null
            val subtitle = if (recent != null) "继续收听 · VoxMusic" else "VoxMusic"
            if (p == null) {
                handler.removeCallbacks(nextLine)
                v.setTextViewText(R.id.widget_title, recent?.entry?.displayTitle ?: "没有在播放")
                v.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                v.setImageViewResource(R.id.widget_play, R.drawable.widget_play)
                if (size.card) {
                    v.setTextViewText(R.id.widget_artist, subtitle)
                    v.setTextViewText(R.id.widget_lyric, "")
                    v.setTextViewText(R.id.widget_lyric_next, "")
                    if (size.lyricLines >= 3) v.setTextViewText(R.id.widget_lyric_prev, "")
                    v.setViewVisibility(R.id.widget_prev, View.INVISIBLE)
                    v.setViewVisibility(R.id.widget_next, View.INVISIBLE)
                    v.setViewVisibility(R.id.widget_like, if (recent != null) View.VISIBLE else View.INVISIBLE)
                } else {
                    v.setTextViewText(R.id.widget_lyric, subtitle)
                    v.setTextColor(R.id.widget_lyric, DIM_COLOR)
                }
            } else {
                val meta = p.mediaMetadata
                v.setTextViewText(R.id.widget_title, meta.title ?: meta.displayTitle ?: "")
                fillLyrics(v, size, p)
                scheduleNextLine(p)
                val bmp = coverOf(meta.artworkData)
                if (bmp != null) v.setImageViewBitmap(R.id.widget_cover, bmp)
                else v.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                v.setImageViewResource(R.id.widget_play, if (p.isPlaying) R.drawable.widget_pause else R.drawable.widget_play)
                if (size.card) {
                    v.setTextViewText(R.id.widget_artist, artistOf(p))
                    v.setViewVisibility(R.id.widget_prev, if (p.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) View.VISIBLE else View.INVISIBLE)
                    v.setViewVisibility(R.id.widget_next, if (p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) View.VISIBLE else View.INVISIBLE)
                    v.setViewVisibility(R.id.widget_like, View.VISIBLE)
                }
            }
            if (size.card) {
                val liked = currentEntry(context)?.let { MusicAppStore(context).isLiked(it.uri) } == true
                v.setImageViewResource(R.id.widget_like, if (liked) R.drawable.widget_heart_filled else R.drawable.widget_heart)
                v.setOnClickPendingIntent(R.id.widget_like, broadcast(context, ACTION_LIKE, 14))
                v.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREV, 11))
                v.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 12))
            }
            val open = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openApp = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.widget_root, openApp)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val play = when {
                p != null -> broadcast(context, ACTION_TOGGLE, 10)
                recent != null -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(context, 13, playIntent(context), flags)
                } else {
                    PendingIntent.getService(context, 13, playIntent(context), flags)
                }
                else -> openApp
            }
            v.setOnClickPendingIntent(R.id.widget_play, play)
            return v
        }

        private fun playIntent(context: Context) =
            Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_WIDGET_PLAY)

        private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code, Intent(context, NowPlayingWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun coverOf(data: ByteArray?): Bitmap? {
            if (data == null) return null
            if (data !== coverData) {
                coverData = data
                cover = runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 300) sample *= 2
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    val scale = 300f / maxOf(bmp.width, bmp.height)
                    if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                    else bmp
                }.getOrNull()
            }
            return cover
        }
    }
}
