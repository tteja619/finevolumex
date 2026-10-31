package dev.finevolume.app.privileged

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * FineVolume's persistent audio helper, launched using app_process from its
 * own ADB Wireless Debugging session. Expected identity is ADB shell UID 2000.
 * stdout is reserved exclusively for the newline-delimited JSON protocol.
 */
object FineVolumeShellServer {
    private const val TAG = "FineVolumeShell"
    private const val STREAM_MUSIC = AudioManager.STREAM_MUSIC
    private const val PLAYER_STATE_STARTED = 2

    @JvmStatic
    fun main(args: Array<String>) {
        val output = PrintWriter(System.out, true)
        val server = runCatching { AudioEngine() }.getOrElse { error ->
            output.println(JSONObject().put("type", "fatal").put("message", error.message ?: error.javaClass.simpleName))
            Log.e(TAG, "Audio engine initialization failed", error)
            return
        }

        output.println(
            JSONObject()
                .put("type", "ready")
                .put("uid", Process.myUid())
                .put("pid", Process.myPid())
                .put("protocol", 1)
                .put("ok", true)
        )

        try {
            val systemInput = System::class.java.getField("in").get(null) as java.io.InputStream
            BufferedReader(InputStreamReader(systemInput, Charsets.UTF_8)).use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isBlank()) continue
                    val response = runCatching {
                        server.execute(JSONObject(line))
                    }.getOrElse { error ->
                        Log.w(TAG, "Command failed", error)
                        JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName)
                    }
                    output.println(response)
                    output.flush()
                    if (response.optBoolean("shutdown", false)) break
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Control stream closed", error)
        } finally {
            server.close()
        }
    }

    private class AudioEngine {
        private val callbackThread = HandlerThread("FineVolumeShellAudioObserver").apply { start() }
        private val callbackHandler = Handler(callbackThread.looper)
        private val appGains = ConcurrentHashMap<String, Float>()
        @Volatile private var mediaGain = 1.0f
        private lateinit var audioManager: AudioManager
        private lateinit var packageManager: android.content.pm.PackageManager
        private lateinit var getPlayerProxyMethod: Method
        private var getClientUidMethod: Method? = null
        private var clientUidField: java.lang.reflect.Field? = null
        private var playerStateMethod: Method? = null
        private val setVolumeMethodCache = ConcurrentHashMap<Class<*>, Method>()
        @Volatile private var closed = false

        private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                callbackHandler.post { reapplyAll() }
                callbackHandler.postDelayed({ reapplyAll() }, 120L)
                callbackHandler.postDelayed({ reapplyAll() }, 450L)
            }
        }

        init {
            // Hidden API access is confined to the explicitly shell-UID helper.
            HiddenApiBypass.addHiddenApiExemptions("")
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMain = activityThreadClass.getDeclaredMethod("systemMain").apply { isAccessible = true }
            val activityThread = systemMain.invoke(null)
            val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
            val systemContext = getSystemContext.invoke(activityThread) as Context

            audioManager = systemContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            packageManager = systemContext.packageManager
            getPlayerProxyMethod = AudioPlaybackConfiguration::class.java
                .getDeclaredMethod("getPlayerProxy").apply { isAccessible = true }

            getClientUidMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid").apply { isAccessible = true }
            }.getOrNull()
            clientUidField = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredField("mClientUid").apply { isAccessible = true }
            }.getOrNull()
            playerStateMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState").apply { isAccessible = true }
            }.getOrNull() ?: runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getPlayerState").apply { isAccessible = true }
            }.getOrNull()

            audioManager.registerAudioPlaybackCallback(playbackCallback, callbackHandler)
            callbackHandler.post { reapplyAll() }
        }

        fun execute(command: JSONObject): JSONObject {
            check(!closed) { "Audio helper is shutting down." }
            return when (command.optString("command")) {
                "ping" -> JSONObject()
                    .put("ok", true)
                    .put("uid", Process.myUid())
                    .put("pid", Process.myPid())
                    .put("sessions", activeMediaConfigs().size)
                    .put("activeApps", JSONArray(activePackages()))
                "set_media_gain" -> {
                    mediaGain = checkedGain(command.optDouble("gain", 1.0).toFloat())
                    reapplyAll()
                    JSONObject().put("ok", true).put("gain", mediaGain)
                }
                "set_app_gain" -> {
                    val packageName = command.getString("packageName")
                    require(packageName.isNotBlank()) { "packageName is required." }
                    val gain = checkedGain(command.optDouble("gain", 1.0).toFloat())
                    appGains[packageName] = gain
                    reapplyAll()
                    JSONObject().put("ok", true).put("packageName", packageName).put("gain", gain)
                }
                "get_app_gain" -> {
                    val packageName = command.getString("packageName")
                    JSONObject().put("ok", true).put("gain", appGains[packageName] ?: 1.0f)
                }
                "get_active_apps" -> JSONObject().put("ok", true).put("activeApps", JSONArray(activePackages()))
                "shutdown" -> JSONObject().put("ok", true).put("shutdown", true)
                else -> JSONObject().put("ok", false).put("error", "Unknown command.")
            }
        }

        private fun checkedGain(value: Float): Float {
            require(value.isFinite()) { "Gain must be finite." }
            return value.coerceIn(0f, 1f)
        }

        private fun activeMediaConfigs(): List<AudioPlaybackConfiguration> =
            audioManager.activePlaybackConfigurations.filter { isMedia(it) }

        private fun isMedia(config: AudioPlaybackConfiguration): Boolean {
            val attributes = config.audioAttributes ?: return false
            return runCatching { attributes.volumeControlStream == STREAM_MUSIC }.getOrDefault(false)
        }

        private fun clientUid(config: AudioPlaybackConfiguration): Int {
            val fromMethod = runCatching { getClientUidMethod?.invoke(config) as? Int }
                .getOrNull()?.takeIf { it > 0 }
            if (fromMethod != null) return fromMethod
            return runCatching { clientUidField?.getInt(config) ?: -1 }.getOrDefault(-1)
        }

        private fun packageForUid(uid: Int): String? {
            if (uid <= 1000) return null
            return runCatching {
                packageManager.getPackagesForUid(uid)?.firstOrNull { pkg ->
                    pkg != "android" && pkg != "com.android.systemui" && pkg != "dev.finevolume.app"
                }
            }.getOrNull()
        }

        private fun activePackages(): List<String> = activeMediaConfigs().mapNotNull { config ->
            val state = runCatching { playerStateMethod?.invoke(config) as? Int }.getOrNull()
            if (state != null && state != PLAYER_STATE_STARTED) return@mapNotNull null
            packageForUid(clientUid(config))
        }.distinct()

        private fun setVolumeMethod(proxy: Any): Method? {
            setVolumeMethodCache[proxy.javaClass]?.let { return it }
            var type: Class<*>? = proxy.javaClass
            var found: Method? = null
            while (type != null && found == null) {
                val current = type
                found = runCatching {
                    current.getDeclaredMethod("setVolume", java.lang.Float.TYPE).apply { isAccessible = true }
                }.getOrNull()
                type = current.superclass
            }
            if (found != null) setVolumeMethodCache[proxy.javaClass] = found
            return found
        }

        private fun apply(config: AudioPlaybackConfiguration) {
            try {
                if (!isMedia(config)) return
                val proxy = getPlayerProxyMethod.invoke(config) ?: return
                val appGain = packageForUid(clientUid(config))?.let { appGains[it] } ?: 1.0f
                val combined = (mediaGain * appGain).coerceIn(0f, 1f)
                val method = setVolumeMethod(proxy) ?: return
                method.invoke(proxy, combined)
            } catch (error: Throwable) {
                Log.w(TAG, "Could not apply gain to player session", error)
            }
        }

        private fun reapplyAll() {
            if (closed) return
            runCatching { activeMediaConfigs().forEach(::apply) }
                .onFailure { Log.w(TAG, "Could not enumerate playback sessions", it) }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { audioManager.unregisterAudioPlaybackCallback(playbackCallback) }
            callbackHandler.removeCallbacksAndMessages(null)
            callbackThread.quitSafely()
        }
    }
}
