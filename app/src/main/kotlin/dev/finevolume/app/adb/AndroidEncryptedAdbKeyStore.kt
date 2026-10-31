package dev.finevolume.app.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.flyfishxu.kadb.cert.KadbPrivateKeyStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists Kadb's ADB host private key encrypted with an AES key held by
 * Android Keystore. The RSA ADB key never has to be stored in plaintext.
 */
class AndroidEncryptedAdbKeyStore(context: Context) : KadbPrivateKeyStore {

    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    @Synchronized
    override fun readPrivateKeyPem(): ByteArray? {
        if (!file.baseFile.exists()) return null
        val payload = file.openRead().use { it.readBytes() }
        require(payload.size > HEADER_SIZE + GCM_TAG_BYTES) { "Stored ADB key payload is truncated." }
        require(payload[0] == FORMAT_VERSION) { "Unsupported encrypted ADB key format." }

        val ivLength = payload[1].toInt() and 0xff
        require(ivLength == GCM_IV_BYTES && payload.size > HEADER_SIZE + ivLength + GCM_TAG_BYTES) {
            "Stored ADB key IV is invalid."
        }

        val iv = payload.copyOfRange(HEADER_SIZE, HEADER_SIZE + ivLength)
        val encrypted = payload.copyOfRange(HEADER_SIZE + ivLength, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    @Synchronized
    override fun writePrivateKeyPemAtomic(privateKeyPem: ByteArray) {
        require(privateKeyPem.isNotEmpty()) { "ADB private key cannot be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        val iv = requireNotNull(cipher.iv) { "Android Keystore did not produce a GCM IV." }
        val encrypted = cipher.doFinal(privateKeyPem)
        val payload = byteArrayOf(FORMAT_VERSION, iv.size.toByte()) + iv + encrypted

        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(payload)
            stream.fd.sync()
            file.finishWrite(stream)
            stream = null
        } catch (t: Throwable) {
            stream?.let(file::failWrite)
            throw t
        }
    }

    @Synchronized
    override fun clear() {
        file.delete()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val FILE_NAME = "finevolume-adb-host-key.enc"
        private const val KEY_ALIAS = "dev.finevolume.app.adb.hostkey.aes"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val HEADER_SIZE = 2
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
    }
}
