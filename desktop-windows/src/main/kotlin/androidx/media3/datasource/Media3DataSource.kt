package androidx.media3.datasource

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import com.aisub.player.net.AppProxy
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URL
import java.util.concurrent.TimeUnit

interface TransferListener

class DataSpec(
    val uri: Uri,
    val position: Long = 0L,
    val length: Long = C.LENGTH_UNSET.toLong(),
    val key: String? = null,
    val httpRequestHeaders: Map<String, String> = emptyMap(),
) {
    fun buildUpon(): Builder = Builder(this)

    fun withUri(newUri: Uri): DataSpec = DataSpec(newUri, position, length, key, httpRequestHeaders)

    fun withAdditionalHeaders(additionalHeaders: Map<String, String>): DataSpec {
        val merged = LinkedHashMap(httpRequestHeaders)
        merged.putAll(additionalHeaders)
        return DataSpec(uri, position, length, key, merged)
    }

    class Builder() {
        private var uri: Uri = Uri.EMPTY
        private var position: Long = 0L
        private var length: Long = C.LENGTH_UNSET.toLong()
        private var key: String? = null
        private var httpRequestHeaders: Map<String, String> = emptyMap()

        constructor(spec: DataSpec) : this() {
            uri = spec.uri
            position = spec.position
            length = spec.length
            key = spec.key
            httpRequestHeaders = spec.httpRequestHeaders
        }

        fun setUri(v: Uri): Builder = apply { uri = v }
        fun setUri(v: String): Builder = apply { uri = Uri.parse(v) }
        fun setPosition(v: Long): Builder = apply { position = v }
        fun setLength(v: Long): Builder = apply { length = v }
        fun setKey(v: String?): Builder = apply { key = v }
        fun setHttpRequestHeaders(v: Map<String, String>): Builder = apply { httpRequestHeaders = v }

        fun build(): DataSpec = DataSpec(uri, position, length, key, httpRequestHeaders)
    }
}

interface DataSource : DataReader {
    fun interface Factory {
        fun createDataSource(): DataSource
    }

    fun addTransferListener(transferListener: TransferListener)
    @Throws(IOException::class)
    fun open(dataSpec: DataSpec): Long
    fun getUri(): Uri?
    fun getResponseHeaders(): Map<String, List<String>> = emptyMap()
    @Throws(IOException::class)
    fun close()
}

abstract class BaseDataSource(protected val isNetwork: Boolean) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) {}
    protected fun transferInitializing(dataSpec: DataSpec) {}
    protected fun transferStarted(dataSpec: DataSpec) {}
    protected fun bytesTransferred(bytesTransferred: Int) {}
    protected fun transferEnded() {}
}

object HttpDataSource {
    class InvalidResponseCodeException(
        val responseCode: Int,
        val responseMessage: String?,
        cause: IOException?,
        val headerFields: Map<String, List<String>>,
        val dataSpec: DataSpec,
        val responseBody: ByteArray,
    ) : IOException("HTTP $responseCode", cause)
}

class FileDataSource : BaseDataSource(false) {
    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = FileDataSource()
    }

    private var raf: RandomAccessFile? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = 0L

    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        val f = ContentResolver.resolveFile(dataSpec.uri) ?: File(dataSpec.uri.path ?: dataSpec.uri.toString())
        if (!f.exists()) throw IOException("文件不存在: ${f.absolutePath}")
        val r = RandomAccessFile(f, "r")
        raf = r
        r.seek(dataSpec.position)
        val avail = (r.length() - dataSpec.position).coerceAtLeast(0L)
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length.coerceAtMost(avail) else avail
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val r = raf ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val n = r.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        return n
    }

    override fun close() {
        runCatching { raf?.close() }
        raf = null
        currentUri = null
    }
}

class DefaultHttpDataSource private constructor(
    private val defaultRequestProperties: Map<String, String>,
) : BaseDataSource(true) {
    private var activeCall: Call? = null
    private var activeResponse: Response? = null
    private var input: InputStream? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var responseHeadersMap: Map<String, List<String>> = emptyMap()

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeadersMap
    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        close()
        currentUri = dataSpec.uri
        var targetUrl = URL(dataSpec.uri.toString())
        val initialHost = targetUrl.host
        var redirectCount = 0
        var resp: Response

        while (true) {
            val httpUrl = targetUrl.toString().toHttpUrlOrNull()
                ?: throw IOException("无效的 HTTP URL: $targetUrl")
            val reqBuilder = Request.Builder()
                .url(httpUrl)
                .header("Accept-Encoding", "identity")
            defaultRequestProperties.forEach { (k, v) -> reqBuilder.header(k, v) }
            dataSpec.httpRequestHeaders.forEach { (k, v) ->
                if (!k.equals("Authorization", ignoreCase = true) || targetUrl.host.equals(initialHost, ignoreCase = true)) {
                    reqBuilder.header(k, v)
                }
            }
            if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
                reqBuilder.header("Range", "bytes=${dataSpec.position}-$end")
            }

            val call = sharedHttpClient.newCall(reqBuilder.build())
            activeCall = call
            resp = call.execute()
            val code = resp.code
            responseHeadersMap = resp.headers.toMultimap()

            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val location = resp.header("Location")
                resp.close()
                activeCall = null
                if (location.isNullOrBlank() || ++redirectCount > 10) {
                    throw HttpDataSource.InvalidResponseCodeException(code, resp.message, null, responseHeadersMap, dataSpec, ByteArray(0))
                }
                targetUrl = URL(targetUrl, location)
                continue
            }
            if (code !in 200..299) {
                val msg = resp.message
                resp.close()
                activeCall = null
                throw HttpDataSource.InvalidResponseCodeException(code, msg, null, responseHeadersMap, dataSpec, ByteArray(0))
            }
            break
        }

        activeResponse = resp
        currentUri = Uri.parse(resp.request.url.toString())
        val body = resp.body ?: throw IOException("空 HTTP 响应体")
        input = body.byteStream()
        val contentLen = body.contentLength()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> if (contentLen >= 0) minOf(dataSpec.length, contentLen) else dataSpec.length
            contentLen >= 0 -> contentLen
            else -> C.LENGTH_UNSET.toLong()
        }
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val ins = input ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = ins.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        return n
    }

    override fun close() {
        val call = activeCall
        val resp = activeResponse
        val ins = input
        activeCall = null
        activeResponse = null
        input = null
        // 若尚未读完就被上游关闭（例如 VLC Range 跳转），主动 cancel 以免阻塞等待读完大文件；若已读完，close() 会归还连接至连接池
        if (bytesRemaining == C.LENGTH_UNSET.toLong() || bytesRemaining > 65_536L) {
            runCatching { call?.cancel() }
        }
        runCatching { ins?.close() }
        runCatching { resp?.close() }
    }

    class Factory : DataSource.Factory {
        private val headers = LinkedHashMap<String, String>()
        fun setAllowCrossProtocolRedirects(allow: Boolean): Factory = this
        fun setUserAgent(userAgent: String?): Factory = apply {
            if (!userAgent.isNullOrBlank()) headers["User-Agent"] = userAgent
        }
        fun setDefaultRequestProperties(props: Map<String, String>): Factory = apply {
            headers.putAll(props)
        }
        override fun createDataSource(): DataSource = DefaultHttpDataSource(HashMap(headers))
    }

    companion object {
        private val sharedHttpClient: OkHttpClient by lazy {
            AppProxy.builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}

class DefaultDataSource(
    private val context: Context,
    private val baseDataSource: DataSource,
) : DataSource {
    private val fileDataSource = FileDataSource()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        val ds = if (scheme == "http" || scheme == "https") baseDataSource else fileDataSource
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.getUri()
    override fun getResponseHeaders(): Map<String, List<String>> = active?.getResponseHeaders() ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(
        private val context: Context,
        private val baseDataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory(),
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = DefaultDataSource(context, baseDataSourceFactory.createDataSource())
    }
}

class ResolvingDataSource(
    private val upstream: DataSource,
    private val resolver: Resolver,
) : DataSource {
    fun interface Resolver {
        @Throws(IOException::class)
        fun resolveDataSpec(dataSpec: DataSpec): DataSpec
    }

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long =
        upstream.open(resolver.resolveDataSpec(dataSpec))

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.getUri()
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.getResponseHeaders()

    override fun close() = upstream.close()

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val resolver: Resolver,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ResolvingDataSource(upstreamFactory.createDataSource(), resolver)
    }
}
