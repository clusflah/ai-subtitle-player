package android.content.res

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class Configuration(
    var orientation: Int = ORIENTATION_LANDSCAPE,
    var screenWidthDp: Int = 1280,
    var screenHeightDp: Int = 800,
    var smallestScreenWidthDp: Int = 800,
    var densityDpi: Int = 160,
) {
    companion object {
        const val ORIENTATION_UNDEFINED = 0
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val UI_MODE_TYPE_NORMAL = 1
        const val UI_MODE_TYPE_TELEVISION = 4
    }
}

class AssetFileDescriptor(private val file: File) : Closeable {
    val length: Long get() = if (file.exists()) file.length() else -1L
    override fun close() {}
}

class AssetManager {
    fun open(fileName: String): InputStream {
        val clean = fileName.trimStart('/')
        // 优先从 classpath resources 读取（包含 ../app/src/main/assets）
        Thread.currentThread().contextClassLoader?.getResourceAsStream(clean)?.let { return it }
        AssetManager::class.java.getResourceAsStream("/$clean")?.let { return it }
        val devAsset = File("../app/src/main/assets", clean)
        if (devAsset.exists()) return FileInputStream(devAsset)
        val localAsset = File("app/src/main/assets", clean)
        if (localAsset.exists()) return FileInputStream(localAsset)
        throw IOException("找不到内置资源：$fileName")
    }

    fun list(path: String): Array<String> {
        val devAsset = File("../app/src/main/assets", path)
        if (devAsset.isDirectory) return devAsset.list() ?: emptyArray()
        val localAsset = File("app/src/main/assets", path)
        if (localAsset.isDirectory) return localAsset.list() ?: emptyArray()
        return emptyArray()
    }
}

class Resources(
    val assets: AssetManager = AssetManager(),
    val configuration: Configuration = Configuration(),
    val displayMetrics: android.util.DisplayMetrics = android.util.DisplayMetrics(),
)
