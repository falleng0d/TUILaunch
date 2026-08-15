package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.terminal.encodeAndSend
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.event.KeyEvent

/**
 * Unit tests for the encode/fallback seam [encodeAndSend] extracted from `JediTermSession`'s `sendKey`
 * lambda. This is the piece that was actually broken: the `sendKey` lambda was invoked correctly by
 * [com.github.atm1020.tuilaunch.terminal.TerminalKeyInterceptor], but its body silently dropped bare
 * Escape because `TerminalStarter.getCode(27, 0)` returns `null` — `TerminalKeyEncoder` has no entry
 * for `VK_ESCAPE` — and the old `?.let { ... }` chain had nowhere to fall back to. These tests exercise
 * the encode-then-fallback logic directly, headless and without a real JediTerm widget.
 */
class EncodeAndSendTest {

    @Test
    fun usesEncoderBytesWhenTheEncoderHasAMapping() {
        var sentBytes: ByteArray? = null
        var sentString: String? = null
        val bytes = byteArrayOf(1, 2, 3)

        encodeAndSend(
            getCode = { _, _ -> bytes },
            sendBytes = { sentBytes = it },
            sendString = { sentString = it },
            keyCode = KeyEvent.VK_UP,
            modifiers = 0,
            keyChar = KeyEvent.CHAR_UNDEFINED,
        )

        assertArrayEquals(bytes, sentBytes)
        assertNull(sentString)
    }

    @Test
    fun fallsBackToTheKeyCharWhenTheEncoderHasNoMapping() {
        var sentBytes: ByteArray? = null
        var sentString: String? = null

        // TerminalKeyEncoder has no entry for VK_ESCAPE (27), so getCode returns null here exactly as
        // the real TerminalStarter.getCode(27, 0) does.
        encodeAndSend(
            getCode = { _, _ -> null },
            sendBytes = { sentBytes = it },
            sendString = { sentString = it },
            keyCode = KeyEvent.VK_ESCAPE,
            modifiers = 0,
            keyChar = KeyEvent.VK_ESCAPE.toChar(),
        )

        assertNull(sentBytes)
        assertEquals(KeyEvent.VK_ESCAPE.toChar().toString(), sentString)
    }

    @Test
    fun sendsNothingWhenTheEncoderHasNoMappingAndTheCharIsUndefined() {
        var sentBytes: ByteArray? = null
        var sentString: String? = null

        encodeAndSend(
            getCode = { _, _ -> null },
            sendBytes = { sentBytes = it },
            sendString = { sentString = it },
            keyCode = KeyEvent.VK_F13,
            modifiers = 0,
            keyChar = KeyEvent.CHAR_UNDEFINED,
        )

        assertNull(sentBytes)
        assertNull(sentString)
    }

    @Test
    fun modifiersAreForwardedUnchangedToTheEncoder() {
        var seenModifiers: Int? = null

        encodeAndSend(
            getCode = { _, modifiers -> seenModifiers = modifiers; null },
            sendBytes = {},
            sendString = {},
            keyCode = KeyEvent.VK_ESCAPE,
            modifiers = KeyEvent.CTRL_DOWN_MASK,
            keyChar = KeyEvent.CHAR_UNDEFINED,
        )

        assertEquals(KeyEvent.CTRL_DOWN_MASK, seenModifiers)
    }
}
