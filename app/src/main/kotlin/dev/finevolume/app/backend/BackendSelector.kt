package dev.finevolume.app.backend

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.shizuku.FineVolumeUserService
import dev.finevolume.app.shizuku.IFineVolumeService
import dev.finevolume.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackendSelector @Inject constructor(
    private val nativeBackend: NativeVolumeBackend,
    private val shizukuPlayerBackend: ShizukuPlayerBackend,
    private val fallbackBackend: FallbackBackend,
    private val shizukuManager: ShizukuManager,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _activeBackend = MutableStateFlow<VolumeBackend>(nativeBackend)
    val activeBackend: StateFlow<VolumeBackend> = _activeBackend

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = IFineVolumeService.Stub.asInterface(service) ?: return
            shizukuPlayerBackend.bindUserService(remote)
            _activeBackend.value = shizukuPlayerBackend

            // The Shizuku process has its own lifetime and memory. Restore every saved
            // per-app gain before relying on it after a bind/rebind.
            scope.launch(Dispatchers.IO) {
                val savedGains = runCatching { settingsRepository.getAppVolumeGains() }.getOrDefault(emptyMap())
                savedGains.forEach { (packageName, gain) ->
                    runCatching { remote.applyAppGain(packageName, gain) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuPlayerBackend.unbindUserService()
            _activeBackend.value = nativeBackend
        }
    }

    init {
        scope.launch {
            combine(shizukuManager.isConnected, shizukuManager.permissionGranted) { connected, granted ->
                connected && granted
            }.collect { canBind ->
                if (canBind) {
                    try {
                        val args = Shizuku.UserServiceArgs(
                            ComponentName("dev.finevolume.app", FineVolumeUserService::class.java.name)
                        ).daemon(false).processNameSuffix("service").debuggable(false).version(2)
                        Shizuku.bindUserService(args, serviceConnection)
                    } catch (error: Exception) {
                        error.printStackTrace()
                        _activeBackend.value = nativeBackend
                    }
                } else {
                    try {
                        val args = Shizuku.UserServiceArgs(
                            ComponentName("dev.finevolume.app", FineVolumeUserService::class.java.name)
                        ).daemon(false).processNameSuffix("service").debuggable(false).version(2)
                        Shizuku.unbindUserService(args, serviceConnection, true)
                    } catch (_: Exception) {}
                    _activeBackend.value = nativeBackend
                }
            }
        }
    }
}
