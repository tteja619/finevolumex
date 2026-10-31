package dev.finevolume.app

import android.app.Application
import com.flyfishxu.kadb.cert.KadbCert
import dagger.hilt.android.HiltAndroidApp
import dev.finevolume.app.adb.AndroidEncryptedAdbKeyStore
import org.lsposed.hiddenapibypass.HiddenApiBypass

@HiltAndroidApp
class FineVolumeApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Kadb's TLS host identity is persisted encrypted by Android Keystore.
        // This makes Wireless Debugging pairing survive process restarts without
        // storing the private RSA key as plaintext in app storage.
        KadbCert.configure(AndroidEncryptedAdbKeyStore(this))

        // FineVolume's separate app_process helper runs as ADB shell and needs
        // access to AudioPlaybackConfiguration's player proxy APIs.
        HiddenApiBypass.addHiddenApiExemptions("")
    }
}
