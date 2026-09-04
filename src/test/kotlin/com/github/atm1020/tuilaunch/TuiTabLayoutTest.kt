package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.toolwindow.TuiTabLayout
import com.intellij.openapi.ui.Splitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JComponent
import javax.swing.JPanel

class TuiTabLayoutTest {

    private val terminal: JComponent = JPanel()
    private val promptBox: JComponent = JPanel()

    private fun newLayout(
        dockedHorizontally: Boolean = false,
        promptBoxPercent: Int = 30,
        promptBoxVisible: Boolean = true,
    ): TuiTabLayout = TuiTabLayout(
        terminal = terminal,
        promptBox = promptBox,
        dockedHorizontally = dockedHorizontally,
        promptBoxPercent = promptBoxPercent,
        promptBoxVisible = promptBoxVisible,
    )

    private fun splitterOf(layout: TuiTabLayout): Splitter = layout.component as Splitter

    @Test
    fun `a side docked tool window stacks the prompt box under the terminal`() {
        val splitter = splitterOf(newLayout(dockedHorizontally = false))

        assertTrue(splitter.isVertical)
        assertSame(terminal, splitter.firstComponent)
        assertSame(promptBox, splitter.secondComponent)
    }

    @Test
    fun `a horizontally docked tool window puts the prompt box beside the terminal`() {
        assertFalse(splitterOf(newLayout(dockedHorizontally = true)).isVertical)
    }

    @Test
    fun `moving the tool window to another edge flips the split direction`() {
        val layout = newLayout(dockedHorizontally = false)

        layout.setDockedHorizontally(true)
        assertFalse(splitterOf(layout).isVertical)

        layout.setDockedHorizontally(false)
        assertTrue(splitterOf(layout).isVertical)
    }

    @Test
    fun `the percent is the prompt box share and the proportion is the terminal share`() {
        val layout = newLayout(promptBoxPercent = 30)

        assertEquals(0.7f, splitterOf(layout).proportion, 0.001f)

        layout.promptBoxPercent = 40

        assertEquals(40, layout.promptBoxPercent)
        assertEquals(0.6f, splitterOf(layout).proportion, 0.001f)
    }

    @Test
    fun `a percent outside the allowed range is clamped`() {
        assertEquals(10, newLayout(promptBoxPercent = 0).promptBoxPercent)
        assertEquals(90, newLayout(promptBoxPercent = 150).promptBoxPercent)
    }

    @Test
    fun `dragging the divider reports the new prompt box percent`() {
        val layout = newLayout(promptBoxPercent = 30)
        val reported = mutableListOf<Int>()
        layout.onPromptBoxPercentChanged = { reported.add(it) }

        splitterOf(layout).proportion = 0.45f

        assertEquals(listOf(55), reported)
        assertEquals(55, layout.promptBoxPercent)
    }

    @Test
    fun `dragging the divider all the way reports a clamped percent`() {
        val layout = newLayout(promptBoxPercent = 30)
        val reported = mutableListOf<Int>()
        layout.onPromptBoxPercentChanged = { reported.add(it) }

        splitterOf(layout).proportion = 1f
        splitterOf(layout).proportion = 0f

        assertEquals(listOf(10, 90), reported)
    }

    @Test
    fun `applying a percent does not report a change back`() {
        val layout = newLayout(promptBoxPercent = 30)
        val reported = mutableListOf<Int>()
        layout.onPromptBoxPercentChanged = { reported.add(it) }

        layout.promptBoxPercent = 60

        assertTrue(reported.isEmpty())
        assertEquals(60, layout.promptBoxPercent)
    }

    @Test
    fun `a hidden prompt box keeps its component out of the layout`() {
        val layout = newLayout(promptBoxVisible = false)

        assertFalse(splitterOf(layout).secondComponent.isVisible)
        assertFalse(layout.promptBoxVisible)

        layout.promptBoxVisible = true

        assertTrue(splitterOf(layout).secondComponent.isVisible)
        assertTrue(layout.promptBoxVisible)
    }
}
