package com.aisub.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.RemoteViews
import androidx.media3.common.Player

/**
 * 桌面小组件「正在播放」：封面、歌名、歌手，上一首 / 播放暂停 / 下一首。
 * 播放器状态变了（播放、暂停、切歌、读到歌曲信息）就刷新；应用没在播放时显示「没有在播放」，点一下打开译幕。
 */
class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = render(context)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE, ACTION_PREV, ACTION_NEXT -> {
                // 应用已经被系统结束、小组件还停在之前的样子：换成「没有在播放」，再试着打开译幕
                val p = PlaybackService.session?.player ?: run {
                    render(context)
                    return openApp(context)
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

    private fun openApp(context: Context) {
        // 系统可能不允许从后台打开界面；不行也没关系，刷新后的小组件点一下就能打开
        runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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

        private val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
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
            render(context)
        }

        private fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_now_playing)
            val p = player?.takeIf { it.currentMediaItem != null }
            if (p == null) {
                v.setTextViewText(R.id.widget_title, "没有在播放")
                v.setTextViewText(R.id.widget_artist, "译幕")
                v.setImageViewResource(R.id.widget_cover, R.drawable.widget_cover_placeholder)
                v.setImageViewResource(R.id.widget_play, R.drawable.widget_play)
                v.setViewVisibility(R.id.widget_prev, View.GONE)
                v.setViewVisibility(R.id.widget_next, View.GONE)
            } else {
                val meta = p.mediaMetadata
                v.setTextViewText(R.id.widget_title, meta.title ?: meta.displayTitle ?: "")
                val artist = listOfNotNull(meta.artist, meta.albumTitle).filter { it.isNotBlank() }.joinToString(" · ")
                v.setTextViewText(R.id.widget_artist, artist)
                v.setViewVisibility(R.id.widget_artist, if (artist.isEmpty()) View.GONE else View.VISIBLE)
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
            v.setOnClickPendingIntent(R.id.widget_play, if (p == null) openApp else broadcast(context, ACTION_TOGGLE, 10))
            v.setOnClickPendingIntent(R.id.widget_prev, broadcast(context, ACTION_PREV, 11))
            v.setOnClickPendingIntent(R.id.widget_next, broadcast(context, ACTION_NEXT, 12))
            return v
        }

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
