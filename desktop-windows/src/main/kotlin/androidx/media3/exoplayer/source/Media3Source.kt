package androidx.media3.exoplayer.source

import android.net.Uri
import androidx.media3.common.DataReader
import androidx.media3.datasource.DataSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.text.SubtitleParser

class LoadEventInfo(
    val uri: Uri,
    val bytesLoaded: Long = 0L,
    val loadDurationMs: Long = 0L,
)

class MediaLoadData(
    val dataType: Int = 0,
    val mediaStartTimeMs: Long = 0L,
    val mediaEndTimeMs: Long = 0L,
)

class DefaultMediaSourceFactory(
    val dataSourceFactory: DataSource.Factory,
    val extractorsFactory: ExtractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory(),
) {
    fun setSubtitleParserFactory(factory: SubtitleParser.Factory): DefaultMediaSourceFactory = this
}

class BundledExtractorsAdapter(private val extractorsFactory: ExtractorsFactory) {
    val currentInputPosition: Long = 0L

    fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput,
    ) {
        output.endTracks()
    }

    fun read(positionHolder: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT

    fun release() {}
}
