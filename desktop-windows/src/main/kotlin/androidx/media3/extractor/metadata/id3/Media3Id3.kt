package androidx.media3.extractor.metadata.id3

import androidx.media3.common.Metadata

open class Id3Frame(val id: String) : Metadata.Entry

class TextInformationFrame(
    id: String,
    val description: String?,
    val values: List<String>,
) : Id3Frame(id)

class BinaryFrame(
    id: String,
    val data: ByteArray,
) : Id3Frame(id)

class Id3Decoder {
    fun decode(data: ByteArray, size: Int): Metadata? = Metadata()
}
