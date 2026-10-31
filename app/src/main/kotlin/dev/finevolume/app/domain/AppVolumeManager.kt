package dev.finevolume.app.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.finevolume.app.adb.WirelessAdbController
import dev.finevolume.app.data.AppMetadataCache
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.model.AppVolumeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class AppVolumeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wirelessAdbController: WirelessAdbController,
    private val appMetadataCache: AppMetadataCache,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _activeAppSessions = MutableStateFlow<List<AppVolumeSession>>(emptyList())
    val activeAppSessions: StateFlow<List<AppVolumeSession>> = _activeAppSessions.asStateFlow()
    private val appVolumeCache = ConcurrentHashMap<String, Float>()

    fun startObserving() {
        refreshActiveSessions()
        scope.launch {
            wirelessAdbController.status.collect { status ->
                if (status.isReady) refreshActiveSessions()
                else _activeAppSessions.value = emptyList()
            }
        }
    }

    fun stopObserving() {
        _activeAppSessions.value = emptyList()
    }

    fun refreshActiveSessions() {
        scope.launch(Dispatchers.IO) {
            val packages = runCatching { wirelessAdbController.getActiveApps() }.getOrDefault(emptyList())
            val sessions = packages.map { packageName ->
                val metadata = appMetadataCache.getAppMetadata(packageName)
                val gain = appVolumeCache[packageName]
                    ?: runCatching { wirelessAdbController.getAppGain(packageName) }.getOrDefault(1f)
                        .also { appVolumeCache[packageName] = it }
                AppVolumeSession(
                    packageName = packageName,
                    appName = metadata.appName,
                    icon = metadata.icon,
                    volume = gain
                )
            }
            _activeAppSessions.value = sessions
        }
    }

    fun setAppVolume(packageName: String, position: Float) {
        val resolution = settingsRepository.cachedSettings.mediaResolution.coerceAtLeast(1)
        val step = (position * resolution).roundToInt().coerceIn(0, resolution)
        val quantized = step.toFloat() / resolution
        appVolumeCache[packageName] = quantized

        _activeAppSessions.value = _activeAppSessions.value.map { session ->
            if (session.packageName == packageName) session.copy(volume = quantized) else session
        }

        scope.launch(Dispatchers.IO) {
            wirelessAdbController.setAppGain(packageName, quantized)
        }
    }

    fun isWirelessAdbReady(): Boolean = wirelessAdbController.status.value.isReady

    suspend fun setLiveCaptionEnabled(enabled: Boolean): Result<Unit> =
        wirelessAdbController.setLiveCaptionEnabled(enabled)
}

