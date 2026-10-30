package dev.finevolume.app.shizuku

import android.content.Context
import android.media.AudioPlaybackConfiguration
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.system.exitProcess
import androidx.annotation.Keep

/**
 * Runs inside the Shizuku user-service process and controls active player proxies.
 * Per-app gains are restored by the app process whenever this service is rebound.
 */
@Keep
class FineVolumeUserService : IFineVolumeService.Stub() {

    private var audioManager: AudioManager? = null
    private var packageManager: android.content.pm.PackageManager? = null
    private var getPlayerProxyMethod: java.lang.reflect.Method? = null
    private var setVolumeMethod: java.lang.reflect.Method? = null
    private var isActiveMethod: java.lang.reflect.Method? = null
    private var getPlayerStateMethod: java.lang.reflect.Method? = null
    private var getClientUidMethod: java.lang.reflect.Method? = null
    private var clientUidField: java.lang.reflect.Field? = null
    private var playbackHandler: Handler? = null

    private val appGainMap = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val uidPackageCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    @Volatile private var sessionGain: Float = 1.0f

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            // A media app may replace its player while continuing playback. Reapply the
            // saved gain to every newly reported player, not just the original proxy.
            applySavedGains(configs ?: runCatching {
                audioManager?.activePlaybackConfigurations?.toMutableList()
            }.getOrNull().orEmpty())
        }
    }

    init {
        try {
            if (Looper.myLooper() == null) Looper.prepare()
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getDeclaredMethod("systemMain").invoke(null)
            val systemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
                .invoke(activityThread) as Context
            audioManager = systemContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            packageManager = systemContext.packageManager

            getPlayerProxyMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerProxy").apply { isAccessible = true }
            }.getOrNull()
            isActiveMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("isActive").apply { isAccessible = true }
            }.getOrNull()
            getPlayerStateMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getPlayerState").apply { isAccessible = true }
            }.getOrNull() ?: runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState").apply { isAccessible = true }
            }.getOrNull()
            getClientUidMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getClientUid").apply { isAccessible = true }
            }.getOrNull() ?: runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid").apply { isAccessible = true }
            }.getOrNull()
            clientUidField = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredField("mClientUid").apply { isAccessible = true }
            }.getOrNull()

            val handler = Handler(Looper.getMainLooper())
            playbackHandler = handler
            audioManager?.registerAudioPlaybackCallback(playbackCallback, handler)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not initialize playback monitoring", error)
        }
    }

    private fun getUidForConfig(config: AudioPlaybackConfiguration): Int {
        try {
            val uid = getClientUidMethod?.invoke(config) as? Int
            if (uid != null && uid > 0) return uid
        } catch (_: Throwable) {}
        try {
            val uid = clientUidField?.getInt(config)
            if (uid != null && uid > 0) return uid
        } catch (_: Throwable) {}
        return -1
    }

    private fun getPackageNameForUid(uid: Int): String? {
        if (uid <= 1000) return null
        uidPackageCache[uid]?.let { return it }
        val pm = packageManager ?: return null
        return try {
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull {
                it !in SYSTEM_PACKAGES && !it.startsWith("com.android.keyguard")
            }
            if (pkg != null) uidPackageCache[uid] = pkg
            pkg
        } catch (_: Throwable) { null }
    }

    private fun setPlayerGain(config: AudioPlaybackConfiguration, gain: Float) {
        try {
            val proxy = getPlayerProxyMethod?.invoke(config) ?: return
            var method = setVolumeMethod
            if (method == null || method.declaringClass != proxy.javaClass) {
                method = runCatching {
                    proxy.javaClass.getDeclaredMethod("setVolume", Float::class.javaPrimitiveType).apply {
                        isAccessible = true
                    }
                }.getOrNull()
                setVolumeMethod = method
            }
            method?.invoke(proxy, gain.coerceIn(0f, 1f))
        } catch (error: Throwable) {
            Log.d(TAG, "Unable to update player gain", error)
        }
    }

    private fun applySavedGains(configs: List<AudioPlaybackConfiguration>) {
        for (config in configs) {
            val uid = getUidForConfig(config)
            val pkg = if (uid > 0) getPackageNameForUid(uid) else null
            val appGain = if (pkg != null) appGainMap[pkg] else null
            if (appGain != null) setPlayerGain(config, appGain * sessionGain)
        }
    }

    override fun applySessionGain(streamType: Int, gain: Float) {
        if (streamType != AudioManager.STREAM_MUSIC) return
        sessionGain = gain.coerceIn(0f, 1f)
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        for (config in configs) {
            val uid = getUidForConfig(config)
            val pkg = if (uid > 0) getPackageNameForUid(uid) else null
            val appGain = if (pkg != null) appGainMap[pkg] ?: 1.0f else 1.0f
            setPlayerGain(config, appGain * sessionGain)
        }
    }

    override fun applyAppGain(packageName: String, gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        appGainMap[packageName] = clamped
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        for (config in configs) {
            val uid = getUidForConfig(config)
            if (uid > 0 && getPackageNameForUid(uid) == packageName) {
                setPlayerGain(config, clamped * sessionGain)
            }
        }
    }

    override fun getActiveAppPackages(): List<String> {
        val result = mutableSetOf<String>()
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        for (config in configs) {
            val playerState = runCatching { getPlayerStateMethod?.invoke(config) as? Int }.getOrNull()
            val playing = if (playerState != null) playerState == 2 else {
                runCatching { isActiveMethod?.invoke(config) as? Boolean }.getOrNull() ?: false
            }
            if (playing) {
                val uid = getUidForConfig(config)
                if (uid > 0) getPackageNameForUid(uid)?.let { if (it !in SYSTEM_PACKAGES) result.add(it) }
            }
        }
        return result.toList()
    }

    override fun getAppGain(packageName: String): Float = appGainMap[packageName] ?: 1.0f

    override fun getActiveSessionCount(streamType: Int): Int {
        val configs = runCatching { audioManager?.activePlaybackConfigurations.orEmpty() }.getOrDefault(emptyList())
        return configs.count {
            runCatching { isActiveMethod?.invoke(it) as? Boolean }.getOrNull() == true
        }
    }

    override fun destroy() {
        try { audioManager?.unregisterAudioPlaybackCallback(playbackCallback) } catch (_: Throwable) {}
        Log.i(TAG, "FineVolumeUserService destroy")
        exitProcess(0)
    }

    companion object {
        private const val TAG = "FineVolumeUserService"
        private val SYSTEM_PACKAGES = setOf("android", "com.android.systemui", "dev.finevolume.app")
    }
}
