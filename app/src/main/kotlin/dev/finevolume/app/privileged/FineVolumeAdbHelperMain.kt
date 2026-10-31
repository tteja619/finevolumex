package dev.finevolume.app.privileged

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point launched with app_process through FineVolume's own ADB shell.
 * Runs as UID 2000 (shell), not root. Commands arrive through shell v2 stdin;
 * responses are returned on stdout. There is no Shizuku Binder dependency.
 */
@Keep
object FineVolumeAdbHelperMain {
    private const val TAG = "FineVolumeAdbHelper"
    private const val SYSTEM_UID_LIMIT = 1000
    private const val PLAYER_STATE_STARTED = 2

    @Volatile private var mediaGain = 1f
    private val appGains = ConcurrentHashMap<String, Float>()
    private val uidPackages = ConcurrentHashMap<Int, String>()

    private var audioManager: AudioManager? = null
    private var packageManager: android.content.pm.PackageManager? = null
    private var callbackThread: HandlerThread? = null
    private var callbackHandler: Handler? = null
    private var getProxyMethod: java.lang.reflect.Method? = null
    private var getClientUidMethod: java.lang.reflect.Method? = null
    private var clientUidField: java.lang.reflect.Field? = null
    private var getPlayerStateMethod: java.lang.reflect.Method? = null
    private var setPlayerVolumeMethod: java.lang.reflect.Method? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val handler = callbackHandler ?: return
            handler.post { reapplyAll() }
            handler.postDelayed({ reapplyAll() }, 120L)
            handler.postDelayed({ reapplyAll() }, 400L)
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val out = PrintWriter(System.out, true)
        try {
            HiddenApiBypass.addHiddenApiExemptions("")
            initializeAudio()
            out.println("READY|" + android.os.Process.myUid())
            out.flush()

            val input = BufferedReader(InputStreamReader(System.`in`))
            while (true) {
                val line = input.readLine() ?: break
                val response = runCatching { handleCommand(line) }
                    .fold(
                        onSuccess = { it },
                        onFailure = { error ->
                            Log.e(TAG, "Command failed", error)
                            "ERROR|" + base64(error.javaClass.simpleName + ": " + (error.message ?: "No detail"))
                        }
                    )
                out.println(response)
                out.flush()
                if (line == "STOP") break
            }
        } catch (error: Throwable) {
            Log.e(TAG, "ADB audio helper failed", error)
            runCatching {
                out.println("FATAL|" + base64(error.javaClass.simpleName + ": " + (error.message ?: "No detail")))
                out.flush()
            }
        } finally {
            shutdown()
        }
    }

    private fun initializeAudio() {
        callbackThread = HandlerThread("FineVolumeAdbPlaybackObserver").apply { start() }
        callbackHandler = Handler(requireNotNull(callbackThread).looper)

        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getDeclaredMethod("systemMain").apply { isAccessible = true }
        val activityThread = systemMain.invoke(null)
        val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext").apply { isAccessible = true }
        val systemContext = getSystemContext.invoke(activityThread) as Context

        audioManager = systemContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        packageManager = systemContext.packageManager

        getProxyMethod = AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerProxy").apply {
            isAccessible = true
        }
        getClientUidMethod = runCatching {
            AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid").apply { isAccessible = true }
        }.getOrNull() ?: runCatching {
            AudioPlaybackConfiguration::class.java.getMethod("getClientUid").apply { isAccessible = true }
        }.getOrNull()
        clientUidField = runCatching {
            AudioPlaybackConfiguration::class.java.getDeclaredField("mClientUid").apply { isAccessible = true }
        }.getOrNull()
        getPlayerStateMethod = runCatching {
            AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState").apply { isAccessible = true }
        }.getOrNull() ?: runCatching {
            AudioPlaybackConfiguration::class.java.getMethod("getPlayerState").apply { isAccessible = true }
        }.getOrNull()

        audioManager?.registerAudioPlaybackCallback(playbackCallback, callbackHandler)
        reapplyAll()
        Log.i(TAG, "Playback observer active under UID " + android.os.Process.myUid())
    }

    private fun handleCommand(line: String): String {
        val parts = line.split('|')
        return when (parts.firstOrNull()) {
            "PING" -> "PONG|" + android.os.Process.myUid() + "|" + activeMediaSessions().size
            "SET_STREAM_GAIN" -> {
                require(parts.size == 2) { "SET_STREAM_GAIN requires one value." }
                mediaGain = parts[1].toFloat().coerceIn(0f, 1f)
                reapplyAll()
                "OK"
            }
            "SET_APP_GAIN" -> {
                require(parts.size == 3) { "SET_APP_GAIN requires package and gain." }
                val packageName = parts[1]
                require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "Invalid package name." }
                appGains[packageName] = parts[2].toFloat().coerceIn(0f, 1f)
                reapplyAll()
                "OK"
            }
            "GET_ACTIVE_APPS" -> "APPS|" + activeMediaPackages().joinToString(",")
            "GET_SESSION_COUNT" -> "SESSIONS|" + activeMediaSessions().size
            "STOP" -> "STOPPED"
            else -> throw IllegalArgumentException("Unknown helper command.")
        }
    }

    private fun isMusicSession(config: AudioPlaybackConfiguration): Boolean {
        val attributes = config.audioAttributes ?: return false
        return runCatching {
            attributes.volumeControlStream == AudioManager.STREAM_MUSIC
        }.getOrDefault(false)
    }

    private fun activeMediaSessions(): List<AudioPlaybackConfiguration> =
        runCatching { audioManager?.activePlaybackConfigurations.orEmpty().filter(::isMusicSession) }
            .getOrDefault(emptyList())

    private fun uidFor(config: AudioPlaybackConfiguration): Int {
        val viaMethod = runCatching { getClientUidMethod?.invoke(config) as? Int }
            .getOrNull()?.takeIf { it > 0 }
        if (viaMethod != null) return viaMethod
        return runCatching { clientUidField?.getInt(config) ?: -1 }.getOrDefault(-1)
    }

    private fun packageFor(uid: Int): String? {
        if (uid <= SYSTEM_UID_LIMIT) return null
        uidPackages[uid]?.let { return it }
        val pkg = runCatching { packageManager?.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
        if (pkg != null) uidPackages[uid] = pkg
        return pkg
    }

    private fun activeMediaPackages(): List<String> =
        activeMediaSessions().mapNotNull { config ->
            val state = runCatching { getPlayerStateMethod?.invoke(config) as? Int }.getOrNull()
            if (state != null && state != PLAYER_STATE_STARTED) return@mapNotNull null
            packageFor(uidFor(config))
        }.distinct()

    private fun reapplyAll() {
        activeMediaSessions().forEach { config ->
            runCatching {
                val proxy = getProxyMethod?.invoke(config) ?: return@runCatching
                val pkg = packageFor(uidFor(config))
                val appGain = pkg?.let { appGains[it] } ?: 1f
                val gain = (mediaGain * appGain).coerceIn(0f, 1f)
                val method = findSetVolumeMethod(proxy) ?: return@runCatching
                method.invoke(proxy, gain)
            }.onFailure { error ->
                Log.w(TAG, "Unable to apply player gain", error)
            }
        }
    }

    private fun findSetVolumeMethod(proxy: Any): java.lang.reflect.Method? {
        setPlayerVolumeMethod?.let { return it }
        var type: Class<*>? = proxy.javaClass
        while (type != null) {
            val current = type
            val method = runCatching {
                current.getDeclaredMethod("setVolume", java.lang.Float.TYPE).apply { isAccessible = true }
            }.getOrNull()
            if (method != null) {
                setPlayerVolumeMethod = method
                return method
            }
            type = current.superclass
        }
        return null
    }

    private fun shutdown() {
        runCatching { audioManager?.unregisterAudioPlaybackCallback(playbackCallback) }
        callbackHandler?.removeCallbacksAndMessages(null)
        callbackThread?.quitSafely()
        callbackHandler = null
        callbackThread = null
    }

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
