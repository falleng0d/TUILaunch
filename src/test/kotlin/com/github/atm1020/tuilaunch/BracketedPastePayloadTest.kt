package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.terminal.bracketedPastePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

private const val PASTE_START = "\u001B[200~"
private const val PASTE_END = "\u001B[201~"

class BracketedPastePayloadTest {

    @Test
    fun aSingleLinePayloadIsSentWithoutPasteMarkers() {
        assertEquals("hello world", bracketedPastePayload("hello world"))
    }

    @Test
    fun aMultiLinePayloadIsWrappedInPasteMarkers() {
        assertEquals(PASTE_START + "first\rsecond" + PASTE_END, bracketedPastePayload("first\nsecond"))
    }

    @Test
    fun bothLineEndingStylesBecomeCarriageReturns() {
        val payload = bracketedPastePayload("first\r\nsecond\nthird")

        assertEquals(PASTE_START + "first\rsecond\rthird" + PASTE_END, payload)
    }

    @Test
    fun trailingNewlinesAreDroppedSoNothingIsSubmitted() {
        assertEquals("only line", bracketedPastePayload("only line\n\n"))
        assertEquals(PASTE_START + "first\rsecond" + PASTE_END, bracketedPastePayload("first\nsecond\r\n\n"))
    }

    @Test
    fun theWrappedPayloadNeverEndsWithACarriageReturn() {
        val payload = bracketedPastePayload("first\nsecond\n")

        assertFalse(payload.removeSuffix(PASTE_END).endsWith('\r'))
    }

    @Test
    fun anEmbeddedEndMarkerIsStrippedSoThePasteCannotBeCutShort() {
        assertEquals("before after", bracketedPastePayload("before " + PASTE_END + "after"))
        assertEquals(
            PASTE_START + "before\rafter" + PASTE_END,
            bracketedPastePayload("before" + PASTE_END + "\nafter"),
        )
    }

    @Test
    fun anEndMarkerAtTheVeryEndLeavesNoTrailingCarriageReturn() {
        assertEquals("first\rsecond".let { PASTE_START + it + PASTE_END }, bracketedPastePayload("first\nsecond\n" + PASTE_END))
    }

    @Test
    fun aLoneStartMarkerIsStrippedSoTheAppIsNotLeftInPasteMode() {
        assertEquals("beforeafter", bracketedPastePayload("before" + PASTE_START + "after"))
        assertEquals(
            PASTE_START + "before\rafter" + PASTE_END,
            bracketedPastePayload("before" + PASTE_START + "\nafter"),
        )
    }

    @Test
    fun aMarkerSplitAcrossAnotherMarkerCannotSurviveStripping() {
        val splitEndMarker = PASTE_END.dropLast(2) + PASTE_END + PASTE_END.takeLast(2)

        val payload = bracketedPastePayload("before" + splitEndMarker + "after")

        assertEquals("beforeafter", payload)
        assertFalse(payload.contains(PASTE_END))
    }

    @Test
    fun anEmptyTextStaysEmptyAndUnwrapped() {
        assertEquals("", bracketedPastePayload("\n\n"))
    }
}
