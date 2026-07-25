package org.archphene.app.launcher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

internal data class SignedLauncherApk(
    val apk: File,
    val signerSha256: ByteArray,
)

internal object LauncherApkSigner {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "archphene-shared-launcher-signing-v1"

    @Suppress("DEPRECATION")
    @Synchronized
    fun sign(
        context: Context,
        input: File,
        output: File,
    ): SignedLauncherApk {
        requireManaged(context, input)
        requireManaged(context, output.parentFile ?: error("Launcher output has no parent"))
        val entry = signingEntry()
        val certificate = entry.certificate as X509Certificate
        val configuration =
            ApkSigner.SignerConfig
                .Builder("Archphene", entry.privateKey, listOf(certificate))
                .build()
        val temporary = File(output.parentFile, "${output.name}.signing")
        check(!temporary.exists() || temporary.delete()) {
            "Could not reset launcher signing output"
        }
        try {
            ApkSigner
                .Builder(listOf(configuration))
                .setInputApk(input)
                .setOutputApk(temporary)
                .setMinSdkVersion(29)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setDebuggableApkPermitted(false)
                .setOtherSignersSignaturesPreserved(false)
                .setCreatedBy("Archphene")
                .build()
                .sign()
            val verified =
                ApkVerifier
                    .Builder(temporary)
                    .setMinCheckedPlatformVersion(29)
                    .build()
                    .verify()
            check(
                verified.isVerified &&
                    verified.isVerifiedUsingV3Scheme &&
                    verified.signerCertificates.size == 1,
            ) {
                "Generated launcher signature verification failed: " +
                    "verified=${verified.isVerified} " +
                    "v2=${verified.isVerifiedUsingV2Scheme} " +
                    "v3=${verified.isVerifiedUsingV3Scheme} " +
                    "signers=${verified.signerCertificates.size} " +
                    "errors=${verified.allErrors}"
            }
            val expected = sha256(certificate.encoded)
            val actual = sha256(verified.signerCertificates.single().encoded)
            check(MessageDigest.isEqual(expected, actual)) {
                "Generated launcher signer identity changed"
            }
            check(!output.exists() || output.delete()) {
                "Could not replace generated launcher"
            }
            check(temporary.renameTo(output)) {
                "Could not commit generated launcher"
            }
            return SignedLauncherApk(output, actual)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun signerSha256(): ByteArray =
        sha256((signingEntry().certificate as X509Certificate).encoded)

    private fun signingEntry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance(KEYSTORE)
        store.load(null)
        if (!store.containsAlias(KEY_ALIAS)) {
            val now = System.currentTimeMillis()
            val generator =
                KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    KEYSTORE,
                )
            generator.initialize(
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                    ).setKeySize(3072)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Archphene Shared Launcher"))
                    .setCertificateSerialNumber(BigInteger(160, SecureRandom()).abs())
                    .setCertificateNotBefore(Date(now - ONE_DAY_MILLIS))
                    .setCertificateNotAfter(Date(now + THIRTY_YEARS_MILLIS))
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKeyPair()
        }
        return store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("Archphene launcher signing key is unavailable")
    }

    private fun requireManaged(
        context: Context,
        value: File,
    ) {
        val path = value.canonicalFile.path
        val files = context.filesDir.canonicalPath
        val cache = context.cacheDir.canonicalPath
        check(
            path == files ||
                path.startsWith("$files${File.separator}") ||
                path == cache ||
                path.startsWith("$cache${File.separator}"),
        ) {
            "Launcher signing paths must remain app-private"
        }
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val THIRTY_YEARS_MILLIS = 30L * 365 * ONE_DAY_MILLIS
}
