package com.pearlnode.ui.viewmodels

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pearlnode.data.DeviceRepository
import com.pearlnode.data.discovery.DiscoveredDevice
import com.pearlnode.model.BluDevice
import com.pearlnode.data.discovery.ScanRange
import com.pearlnode.data.discovery.blePermissionsToRequest
import com.pearlnode.data.discovery.detectCurrentSubnet
import com.pearlnode.data.discovery.discoverViaBle
import com.pearlnode.data.discovery.discoverViaMdns
import com.pearlnode.data.discovery.hasBlePermissions
import com.pearlnode.data.discovery.probeDeviceAt
import com.pearlnode.data.discovery.scanRanges
import com.pearlnode.model.Device
import com.pearlnode.model.DeviceType
import com.pearlnode.model.ShellyGeneration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * A BLU sensor on offer: the sensor as its host describes it, and the host it
 * was found on -- which is not decoration, since it is the only way back to it.
 */
data class DiscoveredBlu(
    val host: Device,
    val sensor: BluDevice,
) {
    /** What it called itself when it was paired, or what it measures. */
    val suggestedName: String
        get() = sensor.name?.takeIf { it.isNotBlank() } ?: sensor.type.label
}

data class AddEditUiState(
    val name: String = "",
    val ip: String = "",
    val ipError: String? = null,
    val type: DeviceType = DeviceType.UNKNOWN,
    val username: String = "",
    val password: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    // Manual-add bottom sheet visibility
    val showManualForm: Boolean = false,
    // Discovery
    val scanRanges: List<ScanRange> = listOf(ScanRange()),
    val discovering: Boolean = false,
    val scanProgress: Int = 0,
    val scanTotal: Int = 254,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val discoveryError: String? = null,
    /**
     * BLU sensors found on the Shellys already added, minus the ones that are
     * already here. They have no address of their own, so they cannot be found
     * by scanning for one -- the host has to be asked.
     */
    val discoveredBlu: List<DiscoveredBlu> = emptyList(),
    val scanningBlu: Boolean = false,
    // IP probe for manual add
    val detecting: Boolean = false,
    val detectError: String? = null,
)

class AddEditDeviceViewModel(
    private val repo: DeviceRepository,
    private val deviceId: String?,
    private val appContext: Context,
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences("shelly_discovery", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(AddEditUiState())
    val uiState: StateFlow<AddEditUiState> = _uiState.asStateFlow()

    private var existingDevice: Device? = null
    private var discoveryJob: Job? = null

    init {
        val savedRanges = loadSavedRanges()
        if (savedRanges != null) {
            _uiState.update { it.copy(scanRanges = savedRanges) }
        } else {
            val prefix = detectCurrentSubnet(appContext)
            _uiState.update { it.copy(scanRanges = listOf(ScanRange("$prefix.1", "$prefix.254"))) }
        }

        if (deviceId != null) {
            viewModelScope.launch {
                val device = repo.devices
                    .mapNotNull { list -> list.find { it.id == deviceId } }
                    .first()
                existingDevice = device
                val credentials = repo.getCredentials(device.id)
                _uiState.update { s ->
                    s.copy(
                        name = device.name, ip = device.ipAddress, type = device.type,
                        username = credentials?.first ?: "", password = credentials?.second ?: "",
                    )
                }
            }
        }
    }

    // Field setters

    fun setName(v: String)     = _uiState.update { it.copy(name = v) }
    fun setIp(v: String)       = _uiState.update { it.copy(ip = v, ipError = null, detectError = null) }
    fun setType(v: DeviceType) = _uiState.update { it.copy(type = v) }
    fun setUsername(v: String) = _uiState.update { it.copy(username = v) }
    fun setPassword(v: String) = _uiState.update { it.copy(password = v) }

    // Scan range management

    fun addRange() {
        val last = _uiState.value.scanRanges.lastOrNull()
        val suggestion = if (last != null) {
            val parts = last.startIp.split(".")
            if (parts.size == 4) {
                val third = parts[2].toIntOrNull()?.plus(1)?.coerceAtMost(254)
                if (third != null)
                    ScanRange("${parts[0]}.${parts[1]}.$third.1", "${parts[0]}.${parts[1]}.$third.254")
                else ScanRange()
            } else ScanRange()
        } else ScanRange()
        _uiState.update { it.copy(scanRanges = it.scanRanges + suggestion) }
    }

    fun removeRange(index: Int) {
        _uiState.update { it.copy(scanRanges = it.scanRanges.filterIndexed { i, _ -> i != index }) }
    }

    fun updateRangeStart(index: Int, startIp: String) {
        _uiState.update { state ->
            state.copy(scanRanges = state.scanRanges.mapIndexed { i, r ->
                if (i != index) r
                else {
                    val autoEnd = autoEndFor(startIp)
                    r.copy(startIp = startIp, endIp = autoEnd ?: r.endIp)
                }
            })
        }
    }

    fun updateRangeEnd(index: Int, endIp: String) {
        _uiState.update { state ->
            state.copy(scanRanges = state.scanRanges.mapIndexed { i, r ->
                if (i != index) r else r.copy(endIp = endIp)
            })
        }
    }

    private fun autoEndFor(startIp: String): String? {
        val parts = startIp.trim().split(".")
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.254"
    }

    // Discovery

    fun startDiscovery(
        context: Context,
        blePermLauncher: ActivityResultLauncher<Array<String>>,
    ) {
        discoveryJob?.cancel()

        val ranges = _uiState.value.scanRanges.filter {
            it.startIp.isNotBlank() && it.endIp.isNotBlank()
        }
        saveRanges(ranges)
        val total = ranges.sumOf { r ->
            val s = r.startIp.split(".").lastOrNull()?.toIntOrNull() ?: 1
            val e = r.endIp.split(".").lastOrNull()?.toIntOrNull() ?: 254
            maxOf(0, e - s + 1)
        }

        _uiState.update { it.copy(
            discovering = true, discovered = emptyList(), discoveredBlu = emptyList(),
            discoveryError = null, scanProgress = 0, scanTotal = total.coerceAtLeast(1),
        ) }

        discoveryJob = viewModelScope.launch {
            // What is already here, so the scan can leave it out. A list of
            // devices the app is already talking to is noise, not a result.
            knownAddresses = runCatching { repo.getAllDevices() }.getOrDefault(emptyList())
                .map { it.ipAddress }.filter { it.isNotBlank() }.toSet()

            // The paired sensors, which no amount of scanning would turn up.
            launch { scanForBluSensors() }

            // Subnet scan; sets discovering=false when done
            launch {
                runCatching {
                    scanRanges(
                        ranges = ranges,
                        onFound = { addToDiscovered(it) },
                        onProgress = { done, t ->
                            _uiState.update { it.copy(scanProgress = done, scanTotal = t) }
                        },
                    )
                }.onFailure { e -> _uiState.update { it.copy(discoveryError = e.message) } }
                _uiState.update { it.copy(discovering = false) }
            }

            // mDNS alongside subnet scan; instant results, no permissions needed
            launch {
                runCatching { discoverViaMdns(context).collect { addToDiscovered(it) } }
            }

            // BLE: surface provisioning devices; request permissions if missing
            if (hasBlePermissions(context)) {
                launch {
                    runCatching {
                        discoverViaBle(context).collect { device ->
                            _uiState.update { s ->
                                if (s.discovered.none { it.name == device.name })
                                    s.copy(discovered = s.discovered + device)
                                else s
                            }
                        }
                    }
                }
            } else {
                blePermLauncher.launch(blePermissionsToRequest())
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        _uiState.update { it.copy(discovering = false) }
    }

    /**
     * Addresses already in the app, so a scan does not offer them again.
     *
     * Filled before a scan starts and left alone afterwards: a device added
     * from the list mid-scan should not vanish from under the finger that is
     * about to tap it, and the list is thrown away when the screen closes.
     */
    private var knownAddresses: Set<String> = emptySet()

    private fun addToDiscovered(device: DiscoveredDevice) {
        if (device.ipAddress.isNotBlank() && device.ipAddress in knownAddresses) return
        _uiState.update { s ->
            if (s.discovered.none { it.ipAddress == device.ipAddress && device.ipAddress.isNotBlank() })
                s.copy(discovered = s.discovered + device)
            else s
        }
    }

    /**
     * Asks every Shelly already added which BLU sensors it is paired with.
     *
     * This is the only way to find one: it has no address, answers nothing
     * itself, and is only visible in the components of the Shelly that holds
     * its readings. Which is also why a sensor can only be added after its host
     * -- the host's credentials are what opens the door.
     *
     * A Shelly that cannot be reached, or is too old to pair with anything,
     * simply contributes nothing.
     */
    private suspend fun scanForBluSensors() {
        _uiState.update { it.copy(scanningBlu = true) }
        val devices = runCatching { repo.getAllDevices() }.getOrDefault(emptyList())
        val taken = devices.mapNotNull { it.bleAddress?.lowercase() }.toSet()
        for (host in devices.filter { !it.isBluSensor }) {
            val found = runCatching { repo.bluDevices(host) }.getOrNull().orEmpty()
            val fresh = found
                .filter { it.address.lowercase() !in taken }
                .map { DiscoveredBlu(host = host, sensor = it) }
            if (fresh.isEmpty()) continue
            _uiState.update { s ->
                val already = s.discoveredBlu.map { it.sensor.address.lowercase() }.toSet()
                s.copy(discoveredBlu = s.discoveredBlu +
                    fresh.filter { it.sensor.address.lowercase() !in already })
            }
        }
        _uiState.update { it.copy(scanningBlu = false) }
    }

    /**
     * Adds a BLU sensor: a real row of its own, pointing at the Shelly it is
     * heard through. No address and no credentials -- both belong to the host,
     * and copying them here would leave two places to keep in step.
     */
    fun addBluSensor(found: DiscoveredBlu, name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val result = runCatching {
                repo.addDevice(
                    Device(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name.ifBlank { found.suggestedName },
                        ipAddress = "",
                        type = found.sensor.type,
                        generation = found.host.generation,
                        hostDeviceId = found.host.id,
                        bleAddress = found.sensor.address,
                    ),
                    null, null,
                )
            }
            _uiState.update { s ->
                if (result.isSuccess) s.copy(
                    saving = false, saved = true,
                    discoveredBlu = s.discoveredBlu.filter { it.sensor.address != found.sensor.address },
                ) else s.copy(saving = false, error = result.exceptionOrNull()?.message)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onBlePermissionsResult(results: Map<String, Boolean>) { /* picked up on next scan */ }

    // Manual form

    fun openManualForm() = _uiState.update { it.copy(showManualForm = true, detectError = null) }
    fun closeManualForm() = _uiState.update { it.copy(showManualForm = false) }

    /** Pre-fill the manual form from a discovered device and open the bottom sheet. */
    fun selectDiscovered(device: DiscoveredDevice) {
        _uiState.update { it.copy(
            name = device.name,
            ip   = device.ipAddress,
            type = device.detectedType,
            showManualForm = true,
            detectError = null,
        ) }
    }

    /** Probe the currently entered IP to auto-fill name and device type. */
    fun detectDevice() {
        val ip = _uiState.value.ip.trim()
        if (!isValidIp(ip)) {
            _uiState.update { it.copy(ipError = "Enter a valid IP address first") }
            return
        }
        _uiState.update { it.copy(detecting = true, detectError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = probeDeviceAt(ip)
            _uiState.update {
                if (result != null) {
                    it.copy(
                        detecting = false,
                        name = result.name,
                        type = result.type,
                        detectError = null,
                    )
                } else {
                    it.copy(detecting = false, detectError = "No Shelly device found at $ip")
                }
            }
        }
    }

    // Save

    fun save() {
        val state = _uiState.value
        if (state.name.isBlank()) { _uiState.update { it.copy(error = "Name is required") }; return }
        if (!isValidIp(state.ip)) { _uiState.update { it.copy(ipError = "Enter a valid IP address") }; return }
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val device = Device(
                    id         = existingDevice?.id ?: UUID.randomUUID().toString(),
                    name       = state.name.trim(),
                    ipAddress  = state.ip.trim(),
                    type       = state.type,
                    generation = existingDevice?.generation ?: ShellyGeneration.UNKNOWN,
                    hasAuth    = state.username.isNotBlank(),
                )
                val user = state.username.takeIf { it.isNotBlank() }
                val pass = state.password.takeIf { it.isNotBlank() }
                if (existingDevice == null) repo.addDevice(device, user, pass)
                else repo.updateDevice(device, user, pass)
            }.onSuccess {
                _uiState.update { it.copy(saving = false, saved = true) }
            }.onFailure { e ->
                // Said where it went wrong: this field also carries the form's
                // own complaints, and "Connection refused" on its own next to
                // the save button reads like a different problem than it is.
                _uiState.update {
                    it.copy(saving = false, error = "Saving failed: ${e.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return false
        return parts.all { part -> (part.toIntOrNull() ?: return false) in 0..255 }
    }

    private fun loadSavedRanges(): List<ScanRange>? {
        val raw = prefs.getString("scan_ranges", null) ?: return null
        return raw.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size == 2) ScanRange(parts[0].trim(), parts[1].trim()) else null
            }
            .takeIf { it.isNotEmpty() }
    }

    private fun saveRanges(ranges: List<ScanRange>) {
        if (ranges.isEmpty()) return
        prefs.edit { putString("scan_ranges", ranges.joinToString("\n") { "${it.startIp},${it.endIp}" }) }
    }

    override fun onCleared() { discoveryJob?.cancel() }

    class Factory(
        private val repo: DeviceRepository,
        private val deviceId: String?,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return AddEditDeviceViewModel(repo, deviceId, context.applicationContext) as T
        }
    }
}
