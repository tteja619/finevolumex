package dev.finevolume.app.backend

import dev.finevolume.app.adb.WirelessAdbController
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.domain.model.BackendType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the high-resolution player backend only while FineVolume's own
 * Wireless ADB shell helper has passed its UID and IPC checks. Otherwise the
 * public Android AudioManager backend remains active.
 */
@Singleton
class BackendSelector @Inject constructor(
    private val nativeBackend: NativeVolumeBackend,
    private val wirelessAdbBackend: WirelessAdbPlayerBackend,
    private val wirelessAdbController: WirelessAdbController,
    private val fallbackBackend: FallbackBackend
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _activeBackend = MutableStateFlow<VolumeBackend>(nativeBackend)
    val activeBackend: StateFlow<VolumeBackend> = _activeBackend

    init {
        scope.launch {
            wirelessAdbController.status.collect { status ->
                _activeBackend.value = if (status.isReady) wirelessAdbBackend else nativeBackend
            }
        }
    }

    fun retryWirelessAdb() {
        scope.launch {
            wirelessAdbController.reconnect()
        }
    }

    fun currentCapabilities(): VolumeCapabilities = activeBackend.value.capabilities()

    fun currentBackendType(): BackendType = activeBackend.value.type
}
