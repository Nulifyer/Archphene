package org.archphene.app

internal object DocumentSharePolicy {
    const val MAX_DOCUMENTS = 32

    fun commonMimeType(mimeTypes: List<String>): String {
        require(mimeTypes.isNotEmpty())
        val first = mimeTypes.first()
        if (mimeTypes.all { it == first }) {
            return first
        }
        val topLevel = first.substringBefore('/', "")
        return if (
            topLevel.isNotEmpty() &&
            mimeTypes.all { mimeType -> mimeType.substringBefore('/', "") == topLevel }
        ) {
            "$topLevel/*"
        } else {
            "*/*"
        }
    }
}
