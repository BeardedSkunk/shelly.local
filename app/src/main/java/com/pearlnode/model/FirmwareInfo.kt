package com.pearlnode.model

enum class FirmwareChannel { STABLE, BETA }

data class DeviceInfo(val shellyTypeId: String, val firmwareVersion: String, val generation: ShellyGeneration)

// Shelly versions can be "20230913-114532/v1.14.0-gcb84623" or just "v1.14.0-gcb84623".
// Normalize to the part after the last '/' so comparisons work regardless of format.
private fun String.normalizeVersion() = substringAfterLast('/').ifBlank { this }

// Extract the YYYY-MM-DD date from the "YYYYMMDD-HHMMSS/version" prefix, if present.
fun String.firmwareDate(): String? {
    val raw = substringBefore('/').substringBefore('-')
    if (raw.length != 8 || raw.any { !it.isDigit() }) return null
    return "${raw.substring(6, 8)}.${raw.substring(4, 6)}.${raw.substring(0, 4)}"
}

data class FirmwareInfo(
    val currentVersion: String,
    val stableVersion: String,
    val stableUrl: String,
    val betaVersion: String? = null,
    val betaUrl: String? = null,
) {
    val hasStableUpdate: Boolean get() =
        stableVersion.isNotBlank() && stableVersion.normalizeVersion() != currentVersion.normalizeVersion()

    val hasBetaUpdate: Boolean get() =
        betaVersion != null && betaVersion.normalizeVersion() != currentVersion.normalizeVersion()

    fun hasUpdate(channel: FirmwareChannel) = when (channel) {
        FirmwareChannel.STABLE -> hasStableUpdate
        FirmwareChannel.BETA -> hasBetaUpdate || hasStableUpdate
    }

    fun targetVersion(channel: FirmwareChannel) = when (channel) {
        FirmwareChannel.STABLE -> stableVersion
        FirmwareChannel.BETA -> betaVersion ?: stableVersion
    }

    fun targetUrl(channel: FirmwareChannel) = when (channel) {
        FirmwareChannel.STABLE -> stableUrl
        FirmwareChannel.BETA -> betaUrl ?: stableUrl
    }
}
