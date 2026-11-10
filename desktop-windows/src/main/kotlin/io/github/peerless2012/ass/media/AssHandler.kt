package io.github.peerless2012.ass.media

import androidx.media3.exoplayer.ExoPlayer
import io.github.peerless2012.ass.media.type.AssRenderType

class AssHandler(val renderType: AssRenderType) {
    fun init(player: ExoPlayer) {}
    fun release() {}
}
