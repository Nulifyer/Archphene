package org.archphene.app.runtime

import java.nio.charset.StandardCharsets

internal data class PackageCompatibility(
    val status: String,
    val capabilities: Int,
    val packageCount: Int,
    val elfCount: Int,
    val commandCount: Int,
    val diagnostic: String,
    val diagnosticPackage: String?,
)

internal fun decodePackageCompatibility(bytes: ByteArray): PackageCompatibility {
    val text = String(bytes, StandardCharsets.US_ASCII)
    val fields =
        parsePackageCompatibilityFields(text)
            ?: throw IllegalStateException("Rust returned invalid package compatibility")
    val status = fields.getOrNull(0)
    val capabilityCharacter = fields.getOrNull(1)?.singleOrNull()
    val capabilities =
        capabilityCharacter
            ?.takeIf { character -> character in '0'..'9' || character in 'a'..'f' }
            ?.digitToIntOrNull(16)
    val packageCount = canonicalNonNegativeInt(fields.getOrNull(2))
    val elfCount = canonicalNonNegativeInt(fields.getOrNull(3))
    val commandCount = canonicalNonNegativeInt(fields.getOrNull(4))
    val diagnostic = fields.getOrNull(5)
    val diagnosticPackageField = fields.getOrNull(6)
    val diagnosticPackage =
        diagnosticPackageField?.takeUnless { value -> value == "-" }
    val validDiagnosticPackage =
        diagnosticPackage == null ||
            (
                diagnosticPackage.length <= 255 &&
                    diagnosticPackage.matches(Regex("[A-Za-z0-9@._+\\-]+"))
            )
    val validStatus =
        when (status) {
            "not-analyzed",
            "bridge-eligible",
            "managed-only",
            "unsupported",
            -> true
            else -> false
        }
    val validDiagnostic =
        when (diagnostic) {
            "none",
            "not-cached",
            "foreign-elf",
            "native-in-any-package",
            "malformed-elf",
            "incompatible-page-size",
            "unsupported-command",
            -> true
            else -> false
        }
    if (
        fields.size != 7 ||
        !validStatus ||
        capabilities == null ||
        packageCount == null ||
        packageCount !in 1..512 ||
        elfCount == null ||
        elfCount !in 0..1_000_000 ||
        commandCount == null ||
        commandCount !in 0..262_144 ||
        !validDiagnostic ||
        !validDiagnosticPackage ||
        status == "unsupported" && diagnosticPackage == null ||
        status != "unsupported" && diagnosticPackage != null ||
        status == "not-analyzed" &&
            (
                capabilities != 0 ||
                    elfCount != 0 ||
                    commandCount != 0 ||
                    diagnostic != "not-cached"
            ) ||
        status == "unsupported" &&
            (diagnostic == "none" || diagnostic == "not-cached") ||
        status != "unsupported" &&
            status != "not-analyzed" &&
            diagnostic != "none"
    ) {
        throw IllegalStateException("Rust returned invalid package compatibility")
    }
    val decodedStatus = checkNotNull(status)
    val decodedDiagnostic = checkNotNull(diagnostic)
    return PackageCompatibility(
        status = decodedStatus,
        capabilities = capabilities,
        packageCount = packageCount,
        elfCount = elfCount,
        commandCount = commandCount,
        diagnostic = decodedDiagnostic,
        diagnosticPackage = diagnosticPackage,
    )
}

private fun parsePackageCompatibilityFields(text: String): List<String>? {
    if (!text.endsWith('\n')) return null
    val end = text.length - 1
    if (end == 0 || text.indexOf('\n') in 0 until end || text.indexOf('\r') in 0 until end) {
        return null
    }

    val fields = ArrayList<String>(7)
    var fieldStart = 0
    repeat(6) {
        val fieldEnd = text.indexOf('\t', fieldStart)
        if (fieldEnd < fieldStart || fieldEnd >= end) return null
        fields.add(text.substring(fieldStart, fieldEnd))
        fieldStart = fieldEnd + 1
    }
    val extraField = text.indexOf('\t', fieldStart)
    if (extraField >= fieldStart && extraField < end) return null
    fields.add(text.substring(fieldStart, end))
    return fields
}

private fun canonicalNonNegativeInt(value: String?): Int? {
    val parsed = value?.toIntOrNull() ?: return null
    return parsed.takeIf { number -> number >= 0 && number.toString() == value }
}
