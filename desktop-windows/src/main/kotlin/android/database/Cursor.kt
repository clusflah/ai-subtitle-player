package android.database

import java.io.Closeable

interface Cursor : Closeable {
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun getColumnIndex(columnName: String): Int
    fun getColumnIndexOrThrow(columnName: String): Int
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun getInt(columnIndex: Int): Int
    fun isNull(columnIndex: Int): Boolean
    val count: Int
}

class MatrixCursor(
    private val columns: Array<String>,
    private val rows: List<Array<Any?>>,
) : Cursor {
    private var pos = -1

    override val count: Int get() = rows.size

    override fun moveToFirst(): Boolean {
        pos = 0
        return rows.isNotEmpty()
    }

    override fun moveToNext(): Boolean {
        pos++
        return pos in rows.indices
    }

    override fun getColumnIndex(columnName: String): Int {
        return columns.indexOfFirst { it.equals(columnName, ignoreCase = true) }
    }

    override fun getColumnIndexOrThrow(columnName: String): Int {
        val i = getColumnIndex(columnName)
        return if (i >= 0) i else 0
    }

    override fun getString(columnIndex: Int): String? {
        val row = rows.getOrNull(pos) ?: return null
        return row.getOrNull(columnIndex)?.toString()
    }

    override fun getLong(columnIndex: Int): Long {
        val row = rows.getOrNull(pos) ?: return 0L
        return when (val v = row.getOrNull(columnIndex)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    override fun getInt(columnIndex: Int): Int = getLong(columnIndex).toInt()

    override fun isNull(columnIndex: Int): Boolean {
        val row = rows.getOrNull(pos) ?: return true
        return row.getOrNull(columnIndex) == null
    }

    override fun close() {}
}
