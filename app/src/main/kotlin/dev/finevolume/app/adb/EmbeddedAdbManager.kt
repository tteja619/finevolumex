package dev.finevolume.app.adb

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import com.flyfishxu.kadb.mdns.MdnsServiceType
import com.flyfishxu.kadb.cert.KadbCert
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

enum class EmbeddedAdbPhase {
    DISCONNECTED,
    PAIRING,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    FAILED
}

data class EmbeddedAdbStatus(
    val phase: EmbeddedAdbPhase = EmbeddedAdbPhase.DISCONNECTED,
    val message: String = "Embedded ADB is not connected.",
    val host: String? = null,
    val port: Int? = null,
    val shellUid: Int? = null,
    val pairedKeyStored: Boolean = false,
    val audioHelperReady: Boolean = false,
    val detail: String? = null
) {
    val isConnected: Boolean get() = phase == EmbeddedAdbPhase.CONNECTED
}

@Singleton
class EmbeddedAdbManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _status = MutableStateFlow(
        EmbeddedAdbStatus(pairedKeyStored = hasStoredIdentity())
    )
    val status: StateFlow<EmbeddedAdbStatus> = _status

    @Volatile private var client: Kadb? = null
    @Volatile private var audioHelper: AdbAudioHelperBridge? = null
    private val connectionLock = Any()

    /**
     * Pair the app's embedded ADB client with this phone's local adbd.
     *
     * This is an explicit one-shot operation. It does not toggle Wireless
     * Debugging, restart adbd, cycle developer settings, or retry in a loop.
     * Kadb persists the ADB identity through the Android Keystore-backed store.
     */
    suspend fun pairLocal(pairingPort: Int, pairingCode: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (pairingPort !in 1..65535) {
                return@withContext Result.failure(IllegalArgumentException("Pairing port must be between 1 and 65535."))
            }
            if (!pairingCode.matches(Regex("\\d{6}"))) {
                return@withContext Result.failure(IllegalArgumentException("Pairing code must contain exactly six digits."))
            }

            setStatus(
                EmbeddedAdbStatus(
                    phase = EmbeddedAdbPhase.PAIRING,
                    message = "Pairing directly with Android Wireless Debugging…",
                    pairedKeyStored = hasStoredIdentity()
                )
            )

            runCatching {
                // LADB uses localhost for the same-device pairing endpoint. Do not
                // use the mDNS connect port here: Android exposes a different port
                // for its short-lived pairing server.
                withTimeout(PAIR_TIMEOUT_MS) {
                    Kadb.pair("127.0.0.1", pairingPort, pairingCode)
                }
                setStatus(
                    EmbeddedAdbStatus(
                        phase = EmbeddedAdbPhase.DISCOVERING,
                        message = "Pairing succeeded. Discovering the separate ADB TLS connection endpoint…",
                        pairedKeyStored = hasStoredIdentity()
                    )
                )
                val endpoint = discoverLocalConnectEndpoint()
                    ?: throw IllegalStateException(
                        "Pairing completed, but mDNS did not reveal this phone's ADB connection port. " +
                            "Keep Wireless Debugging enabled and use the manual connection-port field."
                    )
                connectInternal(endpoint.host, endpoint.port)
                "Paired and connected to " + endpoint.host + ":" + endpoint.port
            }.onFailure { error ->
                val detail = rootCause(error)
                setStatus(
                    EmbeddedAdbStatus(
                        phase = EmbeddedAdbPhase.FAILED,
                        message = "Wireless Debugging pairing failed.",
                        pairedKeyStored = hasStoredIdentity(),
                        detail = detail.javaClass.simpleName + ": " + (detail.message ?: "No detail supplied.")
                    )
                )
            }
        }

    /**
     * Reconnect to a previously paired phone. Pairing is not repeated, and no
     * settings switches are touched. A manual port may be supplied when mDNS
     * discovery is unavailable on an OEM build.
     */
    suspend fun reconnectLocal(manualConnectPort: Int? = null): Result<String> =
        withContext(Dispatchers.IO) {
            if (!hasStoredIdentity()) {
                return@withContext Result.failure(
                    IllegalStateException("No saved ADB identity. Pair FineVolume once using the six-digit code.")
                )
            }
            runCatching {
                val endpoint = if (manualConnectPort != null) {
                    require(manualConnectPort in 1..65535) { "Connection port must be between 1 and 65535." }
                    MdnsEndpoint(
                        name = "Manual local Wireless Debugging endpoint",
                        host = "127.0.0.1",
                        port = manualConnectPort,
                        serviceType = MdnsServiceType.TLS_CONNECT
                    )
                } else {
                    setStatus(
                        EmbeddedAdbStatus(
                            phase = EmbeddedAdbPhase.DISCOVERING,
                            message = "Looking for the previously paired ADB connection endpoint…",
                            pairedKeyStored = true
                        )
                    )
                    discoverLocalConnectEndpoint()
                        ?: throw IllegalStateException(
                            "No local ADB TLS connection endpoint found. Wireless Debugging may be off, " +
                                "or the phone has not announced its current port yet."
                        )
                }
                connectInternal(endpoint.host, endpoint.port)
                "Reconnected to " + endpoint.host + ":" + endpoint.port
            }.onFailure { error ->
                val detail = rootCause(error)
                setStatus(
                    EmbeddedAdbStatus(
                        phase = EmbeddedAdbPhase.FAILED,
                        message = "ADB reconnect failed. The saved pairing key was retained.",
                        pairedKeyStored = hasStoredIdentity(),
                        detail = detail.javaClass.simpleName + ": " + (detail.message ?: "No detail supplied.")
                    )
                )
            }
        }

    private suspend fun discoverLocalConnectEndpoint(): MdnsEndpoint? {
        val localAddresses = localIpv4Addresses()
        val discovery = KadbMdnsAndroid(context)
        return try {
            discovery.start()
            val state = withTimeout(DISCOVERY_TIMEOUT_MS) {
                discovery.state.first { snapshot ->
                    snapshot.connectDevices.any { endpoint ->
                        endpoint.serviceType == MdnsServiceType.TLS_CONNECT &&
                            (endpoint.host == "127.0.0.1" || endpoint.host in localAddresses)
                    } || snapshot.status.name == "FAILED"
                }
            }
            state.connectDevices.firstOrNull { endpoint ->
                endpoint.serviceType == MdnsServiceType.TLS_CONNECT &&
                    (endpoint.host == "127.0.0.1" || endpoint.host in localAddresses)
            }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            discovery.close()
        }
    }

    private suspend fun connectInternal(host: String, port: Int) {
        setStatus(
            EmbeddedAdbStatus(
                phase = EmbeddedAdbPhase.CONNECTING,
                message = "Establishing authenticated ADB TLS connection…",
                host = host,
                port = port,
                pairedKeyStored = hasStoredIdentity()
            )
        )

        val candidate = Kadb.create(
            host = host,
            port = port,
            connectTimeout = CONNECT_TIMEOUT_MS,
            socketTimeout = IO_TIMEOUT_MS
        )

        try {
            val identity = candidate.shell("id")
            check(identity.exitCode == 0) {
                "ADB shell identity check failed: " + identity.allOutput.trim()
            }
            val uid = Regex("uid=(\\d+)").find(identity.allOutput)?.groupValues?.get(1)?.toIntOrNull()
            check(uid == 2000) {
                "ADB responded, but expected Android shell UID 2000. Received: " +
                    identity.allOutput.trim()
            }

            val helper = AdbAudioHelperBridge(context, candidate)
            val helperResult = helper.start()
            synchronized(connectionLock) {
                audioHelper?.close()
                client?.close()
                client = candidate
                audioHelper = helper.takeIf { helperResult.isSuccess }
            }

            if (helperResult.isSuccess) {
                setStatus(
                    EmbeddedAdbStatus(
                        phase = EmbeddedAdbPhase.CONNECTED,
                        message = "Embedded ADB connected. Audio helper is running as shell UID 2000.",
                        host = host,
                        port = port,
                        shellUid = uid,
                        pairedKeyStored = hasStoredIdentity(),
                        audioHelperReady = true
                    )
                )
            } else {
                setStatus(
                    EmbeddedAdbStatus(
                        phase = EmbeddedAdbPhase.CONNECTED,
                        message = "ADB is connected, but the audio helper could not start. Native volume remains available.",
                        host = host,
                        port = port,
                        shellUid = uid,
                        pairedKeyStored = hasStoredIdentity(),
                        audioHelperReady = false,
                        detail = helperResult.exceptionOrNull()?.let { rootCause(it).javaClass.simpleName + ": " + (rootCause(it).message ?: "No detail") }
                    )
                )
            }
        } catch (t: Throwable) {
            candidate.close()
            throw t
        }
    }

    suspend fun setMediaGain(gain: Float): Result<Unit> {
        val helper = synchronized(connectionLock) { audioHelper }
            ?: return Result.failure(IllegalStateException("ADB audio helper is not running."))
        return helper.setMediaGain(gain).onFailure { markHelperFailure(it) }
    }

    suspend fun setAppGain(packageName: String, gain: Float): Result<Unit> {
        val helper = synchronized(connectionLock) { audioHelper }
            ?: return Result.failure(IllegalStateException("ADB audio helper is not running."))
        return helper.setAppGain(packageName, gain).onFailure { markHelperFailure(it) }
    }

    suspend fun activeMediaApps(): Result<List<String>> {
        val helper = synchronized(connectionLock) { audioHelper }
            ?: return Result.failure(IllegalStateException("ADB audio helper is not running."))
        return helper.activeMediaApps().onFailure { markHelperFailure(it) }
    }

    suspend fun activeSessionCount(): Result<Int> {
        val helper = synchronized(connectionLock) { audioHelper }
            ?: return Result.failure(IllegalStateException("ADB audio helper is not running."))
        return helper.activeSessionCount().onFailure { markHelperFailure(it) }
    }

    private fun markHelperFailure(error: Throwable) {
        val detail = rootCause(error)
        synchronized(connectionLock) {
            runCatching { audioHelper?.close() }
            audioHelper = null
        }
        _status.value = _status.value.copy(
            audioHelperReady = false,
            detail = detail.javaClass.simpleName + ": " + (detail.message ?: "No detail"),
            message = "ADB transport exists, but the audio helper stopped. Reconnect to restart it."
        )
    }

    suspend fun runReadOnlyShellCommand(command: String): Result<String> =
        withContext(Dispatchers.IO) {
            val activeClient = synchronized(connectionLock) { client }
                ?: return@withContext Result.failure(IllegalStateException("ADB is not connected."))
            runCatching {
                require(command.isNotBlank()) { "Command cannot be empty." }
                val response = activeClient.shell(command)
                check(response.exitCode == 0) {
                    response.allOutput.ifBlank { "Command exited with code " + response.exitCode }
                }
                response.allOutput
            }.onFailure { error ->
                val detail = rootCause(error)
                setStatus(
                    EmbeddedAdbStatus(
                        phase = EmbeddedAdbPhase.FAILED,
                        message = "ADB shell command failed. Native audio remains available.",
                        pairedKeyStored = hasStoredIdentity(),
                        detail = detail.javaClass.simpleName + ": " + (detail.message ?: "No detail supplied.")
                    )
                )
            }
        }

    fun disconnect() {
        synchronized(connectionLock) {
            runCatching { audioHelper?.close() }
            audioHelper = null
            runCatching { client?.close() }
            client = null
        }
        setStatus(
            EmbeddedAdbStatus(
                phase = EmbeddedAdbPhase.DISCONNECTED,
                message = "Embedded ADB disconnected. FineVolume can still use native AudioManager volume.",
                pairedKeyStored = hasStoredIdentity()
            )
        )
    }

    fun forgetPairing() {
        disconnect()
        runCatching { KadbCert.clear() }
        setStatus(
            EmbeddedAdbStatus(
                phase = EmbeddedAdbPhase.DISCONNECTED,
                message = "Saved ADB pairing identity deleted. Pair again to reconnect.",
                pairedKeyStored = false
            )
        )
    }

    private fun hasStoredIdentity(): Boolean =
        runCatching { KadbCert.exportPrivateKeyOrNull() != null }.getOrDefault(false)

    private fun localIpv4Addresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .toSet()
    }.getOrDefault(emptySet())

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        val visited = HashSet<Throwable>()
        while (visited.add(current)) {
            val next = when (current) {
                is InvocationTargetException -> current.targetException ?: current.cause
                else -> current.cause
            } ?: break
            current = next
        }
        return current
    }

    private fun setStatus(value: EmbeddedAdbStatus) {
        _status.value = value
    }

    companion object {
        private const val PAIR_TIMEOUT_MS = 20_000L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val IO_TIMEOUT_MS = 10_000
    }
}
