package androidx.webkit

import android.webkit.WebView
import java.util.concurrent.Executor

object WebViewFeature {
    const val PROXY_OVERRIDE = "PROXY_OVERRIDE"
    const val DOCUMENT_START_SCRIPT = "DOCUMENT_START_SCRIPT"

    @JvmStatic
    fun isFeatureSupported(feature: String): Boolean = false
}

class ProxyConfig {
    class Builder {
        fun addProxyRule(proxyUrl: String): Builder = this
        fun bypassSimpleHostnames(): Builder = this
        fun addBypassRule(bypassRule: String): Builder = this
        fun build(): ProxyConfig = ProxyConfig()
    }
}

class ProxyController {
    fun setProxyOverride(proxyConfig: ProxyConfig, executor: Executor, listener: Runnable) {
        listener.run()
    }

    fun clearProxyOverride(executor: Executor, listener: Runnable) {
        listener.run()
    }

    companion object {
        private val INSTANCE = ProxyController()
        @JvmStatic
        fun getInstance(): ProxyController = INSTANCE
    }
}

class ScriptHandler {
    fun remove() {}
}

object WebViewCompat {
    @JvmStatic
    fun addDocumentStartJavaScript(
        webview: WebView,
        script: String,
        allowedOriginRules: Set<String>,
    ): ScriptHandler = ScriptHandler()
}
