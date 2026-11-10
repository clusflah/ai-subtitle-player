package com.aisub.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.aisub.player.media.MediaEntry
import com.aisub.player.music.Lyrics

object WidgetPlayback {
    data class Handover(val queue: List<MediaEntry>, val index: Int, val playing: Boolean)
    fun takeOver(): Handover? = null
}

class PlaybackService {
    companion object {
        var session: MediaSession? = null
    }
}

object NowPlayingWidget {
    fun attach(context: Context, player: Player) {}
    fun detach(context: Context) {}
    fun setLyrics(context: Context, lyrics: Lyrics?) {}
    fun refresh(context: Context) {}
}
