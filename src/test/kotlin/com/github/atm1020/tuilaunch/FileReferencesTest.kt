package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.LineRange
import com.github.atm1020.tuilaunch.prompt.fileReference
import com.github.atm1020.tuilaunch.prompt.pathRelativeToRoot
import com.github.atm1020.tuilaunch.prompt.referenceInsertion
import com.github.atm1020.tuilaunch.prompt.selectedLineRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileReferencesTest {

    @Test
    fun `a file without a selection is referenced by path alone`() {
        assertEquals("@src/main/App.kt", fileReference("src/main/App.kt", null))
    }

    @Test
    fun `a multi line selection is referenced with a line range`() {
        assertEquals("@src/main/App.kt#L113-115", fileReference("src/main/App.kt", LineRange(113, 115)))
    }

    @Test
    fun `a selection inside one line is referenced with a single line`() {
        assertEquals("@App.kt#L7", fileReference("App.kt", LineRange(7, 7)))
    }

    @Test
    fun `a path under the root becomes relative to it`() {
        assertEquals("src/App.kt", pathRelativeToRoot("/home/me/project", "/home/me/project/src/App.kt"))
    }

    @Test
    fun `windows separators and a trailing root separator still resolve`() {
        assertEquals("src/App.kt", pathRelativeToRoot("C:\\Projects\\app\\", "C:\\Projects\\app\\src\\App.kt"))
    }

    @Test
    fun `a path outside the root has no relative form`() {
        assertNull(pathRelativeToRoot("/home/me/project", "/etc/hosts"))
        assertNull(pathRelativeToRoot("/home/me/project", "/home/me/project"))
        assertNull(pathRelativeToRoot("", "/home/me/project/src/App.kt"))
    }

    @Test
    fun `selection lines are reported one based`() {
        assertEquals(LineRange(113, 115), selectedLineRange(112, 114, endsAtLineStart = false))
    }

    @Test
    fun `a selection stopping at the start of a line drops that line`() {
        assertEquals(LineRange(113, 115), selectedLineRange(112, 115, endsAtLineStart = true))
    }

    @Test
    fun `a caret on the start of its own line keeps that line`() {
        assertEquals(LineRange(113, 113), selectedLineRange(112, 112, endsAtLineStart = true))
    }

    @Test
    fun `an insertion is padded away from the text around it`() {
        assertEquals("@App.kt ", referenceInsertion("", 0, "@App.kt"))
        assertEquals(" @App.kt ", referenceInsertion("look at", 7, "@App.kt"))
        assertEquals("@App.kt ", referenceInsertion("look at ", 8, "@App.kt"))
        assertEquals(" @App.kt", referenceInsertion("look at now", 7, "@App.kt"))
    }
}
