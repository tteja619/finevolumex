package dev.finevolume.app.adb;

import android.content.Context;
import android.os.Build;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * FineVolume's own ADB identity and connection manager.
 *
 * The RSA identity is stored only in this app's no-backup private directory.
 * It is never shared with Shizuku or another ADB client.
 */
public final class FineVolumeAdbConnectionManager extends AbsAdbConnectionManager {

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
        keyFile = new File(identityDir, "host-private.pk8");
        certificateFile = new File(identityDir, "host-certificate.der");
        setApi(Build.VERSION.SDK_INT);
        setTimeout(12, TimeUnit.SECONDS);
        loadOrCreateIdentity();
    }

    private void loadOrCreateIdentity() throws Exception {
        if (keyFile.isFile() && certificateFile.isFile()) {
            try {
                byte[] encodedKey = readAll(keyFile);
                byte[] encodedCertificate = readAll(certificateFile);
                privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(encodedKey));
                certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new java.io.ByteArrayInputStream(encodedCertificate));
                return;
            } catch (Exception invalidIdentity) {
                keyFile.delete();
                certificateFile.delete();
            }
        }
        createIdentity();
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
        info.set("serialNumber",
                new CertificateSerialNumber(nextSerialNumber()));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(pair.getPublic()));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);

        X509CertImpl generated = new X509CertImpl(info);
        generated.sign(privateKey, algorithm);
        certificate = generated;

        writeAtomically(keyFile, privateKey.getEncoded());
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
            byte[] bytes = new byte[(int) file.length()];
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
