package dev.finevolume.app.adb;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * FineVolume's own ADB identity and connection manager.
 *
 * The RSA host key is encrypted with an AES-GCM key stored in Android Keystore.
 * Only the public X.509 certificate is stored unencrypted. This identity is
 * unique to FineVolume and is not shared with Shizuku or another ADB client.
 */
public final class FineVolumeAdbConnectionManager extends AbsAdbConnectionManager {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ENCRYPTION_KEY_ALIAS = "dev.finevolume.app.wirelessadb.hostkey";
    private static final String ENCRYPTION_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte KEY_FORMAT_VERSION = 1;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int ENCRYPTED_HEADER_BYTES = 2;

    private final File keyFile;
    private final File certificateFile;
    private PrivateKey privateKey;
    private Certificate certificate;

    public FineVolumeAdbConnectionManager(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File identityDir = new File(app.getNoBackupFilesDir(), "wireless-adb");
        if (!identityDir.exists() && !identityDir.mkdirs()) {
            throw new IOException("Could not create the private ADB identity directory.");
        }
        keyFile = new File(identityDir, "host-private.pk8.enc");
        certificateFile = new File(identityDir, "host-certificate.der");
        setApi(Build.VERSION.SDK_INT);
        setTimeout(12, TimeUnit.SECONDS);
        loadOrCreateIdentity();
    }

    private void loadOrCreateIdentity() throws Exception {
        if (keyFile.isFile() && certificateFile.isFile()) {
            byte[] encodedKey = readPrivateKey();
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
            certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(readAll(certificateFile)));
            return;
        }

        // Migrate the old plaintext identity in-place before generating a new one.
        File legacyKey = new File(keyFile.getParentFile(), "host-private.pk8");
        File legacyCertificate = new File(keyFile.getParentFile(), "host-certificate.der");
        if (legacyKey.isFile() && legacyCertificate.isFile()) {
            byte[] legacyPrivateKey = readAll(legacyKey);
            privateKey = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(legacyPrivateKey));
            certificate = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(readAll(legacyCertificate)));
            writeEncryptedPrivateKey(legacyPrivateKey);
            legacyKey.delete();
            return;
        }

        createIdentity();
    }

    /**
     * Reads the encrypted identity. A legacy plaintext key found at the old
     * location is migrated by loadOrCreateIdentity() without changing the ADB
     * public key, so existing Android pairing authorization is retained.
     */
    private byte[] readPrivateKey() throws Exception {
        byte[] payload = readAll(keyFile);
        if (payload.length < ENCRYPTED_HEADER_BYTES + GCM_IV_BYTES + 16
                || payload[0] != KEY_FORMAT_VERSION) {
            throw new IOException("Saved ADB private-key file has an unknown or truncated format.");
        }

        int ivLength = payload[1] & 0xff;
        if (ivLength != GCM_IV_BYTES || payload.length <= ENCRYPTED_HEADER_BYTES + ivLength + 16) {
            throw new IOException("Saved ADB private-key encryption header is invalid.");
        }

        byte[] iv = new byte[ivLength];
        System.arraycopy(payload, ENCRYPTED_HEADER_BYTES, iv, 0, ivLength);
        byte[] ciphertext = new byte[payload.length - ENCRYPTED_HEADER_BYTES - ivLength];
        System.arraycopy(payload, ENCRYPTED_HEADER_BYTES + ivLength,
                ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance(ENCRYPTION_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(),
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    private void writeEncryptedPrivateKey(byte[] encodedKey) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV_BYTES) {
            throw new IOException("Android Keystore returned an invalid AES-GCM IV.");
        }

        byte[] ciphertext = cipher.doFinal(encodedKey);
        byte[] payload = new byte[ENCRYPTED_HEADER_BYTES + iv.length + ciphertext.length];
        payload[0] = KEY_FORMAT_VERSION;
        payload[1] = (byte) iv.length;
        System.arraycopy(iv, 0, payload, ENCRYPTED_HEADER_BYTES, iv.length);
        System.arraycopy(ciphertext, 0, payload, ENCRYPTED_HEADER_BYTES + iv.length, ciphertext.length);
        writeAtomically(keyFile, payload);
    }

    private SecretKey getOrCreateEncryptionKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(ENCRYPTION_KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }

    private void createIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        privateKey = pair.getPrivate();

        String algorithm = "SHA256withRSA";
        Date notBefore = new Date(System.currentTimeMillis() - 60_000L);
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365L * 24L * 60L * 60L * 1000L);
        X500Name name = new X500Name("CN=FineVolume Wireless ADB");
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(pair.getPublic()).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(nextSerialNumber()));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(pair.getPublic()));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);

        X509CertImpl generated = new X509CertImpl(info);
        generated.sign(privateKey, algorithm);
        certificate = generated;

        writeEncryptedPrivateKey(privateKey.getEncoded());
        writeAtomically(certificateFile, certificate.getEncoded());
    }

    private static int nextSerialNumber() {
        int value;
        do {
            value = new SecureRandom().nextInt() & Integer.MAX_VALUE;
        } while (value == 0);
        return value;
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            long length = file.length();
            if (length <= 0 || length > 1024 * 1024) {
                throw new IOException("ADB identity file has an invalid size.");
            }
            byte[] bytes = new byte[(int) length];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IOException("Unexpected end of ADB identity file.");
                offset += read;
            }
            return bytes;
        }
    }

    private static void writeAtomically(File target, byte[] bytes) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Could not save the ADB identity file.");
        }
    }

    public void clearIdentity() throws Exception {
        if (keyFile.exists() && !keyFile.delete()) {
            throw new IOException("Could not delete the saved ADB private-key file.");
        }
        if (certificateFile.exists() && !certificateFile.delete()) {
            throw new IOException("Could not delete the saved ADB certificate file.");
        }
        File legacyKey = new File(keyFile.getParentFile(), "host-private.pk8");
        if (legacyKey.exists() && !legacyKey.delete()) {
            throw new IOException("Could not delete the legacy ADB private-key file.");
        }

        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(ENCRYPTION_KEY_ALIAS)) {
            keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS);
        }

        // libadb 3.1.1 caches a process-wide SSLContext, which retains the old
        // client certificate even after the ADB key is changed. Clear it when
        // the user explicitly forgets pairing so a new identity can re-pair.
        try {
            Class<?> sslUtils = Class.forName("io.github.muntashirakon.adb.SslUtils");
            Field contextField = sslUtils.getDeclaredField("sslContext");
            contextField.setAccessible(true);
            contextField.set(null, null);
        } catch (ClassNotFoundException ignored) {
            // The dependency may change implementation in a future release.
        } catch (NoSuchFieldException ignored) {
            // No static context cache in a future/fixed libadb release.
        }

        privateKey = null;
        certificate = null;
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "FineVolume-" + Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public boolean isIdentityStored() {
        return keyFile.isFile() && certificateFile.isFile();
    }

    public void pairWithLocalDevice(int pairingPort, String pairingCode) throws Exception {
        if (!pair("127.0.0.1", pairingPort, pairingCode)) {
            throw new IOException("Android Wireless Debugging rejected the pairing code.");
        }
    }

    public boolean connectToLocalWirelessDebugging(Context context, long timeoutMillis)
            throws Exception {
        return connectTls(context.getApplicationContext(), timeoutMillis);
    }
}
