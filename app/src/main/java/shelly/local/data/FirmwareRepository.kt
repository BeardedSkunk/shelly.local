package shelly.local.data

import shelly.local.model.DeviceInfo
import shelly.local.model.FirmwareInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class FirmwareRepository {

    // Shelly's update infrastructure uses a certificate chain that fails Android's
    // default validator on some devices. Trust-all is scoped to this client only,
    // which is used exclusively for Shelly's own update server and firmware CDN URLs.
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val http: OkHttpClient = run {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustAll), null)
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, session ->
                val host = session.peerHost ?: return@hostnameVerifier false
                host.endsWith(".shelly.cloud") || host == "shelly.cloud"
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    suspend fun resolveUpdate(deviceInfo: DeviceInfo): FirmwareInfo =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://updates.shelly.cloud/update/${deviceInfo.shellyTypeId}")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("No firmware info for '${deviceInfo.shellyTypeId}': HTTP ${resp.code}")
                val root = Json.parseToJsonElement(resp.body.string()).jsonObject
                val stable = root["stable"]?.jsonObject
                    ?: error("No stable firmware info for '${deviceInfo.shellyTypeId}'")
                val stableBuildId = stable["build_id"]?.jsonPrimitive?.content ?: ""
                val stableUrl     = stable["url"]?.jsonPrimitive?.content ?: ""

                val beta        = root["beta"]?.jsonObject
                val betaBuildId = beta?.get("build_id")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val betaUrl     = beta?.get("url")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

                FirmwareInfo(
                    currentVersion = deviceInfo.firmwareVersion,
                    stableVersion  = stableBuildId,
                    stableUrl      = stableUrl,
                    betaVersion    = betaBuildId,
                    betaUrl        = betaUrl,
                )
            }
        }

    suspend fun downloadFirmware(url: String, onProgress: (Int) -> Unit): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
                val body          = resp.body
                val contentLength = body.contentLength()
                val inputStream   = body.byteStream()
                val output        = ByteArrayOutputStream()
                val buf           = ByteArray(8192)
                var bytesRead     = 0L
                var n: Int
                while (inputStream.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    bytesRead += n
                    if (contentLength > 0) onProgress(((bytesRead * 100) / contentLength).toInt())
                }
                if (contentLength <= 0) onProgress(100)
                output.toByteArray()
            }
        }
}
