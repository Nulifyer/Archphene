package org.archphene.app.launcher

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

internal data class VerifiedLauncherIdentity(
    val androidPackage: String,
    val descriptorIdHex: String,
    val generation: Long,
)

internal object LauncherIdentityVerifier {
    private const val PACKAGE_PREFIX = "org.archphene.linux.p"
    private const val DESCRIPTOR_ID = "org.archphene.launcher.DESCRIPTOR_ID"
    private const val GENERATION = "org.archphene.launcher.GENERATION"
    private const val MANAGER_PACKAGE = "org.archphene.launcher.MANAGER_PACKAGE"
    private const val TEMPLATE_SHA256 = "org.archphene.launcher.TEMPLATE_SHA256"
    private const val CAPABILITIES = "org.archphene.launcher.CAPABILITIES"

    @Suppress("DEPRECATION")
    fun verify(
        context: Context,
        callingUid: Int,
    ): VerifiedLauncherIdentity? {
        val packages = context.packageManager.getPackagesForUid(callingUid) ?: return null
        val androidPackage = packages.singleOrNull() ?: return null
        if (
            androidPackage.length != PACKAGE_PREFIX.length + 32 ||
            !androidPackage.startsWith(PACKAGE_PREFIX) ||
            !androidPackage
                .asSequence()
                .drop(PACKAGE_PREFIX.length)
                .all { character -> character.isDigit() || character in 'a'..'f' }
        ) {
            return null
        }
        val info =
            try {
                context.packageManager.getPackageInfo(
                    androidPackage,
                    PackageManager.GET_META_DATA or
                        PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                return null
            }
        val metadata = info.applicationInfo?.metaData ?: return null
        val descriptor =
            metadata
                .getString(DESCRIPTOR_ID)
                ?.takeIf { value ->
                    value.length == 66 &&
                        value.startsWith("d:") &&
                        value
                            .asSequence()
                            .drop(2)
                            .all { character ->
                                character.isDigit() || character in 'a'..'f'
                            }
                }?.drop(2)
                ?: return null
        val generation =
            metadata
                .getString(GENERATION)
                ?.takeIf { value ->
                    value.length == 22 &&
                        value.startsWith("g:") &&
                        value.asSequence().drop(2).all(Char::isDigit)
                }?.drop(2)
                ?.toLongOrNull()
                ?.takeIf { value -> value in 1..Int.MAX_VALUE.toLong() }
                ?: return null
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        if (
            info.packageName != androidPackage ||
            info.longVersionCode != generation ||
            metadata.getString(MANAGER_PACKAGE) != context.packageName ||
            metadata.getString(TEMPLATE_SHA256) !=
            "h:${LauncherApkAssembler.templateDigestHex(context)}" ||
            metadata.getString(CAPABILITIES) !=
            "c:${LauncherApkAssembler.CAPABILITIES_V2}" ||
            signers.size != 1
        ) {
            return null
        }
        val actualSigner =
            MessageDigest
                .getInstance("SHA-256")
                .digest(signers.single().toByteArray())
        if (!MessageDigest.isEqual(actualSigner, LauncherApkSigner.signerSha256())) {
            return null
        }
        return VerifiedLauncherIdentity(androidPackage, descriptor, generation)
    }
}
