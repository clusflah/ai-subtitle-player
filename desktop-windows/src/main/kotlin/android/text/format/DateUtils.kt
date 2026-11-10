package android.text.format

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateUtils {
    const val SECOND_IN_MILLIS = 1000L
    const val MINUTE_IN_MILLIS = 60_000L
    const val HOUR_IN_MILLIS = 3_600_000L
    const val DAY_IN_MILLIS = 86_400_000L

    const val FORMAT_SHOW_TIME = 1
    const val FORMAT_SHOW_DATE = 16
    const val FORMAT_SHOW_YEAR = 4

    @JvmStatic
    @JvmOverloads
    fun getRelativeTimeSpanString(
        time: Long,
        now: Long = System.currentTimeMillis(),
        minResolution: Long = MINUTE_IN_MILLIS,
    ): CharSequence {
        val diff = (now - time).coerceAtLeast(0L)
        return when {
            diff < MINUTE_IN_MILLIS -> "刚刚"
            diff < HOUR_IN_MILLIS -> "${diff / MINUTE_IN_MILLIS} 分钟前"
            diff < DAY_IN_MILLIS -> "${diff / HOUR_IN_MILLIS} 小时前"
            diff < 30 * DAY_IN_MILLIS -> "${diff / DAY_IN_MILLIS} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(time))
        }
    }

    @JvmStatic
    fun formatDateTime(context: Context?, millis: Long, flags: Int): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }
}
