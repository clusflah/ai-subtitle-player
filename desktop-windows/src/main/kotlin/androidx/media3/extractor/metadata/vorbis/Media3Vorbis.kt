package androidx.media3.extractor.metadata.vorbis

import androidx.media3.common.Metadata

open class VorbisComment(
    val key: String,
    val value: String,
) : Metadata.Entry
