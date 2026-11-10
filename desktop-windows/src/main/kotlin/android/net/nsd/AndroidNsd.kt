package android.net.nsd

import java.net.InetAddress

class NsdServiceInfo {
    var serviceName: String = ""
    var serviceType: String = ""
    var port: Int = 0
    var host: InetAddress? = null
}

class NsdManager {
    interface RegistrationListener {
        fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onServiceRegistered(serviceInfo: NsdServiceInfo)
        fun onServiceUnregistered(serviceInfo: NsdServiceInfo)
    }

    interface DiscoveryListener {
        fun onStartDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onStopDiscoveryFailed(serviceType: String, errorCode: Int)
        fun onDiscoveryStarted(serviceType: String)
        fun onDiscoveryStopped(serviceType: String)
        fun onServiceFound(serviceInfo: NsdServiceInfo)
        fun onServiceLost(serviceInfo: NsdServiceInfo)
    }

    interface ResolveListener {
        fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onServiceResolved(serviceInfo: NsdServiceInfo)
    }

    fun registerService(serviceInfo: NsdServiceInfo, protocolType: Int, listener: RegistrationListener) {
        listener.onServiceRegistered(serviceInfo)
    }

    fun unregisterService(listener: RegistrationListener) {}

    fun discoverServices(serviceType: String, protocolType: Int, listener: DiscoveryListener) {
        listener.onDiscoveryStarted(serviceType)
    }

    fun stopServiceDiscovery(listener: DiscoveryListener) {
        listener.onDiscoveryStopped("")
    }

    fun resolveService(serviceInfo: NsdServiceInfo, listener: ResolveListener) {
        if (serviceInfo.host != null) {
            listener.onServiceResolved(serviceInfo)
        } else {
            listener.onResolveFailed(serviceInfo, 0)
        }
    }

    companion object {
        const val PROTOCOL_DNS_SD = 1
    }
}
