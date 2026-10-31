package dev.finevolume.app.backend

import android.media.AudioManager
import dev.finevolume.app.adb.EmbeddedAdbManager
import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.domain.model.VolumeState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Player-level fine attenuation through FineVolume's own embedded ADB shell
 * helper. ADB shell UID is used, not root. Native AudioManager remains the
 * global master stream and is always available.
 */
@Singleton
class AdbPlayerBackend @Inject constructor(
    private val audioManager: AudioManager,
    private val adbManager: EmbeddedAdbManager
) : VolumeBackend {

    override val type = BackendType.SHIZUKU_PLAYER

    @Volatile private var currentSessionGain = 1f
    @Volatile private var cachedMaxVolume = -1

    private fun maximum(stream: AudioStream): Int {
        if (cachedMaxVolume <= 0) {
            cachedMaxVolume = audioManager.getStreamMaxVolume(stream.androidStream)
        }
        return cachedMaxVolume
    }

    override suspend fun getState(stream: AudioStream): VolumeState {
        val max = maximum(stream)
        val current = audioManager.getStreamVolume(stream.androidStream)
        var position = if (max > 0) current.toFloat() / max.toFloat() else 0f
        if (stream == AudioStream.MUSIC && currentSessionGain < 1f && current > 0) {
            val ratio = Math.cbrt(currentSessionGain.toDouble()).toFloat()
            position = ((current * ratio) / max).coerceIn(0f, 1f)
        }
        return VolumeState(
            stream = stream,
            position = position,
            nativeIndex = current,
            nativeMax = max,
            sessionGain = currentSessionGain.takeIf { stream == AudioStream.MUSIC }
        )
    }

    override suspend fun setPosition(stream: AudioStream, position: Float): Result<Unit> =
        runCatching {
            val bounded = position.coerceIn(0f, 1f)
            val max = maximum(stream)
            check(max > 0) { "Android reports an invalid media volume range." }

            val floatIndex = bounded * max
            val nativeIndex = ceil(floatIndex).toInt().coerceIn(0, max)
            val current = audioManager.getStreamVolume(stream.androidStream)
            if (current != nativeIndex) {
                audioManager.setStreamVolume(stream.androidStream, nativeIndex, 0)
            }

            if (stream == AudioStream.MUSIC) {
                val gain = if (nativeIndex == 0) 1f else {
                    val linear = floatIndex / nativeIndex.toFloat()
                    linear * linear * linear
                }.coerceIn(0f, 1f)

                val result = adbManager.setMediaGain(gain)
                result.getOrThrow()
                currentSessionGain = gain
            }
        }

    override fun capabilities(): VolumeCapabilities {
        val max = maximum(AudioStream.MUSIC)
        val ready = adbManager.status.value.audioHelperReady
        return VolumeCapabilities(
            effectiveResolution = if (ready) 1000 else max,
            supportsSubStepGain = ready,
            isBitPerfect = false,
            backendType = BackendType.SHIZUKU_PLAYER,
            limitedReason = if (ready) null else
                "Embedded ADB audio helper is not connected. Native Android volume remains available ($max steps)."
        )
    }

    suspend fun setAppGain(packageName: String, gain: Float): Result<Unit> =
        adbManager.setAppGain(packageName, gain)
}
