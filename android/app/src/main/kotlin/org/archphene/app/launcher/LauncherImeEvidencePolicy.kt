package org.archphene.app.launcher

internal object LauncherImeEvidencePolicy {
    const val NONE = 0
    const val EMPTY_ONLY = 1
    const val STRONG = 2

    fun classify(
        surroundingTextLength: Int,
        hint: Int,
        purpose: Int,
        cursorRectangleWidth: Int,
        cursorRectangleHeight: Int,
    ): Int =
        when {
            surroundingTextLength > 0 ||
                purpose != 0 ||
                hint and IME_HINT_MULTILINE.inv() != 0 ||
                (cursorRectangleWidth >= 0 && cursorRectangleHeight >= 0) -> STRONG
            surroundingTextLength == 0 -> EMPTY_ONLY
            else -> NONE
        }

    private const val IME_HINT_MULTILINE = 1 shl 9
}
