package com.castview.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** mDNS based device discovery, so receivers show up automatically like LetsView. */
class Discovery(ctx: Context) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun register(name: String) {
        val info = NsdServiceInfo().apply {
            serviceName = name; serviceType = Protocol.SERVICE_TYPE; port = Protocol.PORT
        }
        reg = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg) } catch (_: Exception) {}
    }

    fun start(onFound: (name: String, host: String) -> Unit) {
        disc = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(s: NsdServiceInfo?) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                synchronized(pending) { pending.add(s) }
                next(onFound)
            }
        }
        try { nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc) } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun next(onFound: (String, String) -> Unit) {
        val s = synchronized(pending) {
            if (resolving || pending.isEmpty()) return
            resolving = true; pending.removeFirst()
        }
        try {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo?, e: Int) { done(onFound) }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    i.host?.hostAddress?.let { onFound(i.serviceName, it) }
                    done(onFound)
                }
            })
        } catch (_: Exception) { done(onFound) }
    }

    private fun done(onFound: (String, String) -> Unit) {
        synchronized(pending) { resolving = false }
        next(onFound)
    }

    fun stop() {
        try { reg?.let { nsd.unregisterService(it) } } catch (_: Exception) {}
        try { disc?.let { nsd.stopServiceDiscovery(it) } } catch (_: Exception) {}
        reg = null; disc = null
    }
}
