package com.voxmusic.app

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.voxmusic.app.media.HistoryItem
import com.voxmusic.app.media.MediaEntry
import com.voxmusic.app.media.MusicAppStore
import com.voxmusic.app.media.WebStreamingMusicPlayer
import com.voxmusic.app.music.MusicFeatures
import com.voxmusic.app.music.WebStreamingMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 应用未在前台时从桌面小组件点播放：在后台服务里直接用 [WebStreamingMusicPlayer] 恢复最近收听的 Apple Music / Spotify 歌曲。
 * 打开主界面后无缝移交队列与播放状态。
 */
@UnstableApi
internal object WidgetPlayback {

    class Handover(
        val queue: List<MediaEntry>,
        val index: Int,
        val positionMs: Long,
        val playing: Boolean,
    )

    private var player: WebStreamingMusicPlayer? = null
    private var session: MediaSession? = null
    private var queue: List<MediaEntry> = emptyList()
    private var store: MusicAppStore? = null
    private var app: Application? = null
    private var scope: CoroutineScope? = null
    private var music: MusicFeatures? = null

    fun recentSongs(context: Context): List<HistoryItem> =
        MusicAppStore(context).history()
            .filter { WebStreamingMusic.isStreamingUri(it.entry.uri) }
            .take(MAX_SONGS)

    fun start(service: MediaSessionService): Boolean {
        PlaybackService.session?.let { it.player.play(); return true }
        val app = service.application
        val st = MusicAppStore(app)
        val savedSession = st.loadPlaybackSession()?.takeIf { it.queue.isNotEmpty() }
        val startQueue: List<MediaEntry>
        val startIdx: Int
        val resume: Long
        if (savedSession != null) {
            startQueue = savedSession.queue
            startIdx = savedSession.index.coerceIn(startQueue.indices)
            resume = savedSession.positionMs.coerceAtLeast(0L)
        } else {
            val songs = recentSongs(app)
            if (songs.isEmpty()) return false
            startQueue = songs.map { it.entry }
            startIdx = 0
            val first = songs.first()
            val duration = first.entry.durationMs
            resume = first.positionMs.takeIf { duration <= 0L || it < duration - 10_000L } ?: 0L
        }

        val sc = MainScope()
        val settings = SettingsRepository(app).load()
        lateinit var p: WebStreamingMusicPlayer
        lateinit var m: MusicFeatures
        p = WebStreamingMusicPlayer(app) { uri, title, artist, album, durationMs, coverUrl ->
            m.onStreamingMetadataUpdated(uri, title, artist, album, durationMs, coverUrl)
        }
        m = MusicFeatures(app, sc, player = { p }, settings = { settings })

        queue = startQueue
        p.setMediaItems(queue.map(::itemOf), startIdx, resume)
        p.addListener(saver)

        val s = MediaSession.Builder(service, p)
            .setId(java.util.UUID.randomUUID().toString())
            .setSessionActivity(openApp(app))
            .build()

        player = p
        session = s
        store = st
        this.app = app
        scope = sc
        music = m
        PlaybackService.session = s
        service.addSession(s)
        p.prepare()
        p.play()
        NowPlayingWidget.attach(app, p)
        m.onEntryChanged(queue[startIdx])

        sc.launch {
            m.state.distinctUntilChanged { a, b -> a.tags == b.tags && a.cover === b.cover }.collect { ms ->
                if (session !== s) return@collect
                s.player = SessionPlayer(p, musicMetadata(ms))
                NowPlayingWidget.attach(app, s.player)
            }
        }
        sc.launch {
            m.state.map { it.lyrics }.distinctUntilChanged().collect { l ->
                if (session === s) NowPlayingWidget.setLyrics(app, l)
            }
        }
        return true
    }

    fun takeOver(): Handover? {
        val p = player ?: return null
        val h = Handover(
            queue = queue,
            index = p.currentMediaItemIndex.coerceIn(queue.indices),
            positionMs = p.currentPosition.coerceAtLeast(0L),
            playing = p.playWhenReady,
        )
        release()
        return h
    }

    fun release() {
        val p = player ?: return
        save(p)
        p.removeListener(saver)
        session?.let { if (PlaybackService.session === it) PlaybackService.session = null }
        session?.release()
        p.release()
        scope?.cancel()
        scope = null
        music = null
        app?.let(NowPlayingWidget::detach)
        player = null
        session = null
        store = null
        app = null
    }

    private val saver = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) player?.let(::save)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            save(p)
            queue.getOrNull(p.currentMediaItemIndex)?.let { music?.onEntryChanged(it) }
        }
    }

    private fun save(p: Player) {
        val idx = p.currentMediaItemIndex
        val entry = queue.getOrNull(idx) ?: return
        val duration = p.duration.takeIf { it > 0L } ?: entry.durationMs
        val pos = p.currentPosition.coerceAtLeast(0L)
        runCatching {
            store?.recordHistory(entry.copy(durationMs = duration), pos)
            store?.savePlaybackProgress(entry.copy(durationMs = duration), idx, pos, durationMs = duration)
        }
    }

    private fun itemOf(e: MediaEntry) = MediaItem.Builder()
        .setUri(e.uri)
        .setMediaId(e.uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(e.displayTitle).build())
        .build()

    private fun openApp(app: Application) = PendingIntent.getActivity(
        app, 3, Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val MAX_SONGS = 12
}
