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
import java.io.BufferedInputStream
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
    protected open fun bytesTransferred(bytesTransferred: Int) {
        if (isNetwork && bytesTransferred > 0) {
            com.aisub.player.desktop.DesktopPlayerEngine.recordNetworkBytes(bytesTransferred.toLong())
        }
    }
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
    @Volatile private var activeCall: Call? = null
    @Volatile private var activeResponse: Response? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var currentUri: Uri? = null
    @Volatile private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    @Volatile private var responseHeadersMap: Map<String, List<String>> = emptyMap()
    @Volatile private var isLoopbackHost: Boolean = false
    @Volatile private var openedOnce: Boolean = false
    @Volatile private var opening: Boolean = false
    @Volatile private var cancelledExternally: Boolean = false

    init {
        warmUp()
    }

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeadersMap
    override fun getUri(): Uri? = currentUri

    override fun open(dataSpec: DataSpec): Long {
        if (cancelledExternally) {
            cancelledExternally = false
            throw java.io.InterruptedIOException("DataSource cancelled before open")
        }
        openedOnce = true
        opening = true
        try {
            closeCurrentResources()
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
                if (cancelledExternally) {
                    runCatching { call.cancel() }
                    activeCall = null
                    cancelledExternally = false
                    throw java.io.InterruptedIOException("DataSource cancelled during open")
                }
                resp = call.execute()
                if (cancelledExternally) {
                    runCatching { call.cancel() }
                    runCatching { resp.close() }
                    activeCall = null
                    cancelledExternally = false
                    throw java.io.InterruptedIOException("DataSource cancelled during open")
                }
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
            val host = resp.request.url.host.lowercase()
            isLoopbackHost = host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
            val body = resp.body ?: throw IOException("空 HTTP 响应体")
            input = BufferedInputStream(body.byteStream(), 256 * 1024)
            val contentLen = body.contentLength()
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> if (contentLen >= 0) minOf(dataSpec.length, contentLen) else dataSpec.length
                contentLen >= 0 -> contentLen
                else -> C.LENGTH_UNSET.toLong()
            }
            return bytesRemaining
        } finally {
            opening = false
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val ins = input ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), bytesRemaining).toInt()
        val n = ins.read(buffer, offset, toRead)
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= n
        if (n > 0 && !isLoopbackHost) bytesTransferred(n)
        return n
    }

    private fun closeCurrentResources() {
        val call = activeCall
        val resp = activeResponse
        val ins = input
        activeCall = null
        activeResponse = null
        input = null
        // 若尚未读完就被上游关闭（例如 Range 跳转或抢占），主动 cancel 以立刻断开底层连接并避免排空阻塞
        if (bytesRemaining != 0L) {
            runCatching { call?.cancel() }
        }
        runCatching { ins?.close() }
        runCatching { resp?.close() }
    }

    override fun close() {
        if (!openedOnce || opening) {
            cancelledExternally = true
        }
        closeCurrentResources()
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
        private val highThroughputSocketFactory = object : javax.net.SocketFactory() {
            private val delegate = getDefault()
            private fun tune(s: java.net.Socket): java.net.Socket = s.apply {
                runCatching {
                    tcpNoDelay = true
                    receiveBufferSize = 1024 * 1024
                }
            }
            override fun createSocket(): java.net.Socket = tune(delegate.createSocket())
            override fun createSocket(host: String?, port: Int): java.net.Socket = tune(delegate.createSocket(host, port))
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
                tune(delegate.createSocket(host, port, localHost, localPort))
            override fun createSocket(host: java.net.InetAddress?, port: Int): java.net.Socket = tune(delegate.createSocket(host, port))
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
                tune(delegate.createSocket(address, port, localAddress, localPort))
        }

        private val sharedHttpClient: OkHttpClient = AppProxy.builder()
            .socketFactory(highThroughputSocketFactory)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        @Volatile
        private var warmed = false

        internal fun warmUp() {
            if (warmed) return
            warmed = true
            runCatching {
                val url = "http://127.0.0.1:1/warmup".toHttpUrlOrNull()
                if (url != null) {
                    val req = Request.Builder().url(url).header("Accept-Encoding", "identity").build()
                    sharedHttpClient.newCall(req)
                }
                Class.forName("okhttp3.internal.connection.RealConnection")
                Class.forName("okhttp3.internal.http1.Http1ExchangeCodec")
            }
        }
    }
}

class DefaultDataSource(
    private val context: Context,
    private val baseDataSource: DataSource,
) : DataSource {
    private val fileDataSource = FileDataSource()
    @Volatile private var active: DataSource? = null

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
        val a = active
        active = null
        runCatching { a?.close() }
        if (a !== baseDataSource) {
            runCatching { baseDataSource.close() }
        }
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
