package org.archphene.app.launcher

/**
 * In-process bridge used only by debug-source test receivers.
 *
 * Release builds have no exported component that can reach this object. Keeping
 * the active service reference here lets device tests exercise manager-owned
 * sessions without putting test intents or test code in generated launchers.
 */
internal object LauncherSessionDebugBridge {
    @Volatile
    private var service: LauncherSessionService? = null

    fun attach(candidate: LauncherSessionService) {
        service = candidate
    }

    fun detach(candidate: LauncherSessionService) {
        if (service === candidate) {
            service = null
        }
    }

    fun injectIme(
        androidPackage: String,
        composing: String?,
        committed: String?,
        submit: Boolean,
    ): LauncherSessionDebugResult =
        service?.debugInjectIme(androidPackage, composing, committed, submit)
            ?: LauncherSessionDebugResult(false, 0, "service-not-ready")

    fun requestDocumentSave(
        androidPackage: String,
        title: String,
        suggestedName: String,
        mimeType: String,
        payload: ByteArray,
    ): LauncherSessionDebugResult =
        service?.debugRequestDocumentSave(
            androidPackage,
            title,
            suggestedName,
            mimeType,
            payload,
        ) ?: LauncherSessionDebugResult(false, 0, "service-not-ready")

    fun printPdf(
        androidPackage: String,
        title: String,
        payload: ByteArray,
        nonRegular: Boolean,
    ): LauncherSessionDebugResult =
        service?.debugPrintPdf(androidPackage, title, payload, nonRegular)
            ?: LauncherSessionDebugResult(false, 0, "service-not-ready")

    fun playAudio(androidPackage: String): LauncherSessionDebugResult =
        service?.debugPlayAudio(androidPackage)
            ?: LauncherSessionDebugResult(false, 0, "service-not-ready")

    fun captureMicrophone(androidPackage: String): LauncherSessionDebugResult =
        service?.debugCaptureMicrophone(androidPackage)
            ?: LauncherSessionDebugResult(false, 0, "service-not-ready")
}

internal data class LauncherSessionDebugResult(
    val accepted: Boolean,
    val sessionId: Int,
    val reason: String,
)
