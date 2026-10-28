package com.aisub.player

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aisub.player.media.AppStore
import com.aisub.player.media.HistoryItem
import com.aisub.player.media.MediaEntry
import com.aisub.player.media.withAiff

/**
 * 应用没开着时，在桌面小组件上点播放：不打开界面，直接在后台播放服务里接着放最近听的歌（同首页「继续收听」）。
 * 只是个精简的播放器：歌名、歌手、封面用文件里的标签，没有歌词等功能。
 * 打开译幕时界面接管（[takeOver]），从同一首歌、同一位置继续。
 */
@UnstableApi
internal object WidgetPlayback {

    /** 接管时交给界面的播放队列 */
    class Handover(val queue: List<MediaEntry>, val index: Int, val playing: Boolean)

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var queue: List<MediaEntry> = emptyList()
    private var store: AppStore? = null
    private var app: Application? = null

    /** 最近听过的歌，最新的在前 */
    fun recentSongs(context: Context): List<HistoryItem> =
        AppStore(context).history().filter { it.entry.isMusic }.take(MAX_SONGS)

    /** 在后台开始播放；没有听过的歌时返回 false */
    fun start(service: MediaSessionService): Boolean {
        PlaybackService.session?.let { it.player.play(); return true }
        val app = service.application
        val st = AppStore(app)
        val songs = st.history().filter { it.entry.isMusic }.take(MAX_SONGS)
        if (songs.isEmpty()) return false
        val p = ExoPlayer.Builder(app)
            .setRenderersFactory(renderersFactory(app))
            .setMediaSourceFactory(DefaultMediaSourceFactory(authDataSourceFactory(app), DefaultExtractorsFactory().withAiff()))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        queue = songs.map { it.entry }
        // 没听完的接着上次的位置，快听完的从头开始（和界面里的续播一样）
        val first = songs.first()
        val duration = first.entry.durationMs
        val resume = first.positionMs.takeIf { duration <= 0 || it < duration - 10_000 } ?: 0L
        p.setMediaItems(queue.map(::itemOf), 0, resume)
        p.addListener(saver)
        val s = MediaSession.Builder(service, p)
            .setId(java.util.UUID.randomUUID().toString())
            .setSessionActivity(openApp(app))
            .build()
        player = p
        session = s
        store = st
        this.app = app
        PlaybackService.session = s
        service.addSession(s)
        p.prepare()
        p.play()
        NowPlayingWidget.attach(app, p)
        return true
    }

    /** 界面打开了：记下进度、停掉后台播放器，把队列交给界面接着放 */
    fun takeOver(): Handover? {
        val p = player ?: return null
        val h = Handover(queue, p.currentMediaItemIndex, p.playWhenReady)
        release()
        return h
    }

    /** 记下进度并释放；后台服务结束时也会调用 */
    fun release() {
        val p = player ?: return
        save(p)
        p.removeListener(saver)
        session?.let { if (PlaybackService.session === it) PlaybackService.session = null }
        session?.release()
        p.release()
        app?.let(NowPlayingWidget::detach)
        player = null
        session = null
        store = null
        app = null
    }

    /** 暂停、切歌时记进播放历史，首页「继续收听」和下次续播用 */
    private val saver = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) player?.let(::save)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            player?.let(::save)
        }
    }

    private fun save(p: Player) {
        val entry = queue.getOrNull(p.currentMediaItemIndex) ?: return
        val duration = p.duration.takeIf { it > 0 } ?: entry.durationMs
        runCatching { store?.recordHistory(entry.copy(durationMs = duration), p.currentPosition.coerceAtLeast(0)) }
    }

    private fun itemOf(e: MediaEntry) = MediaItem.Builder()
        .setUri(e.uri)
        .setMediaId(e.uri)
        // 只给歌名兜底；歌手、专辑、封面从文件标签里读
        .setMediaMetadata(MediaMetadata.Builder().setTitle(e.displayTitle).build())
        .build()

    private fun openApp(app: Application) = PendingIntent.getActivity(
        app, 3, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val MAX_SONGS = 12
}
