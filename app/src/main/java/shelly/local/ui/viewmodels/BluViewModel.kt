package shelly.local.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import shelly.local.data.DeviceRepository
import shelly.local.data.Formats
import shelly.local.model.BluDevice
import shelly.local.model.Device
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One BLU sensor, kept up to date by asking the Shelly it is heard through.
 *
 * The sensor itself is asleep almost all the time and speaks when it feels like
 * it -- a BLU H&T sends a packet every few minutes, sooner if something moves.
 * So polling is about the host's copy going stale, not about the sensor, and
 * ten seconds is frequent enough to feel live without asking a plug for its
 * whole component list every breath.
 */
class BluViewModel(
    private val repo: DeviceRepository,
    private val deviceId: String,
) : ViewModel() {

    data class UiState(
        val device: Device? = null,
        val host: Device? = null,
        val sensor: BluDevice? = null,
        val loading: Boolean = true,
        val error: String? = null,
    ) {
        /** When the host last heard from it, or null while it never has. */
        fun lastSeenText(formats: Formats): String? =
            sensor?.lastSeenUtc?.takeIf { it > 0 }?.let { formats.dateTime(it * 1000) }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    fun start() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val devices = runCatching { repo.getAllDevices() }.getOrDefault(emptyList())
            val device = devices.find { it.id == deviceId }
            _uiState.update { it.copy(
                device = device,
                host = devices.find { candidate -> candidate.id == device?.hostDeviceId },
            ) }
            if (device == null) {
                _uiState.update { it.copy(loading = false) }
                return@launch
            }
            while (true) {
                val result = runCatching { repo.bluState(device) }
                _uiState.update { s ->
                    // A reading that could not be fetched leaves the last one in
                    // place and says so, rather than blanking the screen: the
                    // number was true when it arrived, and its timestamp is
                    // right there to say how long ago that was.
                    s.copy(
                        loading = false,
                        sensor = result.getOrNull() ?: s.sensor,
                        error = result.exceptionOrNull()?.let { e -> e.message ?: e.toString() },
                    )
                }
                delay(10_000)
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
    }

    class Factory(
        private val repo: DeviceRepository,
        private val deviceId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BluViewModel(repo, deviceId) as T
    }
}
