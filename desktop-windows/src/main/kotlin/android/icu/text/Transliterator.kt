package android.icu.text

class Transliterator private constructor(
    private val delegate: com.ibm.icu.text.Transliterator,
) {
    fun transliterate(text: String): String = delegate.transliterate(text)

    companion object {
        @JvmStatic
        fun getInstance(id: String): Transliterator {
            return Transliterator(com.ibm.icu.text.Transliterator.getInstance(id))
        }
    }
}
