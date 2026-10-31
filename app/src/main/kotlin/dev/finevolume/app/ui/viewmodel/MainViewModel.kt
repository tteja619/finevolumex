package dev.finevolume.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.finevolume.app.adb.WirelessAdbController
import dev.finevolume.app.adb.WirelessAdbStatus
import dev.finevolume.app.backend.BackendSelector
import dev.finevolume.app.data.AppSettings
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val backendSelector: BackendSelector,
    private val wirelessAdbController: WirelessAdbController
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Lazily, AppSettings())

    val activeBackendCapabilities: StateFlow<VolumeCapabilities> = backendSelector.activeBackend
        .map { it.capabilities() }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            VolumeCapabilities(15, false, false, BackendType.NATIVE, null)
        )

    val wirelessAdbStatus: StateFlow<WirelessAdbStatus> = wirelessAdbController.status

    fun startWirelessAdbWatchdog() {
        wirelessAdbController.startWatchdog()
    }

    fun pairWirelessAdb(port: String, code: String) {
        val pairingPort = port.toIntOrNull()
        if (pairingPort == null) return
        viewModelScope.launch {
            wirelessAdbController.pair(pairingPort, code)
        }
    }

    fun reconnectWirelessAdb() {
        backendSelector.retryWirelessAdb()
    }

    fun forgetWirelessAdbPairing() {
        wirelessAdbController.forgetPairing()
    }

    fun updateIsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateIsEnabled(enabled) }
    }

    fun updateMediaResolution(resolution: Float) {
        viewModelScope.launch { settingsRepository.updateMediaResolution(resolution.toInt()) }
    }

    fun updateMediaIncrement(increment: Float) {
        viewModelScope.launch { settingsRepository.updateMediaIncrement(increment.toInt()) }
    }

    fun updateLongPressRepeatInterval(ms: Float) {
        viewModelScope.launch { settingsRepository.updateLongPressRepeatInterval(ms.toInt()) }
    }

    fun updateHapticEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateHapticLevel(if (enabled) 1 else 0) }
    }
}
