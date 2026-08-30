package shelly.local.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import shelly.local.model.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class ScanRange(
    val startIp: String = "",
    val endIp: String = "",
)

data class DiscoveredDevice(
    val name: String,
    val ipAddress: String,
    val source: DiscoverySource,
    val detectedType: DeviceType = DeviceType.UNKNOWN,
)

data class DeviceProbeResult(val name: String, val type: DeviceType)

enum class DiscoverySource { MDNS, SUBNET, BLE }

/** Auto-detect the /24 prefix of the current WiFi connection (no permissions needed). */
fun detectCurrentSubnet(context: Context): String {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val props = cm.getLinkProperties(cm.activeNetwork ?: return "192.168.1") ?: return "192.168.1"
    val addr = props.linkAddresses
        .firstOrNull { it.address is Inet4Address }
        ?.address?.hostAddress ?: return "192.168.1"
    val parts = addr.split(".")
    return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}" else "192.168.1"
}

/**
 * Scan all provided [ranges] concurrently (up to 40 in-flight probes at once).
 * Each range must be within the same /24; cross-subnet ranges are silently skipped.
 * Calls [onFound] for each host that responds to GET /shelly.
 * Calls [onProgress] with (completed, total) after each probe.
 */
suspend fun scanRanges(
    ranges: List<ScanRange>,
    onFound: (DiscoveredDevice) -> Unit,
    onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
) {
    data class ParsedRange(val prefix: String, val startHost: Int, val endHost: Int)

    val parsed = ranges.mapNotNull { range ->
        val s = range.startIp.trim().split(".")
        val e = range.endIp.trim().split(".")
        if (s.size != 4 || e.size != 4) return@mapNotNull null
        val startHost = s[3].toIntOrNull()?.coerceIn(1, 254) ?: return@mapNotNull null
        val endHost   = e[3].toIntOrNull()?.coerceIn(1, 254) ?: return@mapNotNull null
        if (startHost > endHost) return@mapNotNull null
        val prefix = "${s[0]}.${s[1]}.${s[2]}"
        if (!isRfc1918Prefix(prefix)) return@mapNotNull null
        ParsedRange(prefix, startHost, endHost)
    }
    if (parsed.isEmpty()) return

    val total     = parsed.sumOf { it.endHost - it.startHost + 1 }
    val completed = AtomicInteger(0)
    val client    = buildProbeClient()
    val semaphore = Semaphore(40)

    coroutineScope {
        parsed.flatMap { range ->
            (range.startHost..range.endHost).map { host ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        probeShelly(client, "${range.prefix}.$host")?.let { onFound(it) }
                        onProgress(completed.incrementAndGet(), total)
                    }
                }
            }
        }.awaitAll()
    }
}

/** Probe a single IP; returns name + detected type, or null if no Shelly responds. */
fun probeDeviceAt(ip: String): DeviceProbeResult? =
    probeShelly(buildProbeClient(), ip)?.let { DeviceProbeResult(it.name, it.detectedType) }

/** Returns true if [prefix] (e.g. "192.168.1") is within an RFC1918 private address range. */
private fun isRfc1918Prefix(prefix: String): Boolean {
    val parts = prefix.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return false
    val (a, b) = parts
    return a == 10 ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168)
}

@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
private fun buildProbeClient(): OkHttpClient {
    // Shelly devices use self-signed certs; this client is only used for LAN discovery probes.
    val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
    val sslContext = SSLContext.getInstance("TLS").also {
        it.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }
    return OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAll)
        .hostnameVerifier { _, _ -> true }
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private fun probeShelly(client: OkHttpClient, ip: String): DiscoveredDevice? {
    for (scheme in listOf("http", "https")) {
        try {
            val req = Request.Builder().url("$scheme://$ip/shelly").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body.string()
                if (body.isBlank()) return@use
                val j = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return@use
                val name = j["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: j["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: j["hostname"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "Shelly @ $ip"
                return DiscoveredDevice(name, ip, DiscoverySource.SUBNET, detectDeviceTypeFromJson(j))
            }
        } catch (_: Exception) {}
    }
    return null
}

@Suppress("DEPRECATION")
fun discoverViaMdns(context: Context): Flow<DiscoveredDevice> = callbackFlow {
    val nsdManager  = context.getSystemService(NsdManager::class.java)
    val probeClient = buildProbeClient()
    val pending     = mutableListOf<NsdServiceInfo>()

    fun resolveNext() {
        val next = synchronized(pending) { pending.removeFirstOrNull() } ?: return
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { resolveNext() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val ip   = info.host?.hostAddress ?: return resolveNext()
                val name = info.serviceName.removePrefix("shelly").trimStart('-')
                    .ifBlank { info.serviceName }.replaceFirstChar { it.uppercase() }
                // Detect type with a background HTTP probe so we don't block the NSD thread.
                launch(Dispatchers.IO) {
                    val type = runCatching {
                        val req = Request.Builder().url("http://$ip/shelly").get().build()
                        probeClient.newCall(req).execute().use { resp ->
                            val body = if (resp.isSuccessful) resp.body.string() else ""
                            if (body.isBlank()) return@runCatching DeviceType.UNKNOWN
                            val j = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                            j?.let { detectDeviceTypeFromJson(it) } ?: DeviceType.UNKNOWN
                        }
                    }.getOrDefault(DeviceType.UNKNOWN)
                    trySend(DiscoveredDevice(name, ip, DiscoverySource.MDNS, type))
                }
                resolveNext()
            }
        })
    }

    val listener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName.contains("shelly", ignoreCase = true)) {
                synchronized(pending) { pending.add(info) }
                if (pending.size == 1) resolveNext()
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
    }

    nsdManager.discoverServices("_shelly._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose { runCatching { nsdManager.stopServiceDiscovery(listener) } }
}
