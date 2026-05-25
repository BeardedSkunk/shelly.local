package com.pearlnode.model

data class DeviceState(
    val deviceId: String,
    val channels: List<ChannelState>,
    val isOnline: Boolean = true,
)

data class ChannelState(
    val index: Int,
    val isOn: Boolean,
    val power: Double? = null,      // watts, if supported
    val color: RgbColor? = null,    // non-null for RGBW devices
    val brightness: Int? = null,    // 0-100, non-null for dimmer devices
)

data class RgbColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val brightness: Int = 100,
)
