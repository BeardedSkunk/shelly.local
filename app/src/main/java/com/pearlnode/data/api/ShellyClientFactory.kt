package com.pearlnode.data.api

import com.pearlnode.model.Device
import com.pearlnode.model.ShellyGeneration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object ShellyClientFactory {

    // Shared base client; auth variants use newBuilder() to inherit the connection pool.
    private val base = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * For the journal, which answers slowly and gets slower as it fills.
     *
     * A tier read is not a lookup: the script walks its pages block by block to
     * find where `from` lands, in mJS, on a processor that is also sampling.
     * Measured on the solar plug on 20.08.2026, with the watermark four days
     * back: **6,4 s for 2967 bytes**. Against the ordinary ten-second read that
     * is close enough that a busy moment goes over -- and it did. The sync to
     * that plug had been failing since 16.08 without anyone noticing, because a
     * failed sync leaves the last good chart standing.
     *
     * So the journal gets its own patience. It is always background work or an
     * explicit request, never something a screen is waiting on to draw. Raising
     * this treats the symptom; making the script skip whole pages it can rule
     * out by their start time is the cure, and it is not written yet.
     */
    private val journalBase = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    fun buildJournalClient(username: String?, password: String?): OkHttpClient =
        if (username != null && password != null)
            journalBase.newBuilder().authenticator(ShellyAuthenticator(username, password)).build()
        else
            journalBase

    private val firmwareBase = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun buildHttpClient(username: String?, password: String?): OkHttpClient =
        if (username != null && password != null)
            base.newBuilder().authenticator(ShellyAuthenticator(username, password)).build()
        else
            base

    private fun buildFirmwareClient(username: String?, password: String?): OkHttpClient =
        if (username != null && password != null)
            firmwareBase.newBuilder().authenticator(ShellyAuthenticator(username, password)).build()
        else
            firmwareBase

    fun detectGeneration(ip: String, username: String?, password: String?): ShellyGeneration {
        val client = buildHttpClient(username, password)
        return try {
            val req = Request.Builder().url("http://$ip/shelly").get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body.string().takeIf { it.isNotBlank() } ?: return ShellyGeneration.UNKNOWN
                val j = Json.parseToJsonElement(body).jsonObject
                val gen = j["gen"]?.jsonPrimitive?.content?.toIntOrNull()
                if (gen == null || gen == 1) ShellyGeneration.GEN1 else ShellyGeneration.GEN2
            }
        } catch (_: Exception) {
            ShellyGeneration.UNKNOWN
        }
    }

    fun clientFor(device: Device, username: String?, password: String?): ShellyApiClient {
        val http = buildHttpClient(username, password)
        return when (device.generation) {
            ShellyGeneration.GEN2 -> Gen2Client(device.ipAddress, http, device.type)
            else                  -> Gen1Client(device.ipAddress, http, device.type)
        }
    }

    fun firmwareClientFor(device: Device, username: String?, password: String?): ShellyApiClient {
        val http = buildFirmwareClient(username, password)
        return when (device.generation) {
            ShellyGeneration.GEN2 -> Gen2Client(device.ipAddress, http, device.type)
            else                  -> Gen1Client(device.ipAddress, http, device.type)
        }
    }
}
