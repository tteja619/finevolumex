package dev.finevolume.app.diagnostics

import android.content.Context
import android.media.AudioManager
import android.os.Build
import dev.finevolume.app.adb.EmbeddedAdbManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostics for the public AudioManager backend and FineVolume's own
 * Wireless Debugging ADB transport/helper. Does not invoke Shizuku.
 */
@Singleton
class FineVolumeDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val embeddedAdbManager: EmbeddedAdbManager
) {
    suspend fun runChecks(): String = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).format(Date())
        val status = embeddedAdbManager.status.value

        lines += "FineVolume embedded ADB diagnostics"
        lines += "Time: " + timestamp
        lines += "Device: " + Build.MANUFACTURER + " " + Build.MODEL
        lines += "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
        lines += ""

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio == null) {
            lines += "[FAIL] Android AudioManager could not be obtained."
        } else {
            runCatching {
                val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                check(maximum > 0) { "Media stream reports an invalid maximum volume." }
                check(current in 0..maximum) { "Media stream returned an out-of-range volume index." }

                // Write the exact existing value back. This does not change the
                // audible level and verifies the public volume-control path.
                audio.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    current,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                )
                check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == current) {
                    "Media volume readback changed unexpectedly."
                }
                lines += "[PASS] Native AudioManager read/write check."
                lines += "Media stream: " + current + "/" + maximum
                lines += "Fixed-volume policy: " + audio.isVolumeFixed
                lines += "Audio mode: " + audio.mode
            }.onFailure {
                lines += "[FAIL] Native AudioManager: " + (it.message ?: it.javaClass.simpleName)
            }
        }

        lines += ""
        lines += "Embedded Wireless Debugging ADB"
        lines += "State: " + status.phase.name
        lines += "Message: " + status.message
        lines += "Pairing key stored: " + status.pairedKeyStored
        if (status.host != null && status.port != null) {
            lines += "Endpoint: " + status.host + ":" + status.port
        }

        if (status.isConnected) {
            val identity = embeddedAdbManager.runReadOnlyShellCommand("id")
            identity.fold(
                onSuccess = { output -> lines += "[PASS] ADB shell identity: " + output.trim() },
                onFailure = { lines += "[FAIL] ADB shell probe: " + (it.message ?: it.javaClass.simpleName) }
            )

            if (status.audioHelperReady) {
                val sessions = embeddedAdbManager.activeSessionCount()
                sessions.fold(
                    onSuccess = { lines += "[PASS] Audio helper IPC: " + it + " media session(s)." },
                    onFailure = { lines += "[FAIL] Audio helper IPC: " + (it.message ?: it.javaClass.simpleName) }
                )
                val apps = embeddedAdbManager.activeMediaApps()
                apps.fold(
                    onSuccess = { lines += "Active media apps: " + if (it.isEmpty()) "(none)" else it.joinToString() },
                    onFailure = { lines += "Active media app query: " + (it.message ?: it.javaClass.simpleName) }
                )
            } else {
                lines += "[WARN] ADB transport connected, but the app_process audio helper is not ready."
                status.detail?.let { lines += "Helper error: " + it }
            }
        } else {
            lines += "[INFO] ADB is not connected. Native AudioManager control remains available."
            status.detail?.let { lines += "Last error: " + it }
        }

        lines += ""
        lines += "Privileges"
        lines += "- Embedded ADB commands run as Android shell UID 2000, not root."
        lines += "- Pairing is authorized by the user through Android Wireless Debugging."
        lines += "- FineVolume does not toggle Wireless Debugging and does not restart it in a loop."
        lines += "- Android may revoke/stop Wireless Debugging; reconnect requires it to be enabled again."

        val report = lines.joinToString("\n")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_REPORT, report)
            .putLong(KEY_LAST_RUN, System.currentTimeMillis())
            .apply()
        report
    }

    fun lastReport(): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_REPORT, null)

    companion object {
        private const val PREFS = "finevolume_diagnostics"
        private const val KEY_LAST_REPORT = "last_report"
        private const val KEY_LAST_RUN = "last_run"
    }
}
