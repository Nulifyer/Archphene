package org.archphene.app.runtime

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class ArchpheneRuntimeService : Service() {
    inner class LocalBinder : Binder() {
        val runtimeHandle: Long
            get() = readyHandle

        val packageCatalogStatus: String
            get() = catalogStatus

        val packageSearchStatus: String
            get() = searchStatus

        val packageJobStatus: String
            get() = jobStatus

        fun refreshPackageCatalogs(): Boolean = requestCatalogRefresh()

        fun searchPackages(query: String): Boolean = requestPackageSearch(query)

        fun resolvePackage(packageName: String): Boolean =
            requestPackageResolution(packageName)

        fun installPackage(packageName: String): Boolean =
            requestPackageInstall(packageName)
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var handle = 0L
    @Volatile private var readyHandle = 0L
    private var bootstrapThread: Thread? = null
    private var catalogThread: Thread? = null
    private var packageThread: Thread? = null
    @Volatile private var catalogRefreshActive = false
    @Volatile private var catalogStatus = "Package catalog not downloaded"
    @Volatile private var searchActive = false
    @Volatile private var searchStatus = "Search the official Arch repositories"
    @Volatile private var packageOperationActive = false
    @Volatile private var jobStatus = "No package transaction"
    @Volatile private var lastResolvedPackage = ""
    @Volatile private var lastResolvedRepository = ""

    private data class ResolvedPayload(
        val repository: String,
        val name: String,
        val version: String,
        val filename: String,
        val url: String,
        val size: Long,
    )

    private class PackageIoScratch {
        val requestBuffer: ByteBuffer = ByteBuffer.allocateDirect(512)
        val messageBuffer: ByteBuffer = ByteBuffer.allocateDirect(192)
        val outputBuffer: ByteBuffer =
            ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val transferBuffer = ByteArray(64 * 1024)
    }

    override fun onCreate() {
        super.onCreate()
        if (NativeRuntime.nativeProtocolVersion() != NativeRuntime.PROTOCOL_VERSION) {
            Log.e(TAG, "Native protocol version mismatch")
            stopSelf()
            return
        }
        handle = NativeRuntime.nativeCreate()
        if (handle == 0L) {
            Log.e(TAG, "Native runtime creation failed")
            stopSelf()
            return
        }
        startBootstrap(handle)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        val activeHandle = handle
        handle = 0L
        readyHandle = 0L
        bootstrapThread?.interrupt()
        bootstrapThread = null
        catalogThread?.interrupt()
        catalogThread = null
        packageThread?.interrupt()
        packageThread = null
        if (activeHandle != 0L) {
            NativeRuntime.nativeTransition(activeHandle, NativeRuntime.LIFECYCLE_STOPPING)
            NativeRuntime.nativeTransition(activeHandle, NativeRuntime.LIFECYCLE_STOPPED)
            if (!NativeRuntime.nativeDestroy(activeHandle)) {
                Log.e(TAG, "Native runtime handle was already closed")
            }
        }
        Log.i(TAG, "Shared Rust runtime stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ArchpheneRuntime"
    }

    private fun startBootstrap(activeHandle: Long) {
        bootstrapThread =
            Thread(
                {
                    try {
                        val pathBytes =
                            File(filesDir, "arch-root")
                                .absolutePath
                                .toByteArray(StandardCharsets.UTF_8)
                        val pathBuffer = ByteBuffer.allocateDirect(pathBytes.size)
                        pathBuffer.put(pathBytes)
                        val createdDirectories =
                            NativeRuntime.nativeBootstrapArchRoot(
                                activeHandle,
                                pathBuffer,
                                pathBytes.size,
                                System.currentTimeMillis(),
                            )
                        if (createdDirectories < 0) {
                            throw IllegalStateException(
                                "Shared Arch root bootstrap failed: $createdDirectories",
                            )
                        }
                        val packageVersion = preparePackageRuntime(activeHandle)
                        jobStatus = readLatestPackageJob(activeHandle)
                        mainHandler.post {
                            if (handle != activeHandle) {
                                return@post
                            }
                            if (
                                NativeRuntime.nativeTransition(
                                    activeHandle,
                                    NativeRuntime.LIFECYCLE_RUNNING,
                                ) != 0
                            ) {
                                Log.e(TAG, "Native runtime start transition failed")
                                stopSelf()
                                return@post
                            }
                            readyHandle = activeHandle
                            Log.i(TAG, "Package runtime ready: $packageVersion")
                            Log.i(
                                TAG,
                                "Shared Rust runtime started; root directories created=$createdDirectories",
                            )
                        }
                    } catch (error: Exception) {
                        mainHandler.post {
                            if (handle == activeHandle) {
                                Log.e(TAG, "Runtime bootstrap failed", error)
                                stopSelf()
                            }
                        }
                    }
                },
                "ArchpheneBootstrap",
            ).also(Thread::start)
    }

    private fun preparePackageRuntime(activeHandle: Long): String {
        val (architecture, repositoryArchitecture) =
            when (Build.SUPPORTED_ABIS.firstOrNull()) {
                "x86_64" -> "x86_64" to NativeRuntime.REPOSITORY_X86_64
                "arm64-v8a" -> "aarch64" to NativeRuntime.REPOSITORY_AARCH64
                else -> throw IllegalStateException("Unsupported Android ABI")
            }
        val nativePathBytes =
            File(applicationInfo.nativeLibraryDir)
                .canonicalPath
                .toByteArray(StandardCharsets.UTF_8)
        val manifestBytes =
            assets.open("package-runtime-$architecture.tsv").use { input ->
                input.readBytes()
            }
        if (manifestBytes.isEmpty() || manifestBytes.size > NativeRuntime.PACKAGE_MANIFEST_LIMIT) {
            throw IllegalStateException("Invalid package-runtime manifest size")
        }
        val nativePathBuffer = ByteBuffer.allocateDirect(nativePathBytes.size)
        nativePathBuffer.put(nativePathBytes)
        val manifestBuffer = ByteBuffer.allocateDirect(manifestBytes.size)
        manifestBuffer.put(manifestBytes)
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength =
            NativeRuntime.nativePreparePackageRuntime(
                activeHandle,
                repositoryArchitecture,
                nativePathBuffer,
                nativePathBytes.size,
                manifestBuffer,
                manifestBytes.size,
                outputBuffer,
            )
        if (outputLength <= 0 || outputLength > NativeRuntime.PACKAGE_OUTPUT_SIZE) {
            outputBuffer.position(0)
            val diagnosticBytes = ByteArray(NativeRuntime.PACKAGE_OUTPUT_SIZE)
            outputBuffer.get(diagnosticBytes)
            val terminator = diagnosticBytes.indexOf(0)
            val diagnosticLength =
                if (terminator >= 0) {
                    terminator
                } else {
                    diagnosticBytes.size
                }
            val diagnostic =
                String(
                    diagnosticBytes,
                    0,
                    diagnosticLength,
                    StandardCharsets.UTF_8,
                ).trim()
            throw IllegalStateException(
                buildString {
                    append("Package-runtime probe failed: ")
                    append(outputLength)
                    if (diagnostic.isNotEmpty()) {
                        append(" (")
                        append(diagnostic)
                        append(')')
                    }
                },
            )
        }
        val outputBytes = ByteArray(outputLength)
        outputBuffer.position(0)
        outputBuffer.get(outputBytes)
        return outputBytes
            .toString(StandardCharsets.UTF_8)
            .lineSequence()
            .map(String::trim)
            .firstOrNull { line -> line.contains("Pacman v") }
            ?: throw IllegalStateException("Package-runtime probe returned no pacman version")
    }

    @Synchronized
    private fun requestCatalogRefresh(): Boolean {
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive
        ) {
            return false
        }
        catalogRefreshActive = true
        catalogThread =
            Thread(
                {
                    try {
                        catalogStatus = "Refreshing core package catalog"
                        downloadCatalog(activeHandle, NativeRuntime.CATALOG_CORE)
                        catalogStatus = "Refreshing extra package catalog"
                        downloadCatalog(activeHandle, NativeRuntime.CATALOG_EXTRA)
                        catalogStatus = "Package catalog ready"
                        Log.i(TAG, "Official package catalogs refreshed")
                    } catch (error: Exception) {
                        catalogStatus =
                            "Catalog refresh failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "Package catalog refresh failed", error)
                    } finally {
                        catalogRefreshActive = false
                        catalogThread = null
                    }
                },
                "ArchpheneCatalog",
            ).also(Thread::start)
        return true
    }

    private fun downloadCatalog(
        activeHandle: Long,
        repository: Int,
    ) {
        val messageBuffer = ByteBuffer.allocateDirect(NativeRuntime.CATALOG_MESSAGE_SIZE)
        val descriptor =
            NativeRuntime.nativeBeginPackageCatalogDownload(
                activeHandle,
                repository,
                messageBuffer,
            )
        if (descriptor < 0) {
            throw IllegalStateException(readNativeMessage(messageBuffer, descriptor))
        }
        var finishAttempted = false
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(descriptor),
            ).use { output ->
                val endpoint = URL(readCString(messageBuffer))
                val expectedHost =
                    when (Build.SUPPORTED_ABIS.firstOrNull()) {
                        "x86_64" -> "geo.mirror.pkgbuild.com"
                        "arm64-v8a" -> "ca.us.mirror.archlinuxarm.org"
                        else -> throw IllegalStateException("Unsupported Android ABI")
                    }
                if (
                    endpoint.protocol != "https" ||
                    endpoint.host != expectedHost ||
                    endpoint.userInfo != null ||
                    endpoint.port != -1
                ) {
                    throw SecurityException("Rust supplied an invalid catalog endpoint")
                }
                val maximumBytes =
                    when (repository) {
                        NativeRuntime.CATALOG_CORE -> 8L * 1024 * 1024
                        NativeRuntime.CATALOG_EXTRA -> 64L * 1024 * 1024
                        else -> throw IllegalArgumentException("Unknown catalog")
                    }
                val connection = endpoint.openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    val status = connection.responseCode
                    if (status != HttpsURLConnection.HTTP_OK) {
                        throw IllegalStateException("Catalog server returned HTTP $status")
                    }
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > maximumBytes) {
                        throw IllegalStateException("Catalog exceeds its download limit")
                    }
                    connection.inputStream.use { input ->
                        val transferBuffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(transferBuffer)
                            if (count < 0) {
                                break
                            }
                            total += count
                            if (total > maximumBytes) {
                                throw IllegalStateException("Catalog exceeds its download limit")
                            }
                            output.write(transferBuffer, 0, count)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            messageBuffer.position(0)
            val published =
                NativeRuntime.nativeFinishPackageCatalogDownload(
                    activeHandle,
                    repository,
                    true,
                    messageBuffer,
                )
            finishAttempted = true
            if (published <= 0) {
                throw IllegalStateException(readNativeMessage(messageBuffer, published))
            }
        } finally {
            if (!finishAttempted) {
                messageBuffer.position(0)
                NativeRuntime.nativeFinishPackageCatalogDownload(
                    activeHandle,
                    repository,
                    false,
                    messageBuffer,
                )
            }
        }
    }

    private fun readNativeMessage(
        buffer: ByteBuffer,
        result: Int,
    ): String {
        val message = readCString(buffer)
        return if (message.isEmpty()) {
            "Native package operation failed: $result"
        } else {
            "$message ($result)"
        }
    }

    private fun readNativeMessage(
        buffer: ByteBuffer,
        result: Long,
    ): String = readNativeMessage(buffer, result.coerceAtLeast(Int.MIN_VALUE.toLong()).toInt())

    private fun readCString(buffer: ByteBuffer): String {
        buffer.position(0)
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        val terminator = bytes.indexOf(0)
        val length = if (terminator >= 0) terminator else bytes.size
        return String(bytes, 0, length, StandardCharsets.UTF_8)
    }

    @Synchronized
    private fun requestPackageSearch(query: String): Boolean {
        val normalized = query.trim()
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive ||
            normalized.length !in 2..128 ||
            normalized.any { character ->
                character.code > 0x7f ||
                    (!character.isLetterOrDigit() && character !in "@._+:-")
            }
        ) {
            searchStatus = "Enter 2–128 package-name characters"
            return false
        }
        searchActive = true
        searchStatus = "Searching for $normalized"
        Thread(
            {
                try {
                    val queryBytes = normalized.toByteArray(StandardCharsets.UTF_8)
                    val queryBuffer = ByteBuffer.allocateDirect(queryBytes.size)
                    queryBuffer.put(queryBytes)
                    val outputBuffer =
                        ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
                    val outputLength =
                        NativeRuntime.nativeSearchPackages(
                            activeHandle,
                            queryBuffer,
                            queryBytes.size,
                            outputBuffer,
                        )
                    if (outputLength < 0) {
                        throw IllegalStateException(
                            readNativeMessage(outputBuffer, outputLength),
                        )
                    }
                    if (outputLength == 0) {
                        searchStatus = "No official packages match $normalized"
                    } else {
                        val bytes = ByteArray(outputLength)
                        outputBuffer.position(0)
                        outputBuffer.get(bytes)
                        searchStatus =
                            String(bytes, StandardCharsets.UTF_8)
                                .lineSequence()
                                .mapNotNull { line ->
                                    val fields = line.split('\t', limit = 4)
                                    if (fields.size != 4) {
                                        null
                                    } else {
                                        "${fields[0]}/${fields[1]} ${fields[2]}\n" +
                                            "  ${fields[3]}"
                                    }
                                }.joinToString("\n")
                                .ifEmpty { "No official packages match $normalized" }
                    }
                } catch (error: Exception) {
                    searchStatus =
                        "Package search failed: ${error.message ?: error.javaClass.simpleName}"
                    Log.e(TAG, "Package search failed", error)
                } finally {
                    searchActive = false
                }
            },
            "ArchpheneSearch",
        ).start()
        return true
    }

    @Synchronized
    private fun requestPackageResolution(packageName: String): Boolean {
        val normalized = packageName.trim()
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive ||
            normalized.length !in 1..128 ||
            normalized.any { character ->
                character.code > 0x7f ||
                    (!character.isLetterOrDigit() && character !in "@._+-")
            }
        ) {
            searchStatus = "Enter one exact official package name"
            return false
        }
        searchActive = true
        searchStatus = "Resolving $normalized and its dependencies"
        Thread(
            {
                try {
                    val packages = resolvePayloads(activeHandle, normalized)
                    var totalBytes = 0L
                    var target: ResolvedPayload? = null
                    val packageNames = StringBuilder()
                    packages.forEach { resolved ->
                        totalBytes = Math.addExact(totalBytes, resolved.size)
                        if (resolved.name == normalized) {
                            target = resolved
                        }
                        if (packageNames.isNotEmpty()) {
                            packageNames.append('\n')
                        }
                        packageNames.append("  ").append(resolved.name)
                    }
                    val resolvedTarget =
                        target
                            ?: throw IllegalStateException(
                                "Resolved packages omit the requested target",
                            )
                    lastResolvedPackage = normalized
                    lastResolvedRepository = resolvedTarget.repository
                    val mebibytes = (totalBytes + (1024 * 1024 - 1)) / (1024 * 1024)
                    searchStatus =
                        buildString {
                            append(resolvedTarget.repository)
                            append('/')
                            append(resolvedTarget.name)
                            append(' ')
                            append(resolvedTarget.version)
                            append('\n')
                            append("Dependency closure: ")
                            append(packages.size)
                            append(" packages · ")
                            append(mebibytes)
                            append(" MiB download")
                            append("\n\nPackages\n")
                            append(packageNames)
                        }
                    Log.i(
                        TAG,
                        "Resolved $normalized: ${packages.size} packages, $totalBytes bytes",
                    )
                } catch (error: Exception) {
                    searchStatus =
                        "Package resolution failed: ${error.message ?: error.javaClass.simpleName}"
                    Log.e(TAG, "Package resolution failed", error)
                } finally {
                    searchActive = false
                }
            },
            "ArchpheneResolve",
        ).start()
        return true
    }

    private fun resolvePayloads(
        activeHandle: Long,
        packageName: String,
    ): List<ResolvedPayload> {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        val packageBuffer = ByteBuffer.allocateDirect(packageBytes.size)
        packageBuffer.put(packageBytes)
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val outputLength =
            NativeRuntime.nativeResolvePackage(
                activeHandle,
                packageBuffer,
                packageBytes.size,
                outputBuffer,
            )
        if (outputLength <= 0) {
            throw IllegalStateException(readNativeMessage(outputBuffer, outputLength))
        }
        val bytes = ByteArray(outputLength)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        val packages = ArrayList<ResolvedPayload>()
        String(bytes, StandardCharsets.UTF_8)
            .lineSequence()
            .filter(String::isNotEmpty)
            .forEach { line ->
                val fields = line.split('\t', limit = 6)
                val size = fields.getOrNull(5)?.toLongOrNull()
                if (fields.size != 6 || size == null || size <= 0) {
                    throw IllegalStateException("Rust returned an invalid resolution")
                }
                packages.add(
                    ResolvedPayload(
                        repository = fields[0],
                        name = fields[1],
                        version = fields[2],
                        filename = fields[3],
                        url = fields[4],
                        size = size,
                    ),
                )
                if (packages.size > 256) {
                    throw IllegalStateException("Package closure exceeds its limit")
                }
            }
        if (packages.none { payload -> payload.name == packageName }) {
            throw IllegalStateException("Resolved packages omit the requested target")
        }
        return packages
    }

    @Synchronized
    private fun requestPackageInstall(packageName: String): Boolean {
        val normalized = packageName.trim()
        val repository = lastResolvedRepository
        val activeHandle = readyHandle
        if (
            activeHandle == 0L ||
            catalogRefreshActive ||
            searchActive ||
            packageOperationActive ||
            normalized != lastResolvedPackage ||
            (repository != "core" && repository != "extra")
        ) {
            jobStatus = "Open Details for one exact package before installing it"
            return false
        }
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val requestBytes = "$repository\t$normalized".toByteArray(StandardCharsets.UTF_8)
        val requestBuffer = ByteBuffer.allocateDirect(requestBytes.size)
        requestBuffer.put(requestBytes)
        val jobId =
            NativeRuntime.nativeQueuePackagePrepare(
                activeHandle,
                requestBuffer,
                requestBytes.size,
                System.currentTimeMillis(),
                outputBuffer,
            )
        if (jobId <= 0) {
            jobStatus = "Could not queue install: ${readNativeMessage(outputBuffer, jobId)}"
            return false
        }
        packageOperationActive = true
        jobStatus = "$normalized · Queued · 0%\nQueued"
        packageThread =
            Thread(
                {
                    val scratch = PackageIoScratch()
                    var recordedPhase = 0
                    var recordedProgress = 0
                    fun record(
                        state: Int,
                        phase: Int,
                        progress: Int,
                        message: String,
                    ) {
                        updatePackageJob(
                            activeHandle,
                            jobId,
                            state,
                            phase,
                            progress,
                            message,
                            normalized,
                            scratch,
                        )
                        recordedPhase = phase
                        recordedProgress = progress
                    }
                    try {
                        record(
                            NativeRuntime.JOB_RESOLVING,
                            1,
                            5,
                            "Resolving signed dependency closure",
                        )
                        val packages = resolvePayloads(activeHandle, normalized)
                        val target =
                            packages.firstOrNull { payload -> payload.name == normalized }
                                ?: throw IllegalStateException(
                                    "Resolved packages omit the requested target",
                                )
                        if (target.repository != repository) {
                            throw SecurityException("Target repository changed during install")
                        }
                        packages.forEachIndexed { index, payload ->
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException("Package install interrupted")
                            }
                            val progress = 10 + (index * 65 / packages.size)
                            record(
                                NativeRuntime.JOB_DOWNLOADING,
                                2,
                                progress,
                                "Downloading ${payload.name} (${index + 1}/${packages.size})",
                            )
                            if (isCachedPackageValid(activeHandle, payload, scratch)) {
                                return@forEachIndexed
                            }
                            downloadPackagePayload(activeHandle, payload, false, scratch)
                            downloadPackagePayload(activeHandle, payload, true, scratch)
                        }
                        packages.forEachIndexed { index, payload ->
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException("Package install interrupted")
                            }
                            val progress = 76 + (index * 20 / packages.size)
                            record(
                                NativeRuntime.JOB_VERIFYING,
                                3,
                                progress,
                                "Verifying ${payload.name} (${index + 1}/${packages.size})",
                            )
                            verifyPackagePayload(activeHandle, payload, scratch)
                        }
                        record(
                            NativeRuntime.JOB_INSTALLING,
                            4,
                            97,
                            "Installing verified packages",
                        )
                        installPreparedPackage(activeHandle, normalized, scratch)
                        record(
                            NativeRuntime.JOB_COMPLETE,
                            5,
                            100,
                            "Installed ${target.name} ${target.version}",
                        )
                        Log.i(
                            TAG,
                            "Installed $normalized: ${packages.size} signed packages",
                        )
                    } catch (error: Exception) {
                        try {
                            updatePackageJob(
                                activeHandle,
                                jobId,
                                NativeRuntime.JOB_FAILED,
                                recordedPhase,
                                recordedProgress,
                                boundedJobMessage(
                                    "Install failed: ${error.message ?: error.javaClass.simpleName}",
                                ),
                                normalized,
                                scratch,
                            )
                        } catch (updateError: Exception) {
                            jobStatus =
                                "Install failed and journal update failed: " +
                                    (updateError.message ?: updateError.javaClass.simpleName)
                        }
                        Log.e(TAG, "Package install failed", error)
                    } finally {
                        packageOperationActive = false
                        packageThread = null
                    }
                },
                "ArchpheneInstall",
            ).also(Thread::start)
        return true
    }

    private fun installPreparedPackage(
        activeHandle: Long,
        packageName: String,
        scratch: PackageIoScratch,
    ) {
        val packageBytes = packageName.toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(packageBytes)
        scratch.outputBuffer.clear()
        val outputLength =
            NativeRuntime.nativeInstallPackage(
                activeHandle,
                scratch.requestBuffer,
                packageBytes.size,
                scratch.outputBuffer,
            )
        if (outputLength < 0) {
            throw IllegalStateException(readNativeMessage(scratch.outputBuffer, outputLength))
        }
    }

    private fun updatePackageJob(
        activeHandle: Long,
        jobId: Long,
        state: Int,
        phase: Int,
        progress: Int,
        message: String,
        packageName: String,
        scratch: PackageIoScratch,
    ) {
        val safeMessage = boundedJobMessage(message)
        val messageBytes = safeMessage.toByteArray(StandardCharsets.UTF_8)
        scratch.messageBuffer.clear()
        scratch.messageBuffer.put(messageBytes)
        val result =
            NativeRuntime.nativeUpdatePackageJob(
                activeHandle,
                jobId,
                state,
                phase,
                progress,
                scratch.messageBuffer,
                messageBytes.size,
                System.currentTimeMillis(),
                scratch.outputBuffer,
            )
        if (result != 0) {
            throw IllegalStateException(readNativeMessage(scratch.outputBuffer, result))
        }
        jobStatus =
            "$packageName · ${jobStateName(state)} · $progress%\n$safeMessage"
    }

    private fun boundedJobMessage(message: String): String {
        val sanitized =
            message
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .ifEmpty { "Package operation" }
        var end = minOf(sanitized.length, 192)
        while (
            end > 0 &&
            sanitized.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > 192
        ) {
            end -= 1
        }
        return sanitized.substring(0, end).ifEmpty { "Package operation" }
    }

    private fun jobStateName(state: Int): String =
        when (state) {
            NativeRuntime.JOB_QUEUED -> "Queued"
            NativeRuntime.JOB_RESOLVING -> "Resolving"
            NativeRuntime.JOB_DOWNLOADING -> "Downloading"
            NativeRuntime.JOB_VERIFYING -> "Verifying"
            NativeRuntime.JOB_INSTALLING -> "Publishing"
            NativeRuntime.JOB_COMPLETE -> "Complete"
            NativeRuntime.JOB_FAILED -> "Failed"
            NativeRuntime.JOB_CANCELLED -> "Cancelled"
            else -> "Unknown"
        }

    private fun downloadPackagePayload(
        activeHandle: Long,
        payload: ResolvedPayload,
        signature: Boolean,
        scratch: PackageIoScratch,
    ) {
        val filenameBytes = payload.filename.toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(filenameBytes)
        val descriptor =
            NativeRuntime.nativeBeginPackageDownload(
                activeHandle,
                scratch.requestBuffer,
                filenameBytes.size,
                payload.size,
                signature,
                scratch.outputBuffer,
            )
        if (descriptor < 0) {
            throw IllegalStateException(readNativeMessage(scratch.outputBuffer, descriptor))
        }
        var finishAttempted = false
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(
                ParcelFileDescriptor.adoptFd(descriptor),
            ).use { output ->
                val expectedEndpoint = if (signature) "${payload.url}.sig" else payload.url
                val endpoint = URL(expectedEndpoint)
                val expectedHost =
                    when (Build.SUPPORTED_ABIS.firstOrNull()) {
                        "x86_64" -> "geo.mirror.pkgbuild.com"
                        "arm64-v8a" -> "ca.us.mirror.archlinuxarm.org"
                        else -> throw IllegalStateException("Unsupported Android ABI")
                    }
                if (
                    endpoint.toString() != expectedEndpoint ||
                    endpoint.protocol != "https" ||
                    endpoint.host != expectedHost ||
                    endpoint.userInfo != null ||
                    endpoint.port != -1
                ) {
                    throw SecurityException("Rust supplied an invalid package endpoint")
                }
                val maximumBytes = if (signature) 1024L * 1024 else payload.size
                val connection = endpoint.openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 60_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
                        throw IllegalStateException(
                            "Package server returned HTTP ${connection.responseCode}",
                        )
                    }
                    val declaredLength = connection.contentLengthLong
                    if (
                        declaredLength == 0L ||
                        declaredLength > maximumBytes ||
                        !signature && declaredLength >= 0 && declaredLength != payload.size
                    ) {
                        throw SecurityException("Package object has an invalid size")
                    }
                    connection.inputStream.use { input ->
                        var total = 0L
                        while (true) {
                            val count = input.read(scratch.transferBuffer)
                            if (count < 0) {
                                break
                            }
                            total += count
                            if (total > maximumBytes) {
                                throw SecurityException("Package object exceeds its limit")
                            }
                            output.write(scratch.transferBuffer, 0, count)
                        }
                        if (
                            total == 0L ||
                            !signature && total != payload.size ||
                            declaredLength >= 0 && total != declaredLength
                        ) {
                            throw SecurityException("Package object transfer is incomplete")
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            scratch.outputBuffer.position(0)
            val published =
                NativeRuntime.nativeFinishPackageDownload(
                    activeHandle,
                    true,
                    scratch.outputBuffer,
                )
            finishAttempted = true
            if (published <= 0) {
                throw IllegalStateException(readNativeMessage(scratch.outputBuffer, published))
            }
        } finally {
            if (!finishAttempted) {
                scratch.outputBuffer.position(0)
                NativeRuntime.nativeFinishPackageDownload(
                    activeHandle,
                    false,
                    scratch.outputBuffer,
                )
            }
        }
    }

    private fun verifyPackagePayload(
        activeHandle: Long,
        payload: ResolvedPayload,
        scratch: PackageIoScratch,
    ) {
        val requestBytes =
            "${payload.filename}\t${payload.name}\t${payload.version}"
                .toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(requestBytes)
        val result =
            NativeRuntime.nativeVerifyPackage(
                activeHandle,
                scratch.requestBuffer,
                requestBytes.size,
                payload.size,
                scratch.outputBuffer,
            )
        if (result <= 0) {
            throw SecurityException(readNativeMessage(scratch.outputBuffer, result))
        }
    }

    private fun isCachedPackageValid(
        activeHandle: Long,
        payload: ResolvedPayload,
        scratch: PackageIoScratch,
    ): Boolean {
        val requestBytes =
            "${payload.filename}\t${payload.name}\t${payload.version}"
                .toByteArray(StandardCharsets.UTF_8)
        scratch.requestBuffer.clear()
        scratch.requestBuffer.put(requestBytes)
        val result =
            NativeRuntime.nativeVerifyPackage(
                activeHandle,
                scratch.requestBuffer,
                requestBytes.size,
                payload.size,
                scratch.outputBuffer,
            )
        if (result > 0) {
            return true
        }
        val diagnostic = readNativeMessage(scratch.outputBuffer, result)
        if (!diagnostic.contains("No such file")) {
            Log.w(TAG, "Rejected invalid cached package ${payload.filename}: $diagnostic")
        }
        return false
    }

    private fun readLatestPackageJob(activeHandle: Long): String {
        val outputBuffer = ByteBuffer.allocateDirect(NativeRuntime.PACKAGE_OUTPUT_SIZE)
        val length = NativeRuntime.nativeReadLatestPackageJob(activeHandle, outputBuffer)
        if (length < 0) {
            return "Package journal unavailable: ${readNativeMessage(outputBuffer, length)}"
        }
        if (length == 0) {
            return "No package transaction"
        }
        val bytes = ByteArray(length)
        outputBuffer.position(0)
        outputBuffer.get(bytes)
        val fields = String(bytes, StandardCharsets.UTF_8).trimEnd().split('\t', limit = 9)
        if (fields.size != 9) {
            return "Package journal returned an invalid record"
        }
        val state = fields[2].toIntOrNull() ?: return "Package journal returned invalid state"
        val progress = fields[4].toIntOrNull() ?: return "Package journal returned invalid progress"
        return "${fields[7]} · ${jobStateName(state)} · $progress%\n${fields[8]}"
    }
}
