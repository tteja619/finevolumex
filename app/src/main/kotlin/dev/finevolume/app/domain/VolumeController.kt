package dev.finevolume.app.domain

import dev.finevolume.app.backend.BackendSelector
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.VolumeState
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import dev.finevolume.app.ui.overlay.VolumeOverlayController

/**
 * High-level API for mutating volume state.
 * Fine positions are cached only while the underlying Android stream index is
 * unchanged. External system volume changes therefore invalidate stale steps.
 */
@Singleton
class VolumeController @Inject constructor(
    private val backendSelector: BackendSelector,
    private val settingsRepository: SettingsRepository,
    private val profileManager: ProfileManager,
    private val volumeOverlayController: VolumeOverlayController
) {
    private val backend get() = backendSelector.activeBackend.value

    fun effectiveResolution(@Suppress("UNUSED_PARAMETER") stream: AudioStream): Int =
        backend.capabilities().effectiveResolution

    fun effectiveResolution(@Suppress("UNUSED_PARAMETER") stream: AudioStream, requested: Int): Int =
        min(requested, backend.capabilities().effectiveResolution)

    suspend fun getState(stream: AudioStream): VolumeState = backend.getState(stream)

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val precisePositions = mutableMapOf<AudioStream, Float>()
    private val preciseNativeIndices = mutableMapOf<AudioStream, Int>()
    private val lastUpdateTime = mutableMapOf<AudioStream, Long>()

    private suspend fun adjust(stream: AudioStream, steps: Int) {
        val resolution = settingsRepository.cachedSettings.mediaResolution.coerceAtLeast(1)
        val delta = steps.toFloat() / resolution
        val now = android.os.SystemClock.uptimeMillis()

        val newPosition = mutex.withLock {
            val currentState = backend.getState(stream)
            val cachedPosition = precisePositions[stream]
            val cacheIsCurrent =
                now - (lastUpdateTime[stream] ?: 0L) < CACHE_WINDOW_MS &&
                cachedPosition != null &&
                preciseNativeIndices[stream] == currentState.nativeIndex

            val currentPosition = if (cacheIsCurrent) {
                cachedPosition!!
            } else {
                currentState.position
            }

            val nextPosition = (currentPosition + delta).coerceIn(0f, 1f)
            backend.setPosition(stream, nextPosition)

            // Record the native index after applying the change. If Samsung
            // SystemUI, an accessory, or another controller changes it later,
            // the next step reads the fresh system position instead of stale cache.
            val appliedState = backend.getState(stream)
            precisePositions[stream] = nextPosition
            preciseNativeIndices[stream] = appliedState.nativeIndex
            lastUpdateTime[stream] = now
            nextPosition
        }

        if (stream == AudioStream.MUSIC) volumeOverlayController.show(newPosition)
    }

    suspend fun stepUp(stream: AudioStream, steps: Int) {
        adjust(stream, steps.coerceAtLeast(1))
    }

    suspend fun stepDown(stream: AudioStream, steps: Int) {
        adjust(stream, -steps.coerceAtLeast(1))
    }

    suspend fun setAbsolute(stream: AudioStream, position: Float) {
        val nextPosition = position.coerceIn(0f, 1f)
        mutex.withLock {
            backend.setPosition(stream, nextPosition)
            val appliedState = backend.getState(stream)
            precisePositions[stream] = nextPosition
            preciseNativeIndices[stream] = appliedState.nativeIndex
            lastUpdateTime[stream] = android.os.SystemClock.uptimeMillis()
        }
    }

    suspend fun invalidatePrecisePosition(stream: AudioStream) {
        mutex.withLock {
            precisePositions.remove(stream)
            preciseNativeIndices.remove(stream)
            lastUpdateTime.remove(stream)
        }
    }

    companion object {
        private const val CACHE_WINDOW_MS = 1500L
    }
}
