package androidx.media3.exoplayer.hls.playlist

import android.net.Uri
import java.io.InputStream

open class HlsPlaylist(val baseUri: String)

class HlsMediaPlaylist(
    baseUri: String,
    val mediaSequence: Long,
    val targetDurationUs: Long,
    val segments: List<Segment>,
) : HlsPlaylist(baseUri) {
    class Segment(
        val url: String,
        val durationUs: Long,
        val relativeStartTimeUs: Long,
    )
}

class HlsPlaylistParser {
    fun parse(uri: Uri, inputStream: InputStream): HlsPlaylist {
        val lines = inputStream.bufferedReader(Charsets.UTF_8).readLines()
        var mediaSeq = 0L
        var targetDurationUs = 6_000_000L
        val segments = ArrayList<HlsMediaPlaylist.Segment>()
        var currentStartUs = 0L
        var pendingDurationUs: Long? = null

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    val sec = line.substringAfter(':').trim().toDoubleOrNull() ?: 6.0
                    targetDurationUs = (sec * 1_000_000L).toLong()
                }
                line.startsWith("#EXTINF:") -> {
                    val secStr = line.substringAfter(':').substringBefore(',').trim()
                    val sec = secStr.toDoubleOrNull() ?: 0.0
                    pendingDurationUs = (sec * 1_000_000L).toLong()
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val dur = pendingDurationUs ?: targetDurationUs
                    segments.add(
                        HlsMediaPlaylist.Segment(
                            url = line,
                            durationUs = dur,
                            relativeStartTimeUs = currentStartUs,
                        ),
                    )
                    currentStartUs += dur
                    pendingDurationUs = null
                }
            }
        }
        return HlsMediaPlaylist(
            baseUri = uri.toString(),
            mediaSequence = mediaSeq,
            targetDurationUs = targetDurationUs,
            segments = segments,
        )
    }
}
