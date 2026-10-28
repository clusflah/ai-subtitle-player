package com.aisub.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.Player
import com.aisub.player.music.Lyrics

/**
 * 桌面小组件「正在播放」：封面、歌名、歌手，上一首 / 播放暂停 / 下一首；有带时间的歌词时，下面一整行显示正在唱的那句。
 * 播放器状态变了（播放、暂停、切歌、读到歌曲信息）就刷新；应用没在播放时显示上次听的歌，
 * 点播放在后台接着放（[WidgetPlayback]），不打开界面；没听过歌时点一下打开译幕。
 */
@androidx.media3.common.util.UnstableApi
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE, ACTION_PREV, ACTION_NEXT -> {
                // 应用已经被系统结束、小组件还停在之前的样子：点播放就在后台接着放，其他按钮只刷新小组件
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
        // 系统可能不允许从这里启动后台播放；不行也没关系，刷新后的小组件再点一下就能放
        runCatching { androidx.core.content.ContextCompat.startForegroundService(context, playIntent(context)) }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.aisub.player.widget.TOGGLE"
        private const val ACTION_PREV = "com.aisub.player.widget.PREV"
        private const val ACTION_NEXT = "com.aisub.player.widget.NEXT"

        private var player: Player? = null
        private var appContext: Context? = null

        /** 封面解码一次就够：同一份封面数据不重复解码 */
        private var coverData: ByteArray? = null
        private var cover: Bitmap? = null

        /** 当前歌的歌词（界面或后台播放器读到后交过来）；到下一句的时间刷新一次 */
        private var lyrics: Lyrics? = null
        private val handler = Handler(Looper.getMainLooper())
        private val nextLine = Runnable { appContext?.let(::renderLyric) }

        private val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
                        // 拖动进度、改倍速：歌词要重新对时间
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
                    )
                ) appContext?.let(::render)
            }
        }

        /** 媒体会话换了播放器（换内核、读到歌曲信息）时调用：改为跟随新的播放器，并马上刷新 */
        fun attach(context: Context, p: Player) {
            player?.removeListener(listener)
            appContext = context.applicationContext
            player = p
            p.addListener(listener)
            render(context)
        }

        /** 播放器释放（退出应用）：显示「没有在播放」 */
        fun detach(context: Context) {
            player?.removeListener(listener)
            player = null
            coverData = null
            cover = null
            lyrics = null
            render(context)
        }

        /** 换歌、读到歌词、调整歌词时间时调用；null 为没有歌词 */
        fun setLyrics(context: Context, l: Lyrics?) {
            if (l == lyrics) return
            lyrics = l
            appContext = context.applicationContext
            renderLyric(context)
        }

        private fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context))
        }

        /** 只换歌词那一行：不重新传封面 */
        private fun renderLyric(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            val p = player?.takeIf { it.currentMediaItem != null }
            if (ids.isEmpty() || p == null) {
                handler.removeCallbacks(nextLine)
                return
            }
            val v = RemoteViews(context.packageName, R.layout.widget_now_playing)
            lyricLine(v, p)
            manager.partiallyUpdateAppWidget(ids, v)
        }

        /**
         * 填正在唱的那句：没有带时间的歌词、还没唱到第一句时显示歌手。
         * 播放中顺便约好下一句开始的时间再刷新。
         */
        private fun lyricLine(v: RemoteViews, p: Player) {
            handler.removeCallbacks(nextLine)
            val l = lyrics?.takeIf { it.synced }
            val pos = p.currentPosition
            val index = l?.indexAt(pos) ?: -1
            val text = l?.lines?.getOrNull(index)?.text?.takeIf { it.isNotBlank() }
            v.setTextViewText(R.id.widget_lyric, text ?: "")
            v.setViewVisibility(R.id.widget_lyric, if (text != null) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.widget_artist, if (artistOf(p).isEmpty()) View.GONE else View.VISIBLE)
            val next = l?.lines?.getOrNull(index + 1)?.timeMs ?: return
            if (!p.isPlaying) return
            val speed = p.playbackParameters.speed.takeIf { it > 0 } ?: 1f
            handler.postDelayed(nextLine, ((next - pos) / speed).toLong().coerceAtLeast(100))
        }

        private fun artistOf(p: Player): String =
            listOfNotNull(p.mediaMetadata.artist, p.mediaMetadata.albumTitle).filter { it.isNotBlank() }.joinToString(" · ")

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_now_playing)
            val p = player?.takeIf { it.currentMediaItem != null }
            val recent = if (p == null) WidgetPlayback.recentSongs(context).firstOrNull() else null
            if (p == null) {
                handler.removeCallbacks(nextLine)
                v.setViewVisibility(R.id.widget_lyric, View.GONE)
                v.setTextViewText(R.id.widget_title, recent?.entry?.displayTitle ?: "没有在播放")
                v.setTextViewText(R.id.widget_artist, if (recent != null) "继续收听 · 译幕" else "译幕")
                v.setViewVisibility(R.id.widget_artist, View.VISIBLE)
                v.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                v.setImageViewResource(R.id.widget_play, R.drawable.widget_play)
                v.setViewVisibility(R.id.widget_prev, View.GONE)
                v.setViewVisibility(R.id.widget_next, View.GONE)
            } else {
                val meta = p.mediaMetadata
                v.setTextViewText(R.id.widget_title, meta.title ?: meta.displayTitle ?: "")
                v.setTextViewText(R.id.widget_artist, artistOf(p))
                lyricLine(v, p)
                val bmp = coverOf(meta.artworkData)
                if (bmp != null) v.setImageViewBitmap(R.id.widget_cover, bmp)
                else v.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                v.setImageViewResource(R.id.widget_play, if (p.isPlaying) R.drawable.widget_pause else R.drawable.widget_play)
                v.setViewVisibility(R.id.widget_prev, if (p.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) View.VISIBLE else View.INVISIBLE)
                v.setViewVisibility(R.id.widget_next, if (p.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) View.VISIBLE else View.INVISIBLE)
            }
            // 点封面、文字：回到播放页（没在播放时就是打开译幕）
            val open = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openApp = PendingIntent.getActivity(context, 2, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            v.setOnClickPendingIntent(R.id.widget_root, openApp)
            // 没在播放：有听过的歌就直接启动后台播放服务（不经过广播，系统允许小组件这样启动前台服务），没有就打开译幕
            val play = when {
                p != null -> broadcast(context, ACTION_TOGGLE, 10)
                recent != null -> PendingIntent.getForegroundService(context, 13, playIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else -> openApp
            }
            v.setOnClickPendingIntent(R.id.widget_play, play)
            v.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREV, 11))
            v.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 12))
            return v
        }

        private fun playIntent(context: Context) =
            Intent(context, PlaybackService::class.java).setAction(PlaybackService.ACTION_WIDGET_PLAY)

        private fun broadcast(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code, Intent(context, NowPlayingWidget::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** 小组件传给桌面的图片有大小限制，封面缩到 300 像素以内 */
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
