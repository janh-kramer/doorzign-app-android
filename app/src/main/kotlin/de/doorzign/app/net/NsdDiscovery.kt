package de.doorzign.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import de.doorzign.app.core.AddressInput
import de.doorzign.app.core.Fingerprints
import java.net.Inet6Address
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Per mDNS gefundenes Edge-Gateway (TXT laut docs/edge-connection.md). */
data class FoundGateway(
    val serviceName: String,
    val host: String,
    val httpsPort: Int,
    val instanceId: String?,
    val name: String?,
    val node: String?,
    val fingerprint: String?,
    val apiMajor: String?,
) {
    val origin: String get() = "https://${if (host.contains(':')) "[$host]" else host}:$httpsPort"
    val title: String get() = listOfNotNull(name, node?.takeIf { it != name }).joinToString(" · ").ifEmpty { serviceName }
}

/** mDNS-Suche konnte nicht gestartet werden. */
class DiscoveryFailed(val errorCode: Int) : IllegalStateException("NSD $errorCode")

/** Suche nach `_doorzign._tcp` über NsdManager. Auflösungen laufen nacheinander (ältere Android-Versionen). */
class NsdDiscovery(private val context: Context) {

    fun discover(): Flow<List<FoundGateway>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("doorzign-mdns")?.apply { setReferenceCounted(false); acquire() }
        val found = linkedMapOf<String, FoundGateway>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close(DiscoveryFailed(errorCode)) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { toResolve.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { found.remove(info.serviceName); trySend(found.values.toList()) }
            }
        }

        launch {
            for (info in toResolve) {
                val r = resolve(nsd, info) ?: continue
                toGateway(r)?.let { gw -> synchronized(found) { found[gw.serviceName] = gw; trySend(found.values.toList()) } }
            }
        }

        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock?.release()
            toResolve.close()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (cont.isActive) cont.resume(serviceInfo) }
            })
        }

    @Suppress("DEPRECATION")
    private fun toGateway(info: NsdServiceInfo): FoundGateway? {
        val addr = info.host ?: return null
        val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
        val host = if (addr is Inet6Address) addr.hostAddress!!.substringBefore('%') else addr.hostAddress!!
        return FoundGateway(
            serviceName = info.serviceName,
            host = host,
            httpsPort = txt["https"]?.toIntOrNull() ?: AddressInput.EDGE_DEFAULT_PORT,
            instanceId = txt["id"],
            name = txt["name"],
            node = txt["node"],
            fingerprint = txt["fp"]?.let(Fingerprints::normalize),
            apiMajor = txt["api"],
        )
    }

    companion object {
        const val SERVICE_TYPE = "_doorzign._tcp."
    }
}
