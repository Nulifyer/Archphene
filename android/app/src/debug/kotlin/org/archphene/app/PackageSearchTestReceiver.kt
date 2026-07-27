package org.archphene.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

internal class PackageSearchTestReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_SEED) {
            return
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val workerHoldMillis = intent.getIntExtra(EXTRA_WORKER_HOLD_MILLIS, 0)
        if (
            token == null ||
            !TOKEN.matches(token) ||
            workerHoldMillis !in 0..MAX_WORKER_HOLD_MILLIS
        ) {
            Log.e(TAG, "Rejected invalid package-search fixture")
            return
        }
        val pending = goAsync()
        Thread(
            {
                try {
                    val sync = File(context.filesDir, "arch-root/var/lib/pacman/sync")
                    check(sync.mkdirs() || sync.isDirectory) {
                        "could not create package catalog directory"
                    }
                    writeCatalog(
                        File(sync, "core.db"),
                        listOf(
                            PackageRecord(
                                "base",
                                "3-2",
                                "Minimal package set to define a basic Arch Linux installation",
                            ),
                            PackageRecord(
                                "glibc",
                                "2.42+r33+gde5fe48316ed-1",
                                "GNU C Library",
                            ),
                        ),
                    )
                    writeCatalog(
                        File(sync, "extra.db"),
                        listOf(
                            PackageRecord(
                                "dotnet-runtime",
                                "10.0.4.sdk104-1",
                                "The .NET runtime",
                            ),
                            PackageRecord(
                                "dotnet-sdk",
                                "10.0.100.sdk100-1",
                                "The .NET SDK",
                            ),
                            PackageRecord(
                                "dotnet-sdk-preview",
                                "11.0.0.preview.6-1",
                                "Preview .NET SDK for early testing",
                            ),
                        ),
                    )
                    val local =
                        File(context.filesDir, "arch-root/var/lib/pacman/local")
                    check(local.mkdirs() || local.isDirectory) {
                        "could not create local package database"
                    }
                    File(local, "ALPM_DB_VERSION").writeText("9\n")
                    writeInstalledPackage(
                        local,
                        "dotnet-runtime",
                        "10.0.4.sdk104-1",
                        "The .NET runtime",
                    )
                    writeInstalledPackage(
                        local,
                        "dotnet-sdk-preview",
                        "10.0.0.preview.1-1",
                        "Previously installed preview .NET SDK",
                    )
                    if (workerHoldMillis != 0) {
                        check(
                            context
                                .getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)
                                .edit()
                                .putLong(TEST_WORKER_HOLD_MILLIS, workerHoldMillis.toLong())
                                .commit(),
                        ) {
                            "could not save package-worker start gate"
                        }
                    }
                    Log.i(TAG, "Seeded package search token=$token")
                } catch (error: Exception) {
                    Log.e(TAG, "Package-search fixture failed token=$token", error)
                } finally {
                    pending.finish()
                }
            },
            "ArchphenePackageSearchProbe",
        ).start()
    }

    private fun writeInstalledPackage(
        local: File,
        name: String,
        version: String,
        description: String,
    ) {
        val directory = File(local, "$name-$version")
        check(directory.mkdirs() || directory.isDirectory) {
            "could not create installed package record"
        }
        File(directory, "desc").writeText(
            "%NAME%\n$name\n\n%VERSION%\n$version\n\n%DESC%\n$description\n\n" +
                "%ARCH%\nany\n\n%REASON%\n0\n\n%VALIDATION%\npgp\n",
            StandardCharsets.UTF_8,
        )
        File(directory, "files").writeText("%FILES%\n", StandardCharsets.UTF_8)
    }

    private fun writeCatalog(
        destination: File,
        packages: List<PackageRecord>,
    ) {
        GZIPOutputStream(
            BufferedOutputStream(FileOutputStream(destination, false)),
        ).use { output ->
            packages.forEach { packageRecord ->
                val description =
                    buildString {
                        append("%NAME%\n")
                        append(packageRecord.name)
                        append("\n\n%VERSION%\n")
                        append(packageRecord.version)
                        append("\n\n%DESC%\n")
                        append(packageRecord.description)
                        append("\n\n%CSIZE%\n1\n\n%ISIZE%\n1\n\n%ARCH%\nany\n")
                        append("\n%FILENAME%\n")
                        append(packageRecord.name)
                        append('-')
                        append(packageRecord.version)
                        append("-any.pkg.tar.zst\n")
                    }.toByteArray(StandardCharsets.UTF_8)
                writeTarFile(
                    output,
                    "${packageRecord.name}-${packageRecord.version}/desc",
                    description,
                )
            }
            output.write(ByteArray(TAR_BLOCK_BYTES * 2))
        }
    }

    private fun writeTarFile(
        output: OutputStream,
        path: String,
        contents: ByteArray,
    ) {
        val header = ByteArray(TAR_BLOCK_BYTES)
        writeAscii(header, 0, 100, path)
        writeOctal(header, 100, 8, 0x1a4)
        writeOctal(header, 108, 8, 0)
        writeOctal(header, 116, 8, 0)
        writeOctal(header, 124, 12, contents.size.toLong())
        writeOctal(header, 136, 12, 0)
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = '0'.code.toByte()
        writeAscii(header, 257, 6, "ustar")
        writeAscii(header, 263, 2, "00")
        writeAscii(header, 265, 32, "archphene")
        writeAscii(header, 297, 32, "archphene")
        var checksum = 0
        header.forEach { byte -> checksum += byte.toInt() and 0xff }
        val encodedChecksum = checksum.toString(8).padStart(6, '0')
        writeAscii(header, 148, 6, encodedChecksum)
        header[154] = 0
        header[155] = ' '.code.toByte()
        output.write(header)
        output.write(contents)
        val padding = (TAR_BLOCK_BYTES - contents.size % TAR_BLOCK_BYTES) % TAR_BLOCK_BYTES
        if (padding > 0) {
            output.write(ByteArray(padding))
        }
    }

    private fun writeAscii(
        destination: ByteArray,
        offset: Int,
        width: Int,
        value: String,
    ) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        check(bytes.size <= width) { "tar field is too long" }
        bytes.copyInto(destination, offset, 0, bytes.size)
    }

    private fun writeOctal(
        destination: ByteArray,
        offset: Int,
        width: Int,
        value: Long,
    ) {
        val encoded = value.toString(8).padStart(width - 1, '0')
        check(encoded.length == width - 1) { "tar number is too large" }
        writeAscii(destination, offset, width - 1, encoded)
        destination[offset + width - 1] = 0
    }

    private class PackageRecord(
        val name: String,
        val version: String,
        val description: String,
    )

    private companion object {
        private const val TAG = "ArchphenePackageSearchProbe"
        private const val ACTION_SEED =
            "org.archphene.app.debug.action.SEED_PACKAGE_SEARCH"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_WORKER_HOLD_MILLIS = "worker-hold-ms"
        private const val MAX_WORKER_HOLD_MILLIS = 5_000
        private const val TEST_PREFERENCES = "package_job_test"
        private const val TEST_WORKER_HOLD_MILLIS = "worker_hold_ms"
        private const val TAR_BLOCK_BYTES = 512
        private val TOKEN = Regex("[a-z0-9-]{1,48}")
    }
}
