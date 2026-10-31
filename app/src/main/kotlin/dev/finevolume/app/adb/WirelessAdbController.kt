package dev.finevolume.app.adb

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class WirelessAdbStage {
    NOT_PAIRED,
    PAIRING,
    WAITING_FOR_WIRELESS_DEBUGGING,
    CONNECTING,
    STARTING_HELPER,
    READY,
    ERROR
}

data class WirelessAdbStatus(
    val stage: WirelessAdbStage = WirelessAdbStage.NOT_PAIRED,
    val message: String = "Pair FineVolume with Android Wireless Debugging to enable its independent shell engine.",
    val helperUid: Int? = null,
    val helperPid: Int? = null,
    val activeMediaSessions: Int = 0,
    val lastError: String? = null
) {
    val isReady: Boolean get() = stage == WirelessAdbStage.READY && helperUid == SHELL_UID

    companion object {
        const val SHELL_UID = 2000
    }
}

/**
 * FineVolume-owned ADB client and persistent shell helper supervisor.
 *
 * It uses the phone's own Wireless Debugging ADB daemon and a private RSA
 * identity. No Shizuku app, Shizuku Binder, root, or exported helper app is used.
 * Android still requires the user to enable Wireless Debugging and approve
 * initial pairing in Developer Options.
 */
@Singleton
class WirelessAdbController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val commandMutex = Mutex()
    private val connecting = AtomicBoolean(false)

    private val _status = MutableStateFlow(
        WirelessAdbStatus(
            stage = if (preferences.getBoolean(KEY_PAIRED, false)) {
                WirelessAdbStage.WAITING_FOR_WIRELESS_DEBUGGING
            } else {
                WirelessAdbStage.NOT_PAIRED
            },
            message = if (preferences.getBoolean(KEY_PAIRED, false)) {
                "This device was paired previously. Enable Wireless Debugging to reconnect."
            } else {
                "Initial Wireless Debugging pairing is required."
            }
        )
    )
    val status: StateFlow<WirelessAdbStatus> = _status.asStateFlow()

    @Volatile private var adbManager: FineVolumeAdbConnectionManager? = null
    @Volatile private var helperStream: AdbStream? = null
    @Volatile private var input: BufferedReader? = null
    @Volatile private var output: BufferedWriter? = null
    @Volatile private var watchdogJob: Job? = null
    @Volatile private var userRequestedStop = false

    private fun manager(): FineVolumeAdbConnectionManager {
        adbManager?.let { return it }
        return synchronized(this) {
            adbManager ?: FineVolumeAdbConnectionManager(context).also { adbManager = it }
        }
    }

    fun hasStoredIdentity(): Boolean =
        runCatching { manager().isIdentityStored }.getOrDefault(false)

    fun isPaired(): Boolean = preferences.getBoolean(KEY_PAIRED, false)

    /**
     * Pair directly with Android's local ADB pairing endpoint.
     * The user obtains port/code from Developer Options > Wireless debugging >
     * Pair device with pairing code. The short-lived code is never persisted.
     */
    suspend fun pair(pairingPort: Int, pairingCode: String): Result<Unit> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Result.failure(IllegalStateException("Wireless ADB pairing requires Android 11 or newer."))
        }
        if (pairingPort !in 1..65535) {
            return Result.failure(IllegalArgumentException("Enter the pairing port shown by Android."))
        }
        if (!PAIRING_CODE.matches(pairingCode)) {
            return Result.failure(IllegalArgumentException("Enter the six-digit pairing code shown by Android."))
        }

        _status.value = WirelessAdbStatus(
            stage = WirelessAdbStage.PAIRING,
            message = "Pairing with this phone's Wireless Debugging service…"
        )

        return withContext(Dispatchers.IO) {
            runCatching {
                disconnectTransport()
                manager().pairWithLocalDevice(pairingPort, pairingCode)
                preferences.edit().putBoolean(KEY_PAIRED, true).apply()
                _status.value = WirelessAdbStatus(
                    stage = WirelessAdbStage.WAITING_FOR_WIRELESS_DEBUGGING,
                    message = "Pairing completed. Connecting to Wireless Debugging…"
                )
                connectAndStartHelper()
            }.onFailure { error ->
                _status.value = WirelessAdbStatus(
                    stage = WirelessAdbStage.ERROR,
                    message = "ADB pairing failed.",
                    lastError = error.message ?: error.javaClass.simpleName
                )
            }
        }
    }

    fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        userRequestedStop = false
        watchdogJob = scope.launch {
            var backoffMs = 2_000L
            while (true) {
                if (userRequestedStop) break

                if (status.value.isReady) {
                    val health = runCatching { command(JSONObject().put("command", "ping")) }
                    health.fold(
                        onSuccess = { reply ->
                            if (!reply.optBoolean("ok", false) || reply.optInt("uid", -1) != WirelessAdbStatus.SHELL_UID) {
                                disconnectAndFallback("The FineVolume shell helper failed its health check.")
                            } else {
                                _status.value = status.value.copy(
                                    activeMediaSessions = reply.optInt("sessions", 0),
                                    lastError = null
                                )
                            }
                        },
                        onFailure = { error ->
                            disconnectAndFallback(error.message ?: "Wireless ADB connection was lost.")
                        }
                    )
                    delay(8_000L)
                    continue
                }

                if (!isPaired()) {
                    _status.value = WirelessAdbStatus(
                        stage = WirelessAdbStage.NOT_PAIRED,
                        message = "Pair once from Android Developer Options. Native volume control remains available."
                    )
                    delay(10_000L)
                    continue
                }

                val success = runCatching { connectAndStartHelper() }.onFailure { error ->
                    _status.value = WirelessAdbStatus(
                        stage = WirelessAdbStage.WAITING_FOR_WIRELESS_DEBUGGING,
                        message = "Waiting for Wireless Debugging. Turn it on in Developer Options; FineVolume will reconnect automatically.",
                        lastError = error.message ?: error.javaClass.simpleName
                    )
                }.isSuccess

                if (success && status.value.isReady) {
                    backoffMs = 2_000L
                } else {
                    delay(backoffMs)
                    backoffMs = min(backoffMs * 2L, 30_000L)
                }
            }
        }
    }

    fun stopWatchdog() {
        userRequestedStop = true
        watchdogJob?.cancel()
        watchdogJob = null
        scope.launch { disconnectTransport() }
    }

    suspend fun reconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            userRequestedStop = false
            disconnectTransport()
            connectAndStartHelper()
        }.onFailure { error ->
            _status.value = WirelessAdbStatus(
                stage = WirelessAdbStage.WAITING_FOR_WIRELESS_DEBUGGING,
                message = "Could not reconnect. Check that Wireless Debugging is enabled.",
                lastError = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private suspend fun connectAndStartHelper() {
        if (status.value.isReady) return
        if (!isPaired()) throw IllegalStateException("FineVolume has not been paired with Wireless Debugging.")

        if (!connecting.compareAndSet(false, true)) {
            withTimeout(20_000L) {
                while (connecting.get()) delay(100L)
            }
            if (status.value.isReady) return
            throw IllegalStateException("Another Wireless ADB connection attempt failed.")
        }

        try {
            _status.value = WirelessAdbStatus(
                stage = WirelessAdbStage.CONNECTING,
                message = "Discovering this phone's Wireless Debugging TLS service…"
            )

            val manager = manager()
            val connected = manager.connectToLocalWirelessDebugging(context, CONNECT_TIMEOUT_MS)
            check(connected && manager.isConnected) {
                "Wireless Debugging TLS service was not discovered. Ensure Wireless Debugging is enabled and the phone is unlocked."
            }

            _status.value = WirelessAdbStatus(
                stage = WirelessAdbStage.STARTING_HELPER,
                message = "Starting FineVolume's shell-UID audio helper…"
            )

            val stream = manager.openStream("shell,v2,raw:${helperCommand()}")
            helperStream = stream
            input = BufferedReader(InputStreamReader(stream.openInputStream(), Charsets.UTF_8))
            output = BufferedWriter(OutputStreamWriter(stream.openOutputStream(), Charsets.UTF_8))

            val readyLine = withTimeout(HELPER_START_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { input?.readLine() }
            } ?: throw IllegalStateException("The FineVolume helper closed before it reported ready.")

            val ready = JSONObject(readyLine)
            if (ready.optString("type") == "fatal") {
                throw IllegalStateException(ready.optString("message", "The helper could not start."))
            }

            val uid = ready.optInt("uid", -1)
            val pid = ready.optInt("pid", -1)
            check(ready.optBoolean("ok", false) && ready.optString("type") == "ready") {
                "FineVolume helper returned an unexpected startup response."
            }
            check(uid == WirelessAdbStatus.SHELL_UID) {
                "Unexpected helper identity UID $uid. FineVolume requires the Android ADB shell UID 2000."
            }

            _status.value = WirelessAdbStatus(
                stage = WirelessAdbStage.READY,
                message = "FineVolume's own ADB shell helper is connected. Shizuku is not used.",
                helperUid = uid,
                helperPid = pid
            )

            reapplyPersistedGains()
            val health = command(JSONObject().put("command", "ping"))
            _status.value = status.value.copy(activeMediaSessions = health.optInt("sessions", 0))
        } finally {
            connecting.set(false)
        }
    }

    private fun helperCommand(): String {
        val apkPath = context.applicationInfo.sourceDir
        val nativePath = context.applicationInfo.nativeLibraryDir
        return "CLASSPATH=${shellQuote(apkPath)} LD_LIBRARY_PATH=${shellQuote(nativePath)} " +
            "exec app_process /system/bin --nice-name=finevolume-audio " +
            "dev.finevolume.app.privileged.FineVolumeShellServer"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun command(payload: JSONObject): JSONObject = commandMutex.withLock {
        val writer = output ?: throw IllegalStateException("Wireless ADB shell is not connected.")
        val reader = input ?: throw IllegalStateException("Wireless ADB shell is not connected.")

        writer.write(payload.toString())
        writer.newLine()
        writer.flush()

        val line = withTimeout(COMMAND_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { reader.readLine() }
        } ?: throw IllegalStateException("FineVolume shell helper closed the command stream.")

        val response = JSONObject(line)
        check(response.optBoolean("ok", false)) {
            response.optString("error", "FineVolume shell helper rejected the command.")
        }
        response
    }

    suspend fun setMediaGain(gain: Float): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val safeGain = gain.coerceIn(0f, 1f)
            preferences.edit().putFloat(KEY_MEDIA_GAIN, safeGain).apply()
            if (status.value.isReady) {
                command(JSONObject().put("command", "set_media_gain").put("gain", safeGain))
            }
        }
    }

    suspend fun setAppGain(packageName: String, gain: Float): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(packageName.isNotBlank())
            val safeGain = gain.coerceIn(0f, 1f)
            preferences.edit().putFloat(APP_GAIN_PREFIX + packageName, safeGain).apply()
            if (status.value.isReady) {
                command(
                    JSONObject()
                        .put("command", "set_app_gain")
                        .put("packageName", packageName)
                        .put("gain", safeGain)
                )
            }
        }
    }

    suspend fun getAppGain(packageName: String): Float = withContext(Dispatchers.IO) {
        if (!status.value.isReady) {
            return@withContext preferences.getFloat(APP_GAIN_PREFIX + packageName, 1.0f)
        }
        command(JSONObject().put("command", "get_app_gain").put("packageName", packageName))
            .optDouble("gain", 1.0).toFloat()
    }

    suspend fun getActiveApps(): List<String> = withContext(Dispatchers.IO) {
        if (!status.value.isReady) return@withContext emptyList()
        val response = command(JSONObject().put("command", "get_active_apps"))
        val items = response.optJSONArray("activeApps") ?: JSONArray()
        (0 until items.length()).mapNotNull { items.optString(it).takeIf(String::isNotBlank) }
    }

    suspend fun runHealthCheck(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val response = command(JSONObject().put("command", "ping"))
            val uid = response.optInt("uid", -1)
            check(uid == WirelessAdbStatus.SHELL_UID) { "Helper UID is not 2000." }
            response.optInt("sessions", 0)
        }
    }

    private suspend fun reapplyPersistedGains() {
        command(
            JSONObject()
                .put("command", "set_media_gain")
                .put("gain", preferences.getFloat(KEY_MEDIA_GAIN, 1.0f))
        )
        val all = preferences.all
        for ((key, value) in all) {
            if (key.startsWith(APP_GAIN_PREFIX) && value is Float) {
                val packageName = key.removePrefix(APP_GAIN_PREFIX)
                command(
                    JSONObject()
                        .put("command", "set_app_gain")
                        .put("packageName", packageName)
                        .put("gain", value)
                )
            }
        }
    }

    private suspend fun disconnectAndFallback(reason: String) {
        disconnectTransport()
        _status.value = WirelessAdbStatus(
            stage = if (isPaired()) WirelessAdbStage.WAITING_FOR_WIRELESS_DEBUGGING else WirelessAdbStage.NOT_PAIRED,
            message = "FineVolume's shell helper disconnected. Native AudioManager controls remain available.",
            lastError = reason
        )
    }

    private fun disconnectTransport() {
        runCatching { helperStream?.close() }
        helperStream = null
        input = null
        output = null
        runCatching { adbManager?.disconnect() }
        if (!status.value.isReady && isPaired()) {
            _status.value = status.value.copy(
                stage = WirelessAdbStage.WAITING_FOR_WIRELESS_DEBUGGING,
                helperUid = null,
                helperPid = null,
                activeMediaSessions = 0
            )
        }
    }

    fun forgetPairing() {
        preferences.edit().putBoolean(KEY_PAIRED, false).apply()
        scope.launch {
            disconnectTransport()
            _status.value = WirelessAdbStatus(
                stage = WirelessAdbStage.NOT_PAIRED,
                message = "FineVolume ADB pairing has been forgotten. Your Android Developer Options pairing entry may also be removed there."
            )
        }
    }

    companion object {
        private const val PREFS = "finevolume_wireless_adb"
        private const val KEY_PAIRED = "paired"
        private const val KEY_MEDIA_GAIN = "media_gain"
        private const val APP_GAIN_PREFIX = "app_gain:"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val HELPER_START_TIMEOUT_MS = 15_000L
        private const val COMMAND_TIMEOUT_MS = 5_000L
        private val PAIRING_CODE = Regex("^[0-9]{6}$")
    }
}
