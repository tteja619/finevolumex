package dev.finevolume.app.adb

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Keeps one ADB shell-v2 stream open to FineVolume's app_process helper.
 * Commands are simple line messages; stdout carries one response per command.
 */
class AdbAudioHelperBridge(
    private val context: Context,
    private val adb: Kadb
) : AutoCloseable {

    private val commandMutex = Mutex()
    @Volatile private var shell: AdbShellStream? = null
    @Volatile private var stderrTail = ""

    suspend fun start(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            check(shell == null) { "Audio helper is already running." }
            val apkPath = context.applicationInfo.sourceDir
            val command = "CLASSPATH=" + shellQuote(apkPath) +
                " app_process /system/bin dev.finevolume.app.privileged.FineVolumeAdbHelperMain"
            val stream = adb.openShell(command)
            shell = stream

            val ready = readLine(stream)
            val parts = ready.split('|')
            check(parts.size == 2 && parts[0] == "READY") {
                "Audio helper did not send its ready handshake. " +
                    "Response: " + ready + stderrSuffix()
            }
            val uid = parts[1].toIntOrNull()
                ?: throw IllegalStateException("Audio helper returned an invalid process UID.")
            check(uid == 2000) {
                "Expected app_process to run under ADB shell UID 2000, received UID $uid."
            }

            val pong = sendAndRead(stream, "PING")
            check(pong.startsWith("PONG|2000|")) {
                "Audio helper health check failed: $pong" + stderrSuffix()
            }
            uid
        }.onFailure {
            close()
        }
    }

    suspend fun setMediaGain(gain: Float): Result<Unit> =
        sendCommand("SET_STREAM_GAIN|" + gain.coerceIn(0f, 1f))

    suspend fun setAppGain(packageName: String, gain: Float): Result<Unit> {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) {
            return Result.failure(IllegalArgumentException("Invalid package name."))
        }
        return sendCommand("SET_APP_GAIN|$packageName|" + gain.coerceIn(0f, 1f))
    }

    suspend fun activeMediaApps(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = commandMutex.withLock {
                val stream = requireShell()
                sendAndRead(stream, "GET_ACTIVE_APPS")
            }
            check(response.startsWith("APPS|")) { "Unexpected active-app response: $response" }
            response.removePrefix("APPS|").split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
        }
    }

    suspend fun activeSessionCount(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val response = commandMutex.withLock {
                sendAndRead(requireShell(), "GET_SESSION_COUNT")
            }
            check(response.startsWith("SESSIONS|")) { "Unexpected session response: $response" }
            response.removePrefix("SESSIONS|").toInt()
        }
    }

    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            commandMutex.withLock { sendAndRead(requireShell(), "PING") }
        }
    }

    private suspend fun sendCommand(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = commandMutex.withLock {
                sendAndRead(requireShell(), command)
            }
            if (response.startsWith("ERROR|") || response.startsWith("FATAL|")) {
                throw IOException(decodeError(response))
            }
            check(response == "OK") { "Unexpected helper response: $response" }
        }.onFailure { close() }
    }

    private fun requireShell(): AdbShellStream =
        shell ?: throw IllegalStateException("FineVolume app_process audio helper is not running.")

    private fun sendAndRead(stream: AdbShellStream, command: String): String {
        stream.write(command + "\n")
        return readLine(stream)
    }

    private fun readLine(stream: AdbShellStream): String {
        val out = StringBuilder()
        while (true) {
            when (val packet = stream.read()) {
                is AdbShellPacket.StdOut -> {
                    out.append(packet.payload.toString(Charsets.UTF_8))
                    val newline = out.indexOf("\n")
                    if (newline >= 0) return out.substring(0, newline).trimEnd('\r')
                    check(out.length <= MAX_RESPONSE_CHARS) { "Audio helper response exceeded the safety limit." }
                }
                is AdbShellPacket.StdError -> {
                    stderrTail = (stderrTail + packet.payload.toString(Charsets.UTF_8)).takeLast(MAX_STDERR_CHARS)
                }
                is AdbShellPacket.Exit -> {
                    shell = null
                    throw IOException(
                        "FineVolume app_process helper exited with code " +
                            (packet.payload.firstOrNull()?.toInt()?.and(0xff) ?: -1) + stderrSuffix()
                    )
                }
            }
        }
    }

    private fun stderrSuffix(): String =
        stderrTail.takeIf { it.isNotBlank() }?.let { "\nHelper stderr: " + it } ?: ""

    private fun decodeError(response: String): String {
        val encoded = response.substringAfter('|', "")
        return runCatching {
            android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
        }.getOrDefault(response)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    override fun close() {
        val current = shell
        shell = null
        if (current != null) runCatching { current.close() }
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 16 * 1024
        private const val MAX_STDERR_CHARS = 4 * 1024
    }
}
