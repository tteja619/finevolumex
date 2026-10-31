package dev.finevolume.app.backend

import android.media.AudioManager
import dev.finevolume.app.adb.WirelessAdbController
import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.domain.model.VolumeState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * FineVolume-owned ADB shell backend.
 *
 * AudioManager sets the shared native media stream index; FineVolume's
 * persistent app_process helper applies fractional player gain within the
 * selected native step. This is software attenuation, not bit-perfect output.
 */
@Singleton
class WirelessAdbPlayerBackend @Inject constructor(
    private val audioManager: AudioManager,
    private val wirelessAdbController: WirelessAdbController
) : VolumeBackend {

    override val type = BackendType.WIRELESS_ADB_PLAYER

    @Volatile private var currentSessionGain = 1.0f
    @Volatile private var cachedMaxVolume = -1

    private fun maxVolume(stream: AudioStream): Int {
        if (cachedMaxVolume <= 0) cachedMaxVolume = audioManager.getStreamMaxVolume(stream.androidStream)
        return cachedMaxVolume
    }

    override suspend fun getState(stream: AudioStream): VolumeState {
        val max = maxVolume(stream)
        val current = audioManager.getStreamVolume(stream.androidStream)
        var position = if (max > 0) current.toFloat() / max.toFloat() else 0f

        if (stream == AudioStream.MUSIC && current > 0 && currentSessionGain < 1f) {
            val linearRatio = Math.cbrt(currentSessionGain.toDouble()).toFloat()
            position = (current * linearRatio / max.coerceAtLeast(1)).coerceIn(0f, 1f)
        }

        return VolumeState(
            stream = stream,
            position = position,
            nativeIndex = current,
            nativeMax = max,
            sessionGain = currentSessionGain.takeIf { stream == AudioStream.MUSIC && wirelessAdbController.status.value.isReady }
        )
    }

    override suspend fun setPosition(stream: AudioStream, position: Float): Result<Unit> = runCatching {
        val target = position.coerceIn(0f, 1f)
        val max = maxVolume(stream)
        if (max <= 0) return@runCatching

        val floatIndex = target * max
        val nativeIndex = ceil(floatIndex).toInt().coerceIn(0, max)
        val actualIndex = audioManager.getStreamVolume(stream.androidStream)

        // Compare with live system state; don't assume FineVolume was the last writer.
        if (actualIndex != nativeIndex) {
            audioManager.setStreamVolume(stream.androidStream, nativeIndex, 0)
        }

        if (stream == AudioStream.MUSIC && wirelessAdbController.status.value.isReady) {
            val fraction = if (nativeIndex == 0) 1f else (floatIndex / nativeIndex).coerceIn(0f, 1f)
            val gain = fraction * fraction * fraction
            val result = wirelessAdbController.setMediaGain(gain)
            result.getOrThrow()
            currentSessionGain = gain
        }
    }

    override fun capabilities(): VolumeCapabilities {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ready = wirelessAdbController.status.value.isReady
        return VolumeCapabilities(
            effectiveResolution = if (ready) 1000 else max,
            supportsSubStepGain = ready,
            isBitPerfect = false,
            backendType = if (ready) BackendType.WIRELESS_ADB_PLAYER else BackendType.NATIVE,
            limitedReason = if (ready) null else
                "Wireless ADB shell helper is not connected. Native media volume remains available at $max hardware steps."
        )
    }

    suspend fun setAppGain(packageName: String, gain: Float): Result<Unit> =
        wirelessAdbController.setAppGain(packageName, gain)

    suspend fun getAppGain(packageName: String): Float =
        wirelessAdbController.getAppGain(packageName)
}
