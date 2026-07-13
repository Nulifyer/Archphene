package org.archpheneos.manager;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.security.auth.x500.X500Principal;

public final class ArchWrapperSigner {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "archphene-wrapper-signing-v1";

    public static final class Result {
        public final File apk;
        public final String signerSha256;
        public final boolean verifiedV2;
        public final boolean verifiedV3;

        Result(File apk, String signerSha256, boolean verifiedV2, boolean verifiedV3) {
            this.apk = apk;
            this.signerSha256 = signerSha256;
            this.verifiedV2 = verifiedV2;
            this.verifiedV3 = verifiedV3;
        }
    }

    private ArchWrapperSigner() {}

    public static synchronized Result sign(Context context, File inputApk, File outputApk)
            throws Exception {
        requireManaged(context, inputApk);
        requireManaged(context, outputApk.getParentFile());
        KeyStore.PrivateKeyEntry entry = signingEntry();
        PrivateKey key = entry.getPrivateKey();
        X509Certificate certificate = (X509Certificate) entry.getCertificate();
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("Archphene", key,
                Collections.singletonList(certificate)).build();
        File temporary = new File(outputApk.getParentFile(), outputApk.getName() + ".new");
        if (temporary.exists() && !temporary.delete()) {
            throw new IllegalStateException("Could not reset generated APK output");
        }
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(inputApk)
                .setOutputApk(temporary)
                .setMinSdkVersion(23)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setDebuggableApkPermitted(true)
                .setOtherSignersSignaturesPreserved(false)
                .setCreatedBy("Archphene")
                .build().sign();
        ApkVerifier.Result verified = new ApkVerifier.Builder(temporary)
                .setMinCheckedPlatformVersion(23).build().verify();
        if (!verified.isVerified() || !verified.isVerifiedUsingV2Scheme()
                || verified.getSignerCertificates().size() != 1) {
            temporary.delete();
            throw new SecurityException("Generated APK signature verification failed: "
                    + verified.getAllErrors());
        }
        String actual = sha256(verified.getSignerCertificates().get(0).getEncoded());
        String expected = sha256(certificate.getEncoded());
        if (!actual.equals(expected)) {
            temporary.delete();
            throw new SecurityException("Generated APK signer identity changed");
        }
        if (outputApk.exists() && !outputApk.delete()) {
            temporary.delete();
            throw new IllegalStateException("Could not replace generated APK");
        }
        if (!temporary.renameTo(outputApk)) {
            temporary.delete();
            throw new IllegalStateException("Could not commit generated APK");
        }
        return new Result(outputApk, actual, verified.isVerifiedUsingV2Scheme(),
                verified.isVerifiedUsingV3Scheme());
    }

    public static synchronized String signerSha256() throws Exception {
        return sha256(signingEntry().getCertificate().getEncoded());
    }

    private static KeyStore.PrivateKeyEntry signingEntry() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            long now = System.currentTimeMillis();
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE);
            generator.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(3072)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=Archphene Device Wrapper"))
                    .setCertificateSerialNumber(new BigInteger(160,
                            new java.security.SecureRandom()).abs())
                    .setCertificateNotBefore(new Date(now - 24L * 60 * 60 * 1000))
                    .setCertificateNotAfter(new Date(now + 30L * 365 * 24 * 60 * 60 * 1000))
                    .setUserAuthenticationRequired(false)
                    .build());
            generator.generateKeyPair();
            store.load(null);
        }
        KeyStore.Entry value = store.getEntry(KEY_ALIAS, null);
        if (!(value instanceof KeyStore.PrivateKeyEntry)) {
            throw new SecurityException("Archphene wrapper signing key is unavailable");
        }
        return (KeyStore.PrivateKeyEntry) value;
    }

    private static void requireManaged(Context context, File value) throws Exception {
        File file = value.getCanonicalFile();
        String path = file.getPath();
        String files = context.getFilesDir().getCanonicalPath();
        String cache = context.getCacheDir().getCanonicalPath();
        if (!(path.equals(files) || path.startsWith(files + File.separator)
                || path.equals(cache) || path.startsWith(cache + File.separator))) {
            throw new SecurityException("APK signing paths must remain app-private");
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder output = new StringBuilder();
        for (byte part : digest) output.append(String.format(Locale.ROOT, "%02x", part & 0xff));
        return output.toString();
    }
}