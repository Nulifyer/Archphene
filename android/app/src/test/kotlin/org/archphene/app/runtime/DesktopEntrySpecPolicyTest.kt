package org.archphene.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEntrySpecPolicyTest {
    @Test
    fun acceptsEmptySpecs() {
        assertTrue(validDesktopArgumentSpec(""))
        assertTrue(validDesktopMimeSpec(""))
    }

    @Test
    fun acceptsEveryFixedArgumentAndLiterals() {
        for (argument in listOf("f", "F", "u", "U", "i", "c", "k", "L:x", "L:literal value")) {
            assertTrue(validDesktopArgumentSpec(argument))
        }
    }

    @Test
    fun rejectsMalformedAndEmptyArguments() {
        for (argument in listOf("x", "ff", "L", "L:", "\u001ff", "f\u001f", "f\u001f\u001fk")) {
            assertFalse(validDesktopArgumentSpec(argument))
        }
    }

    @Test
    fun enforcesArgumentCountBound() {
        assertTrue(validDesktopArgumentSpec(List(32) { "f" }.joinToString("\u001f")))
        assertFalse(validDesktopArgumentSpec(List(33) { "f" }.joinToString("\u001f")))
    }

    @Test
    fun acceptsValidMimeEntries() {
        assertTrue(validDesktopMimeSpec("text/plain;application/json;"))
    }

    @Test
    fun enforcesMimeEntryCountBound() {
        assertTrue(validDesktopMimeSpec(List(16) { "type$it/value" }.joinToString(";", postfix = ";")))
        assertFalse(validDesktopMimeSpec(List(17) { "type$it/value" }.joinToString(";", postfix = ";")))
    }

    @Test
    fun rejectsMalformedMimeSpecs() {
        for (mimeSpec in listOf("text/plain", ";", "text/plain;;", "text;")) {
            assertFalse(validDesktopMimeSpec(mimeSpec))
        }
    }

    @Test
    fun rejectsExactSixteenKibibyteDelimiterFloods() {
        assertFalse(validDesktopArgumentSpec("\u001f".repeat(16 * 1024)))
        assertFalse(validDesktopMimeSpec(";".repeat(16 * 1024)))
    }
}
