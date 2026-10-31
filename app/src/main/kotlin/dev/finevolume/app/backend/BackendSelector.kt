package dev.finevolume.app.backend

import dev.finevolume.app.adb.EmbeddedAdbManager
import dev.finevolume.app.backend.VolumeBackend
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
 * Selects embedded ADB player attenuation only after its shell helper has
 * completed a UID-2000 handshake. Native AudioManager is always the fallback.
 */
@Singleton
class BackendSelector @Inject constructor(
    private val nativeBackend: NativeVolumeBackend,
    private val adbPlayerBackend: AdbPlayerBackend,
    private val fallbackBackend: FallbackBackend,
    private val embeddedAdbManager: EmbeddedAdbManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _activeBackend = MutableStateFlow<VolumeBackend>(nativeBackend)
    val activeBackend: StateFlow<VolumeBackend> = _activeBackend

    init {
        scope.launch {
            embeddedAdbManager.status.collect { status ->
                _activeBackend.value = if (status.isConnected && status.audioHelperReady) {
                    adbPlayerBackend
                } else {
                    nativeBackend
                }
            }
        }
    }

    fun activeBackendName(): String =
        when (_activeBackend.value.type) {
            BackendType.NATIVE -> "Native AudioManager"
            BackendType.ADB_PLAYER -> "Embedded ADB player helper"
            BackendType.FALLBACK -> "Read-only fallback"
        }
}
