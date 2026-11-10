package androidx.media3.common.text

class Cue(
    val text: CharSequence? = null,
    val line: Float = DIMEN_UNSET,
    val lineType: Int = LINE_TYPE_NUMBER,
    val lineAnchor: Int = ANCHOR_TYPE_START,
    val position: Float = DIMEN_UNSET,
    val positionAnchor: Int = ANCHOR_TYPE_START,
    val size: Float = DIMEN_UNSET,
    val bitmap: android.graphics.Bitmap? = null,
) {
    class Builder {
        private var text: CharSequence? = null
        private var line: Float = DIMEN_UNSET
        private var lineType: Int = LINE_TYPE_NUMBER

        fun setText(t: CharSequence?): Builder = apply { text = t }
        fun setLine(l: Float, type: Int): Builder = apply { line = l; lineType = type }
        fun build(): Cue = Cue(text, line, lineType)
    }

    companion object {
        const val DIMEN_UNSET = -3.4028235E38f
        const val LINE_TYPE_NUMBER = 0
        const val LINE_TYPE_FRACTION = 1
        const val ANCHOR_TYPE_START = 0
        const val ANCHOR_TYPE_MIDDLE = 1
        const val ANCHOR_TYPE_END = 2
    }
}

class CueGroup(val cues: List<Cue> = emptyList(), val presentationTimeUs: Long = 0L)
