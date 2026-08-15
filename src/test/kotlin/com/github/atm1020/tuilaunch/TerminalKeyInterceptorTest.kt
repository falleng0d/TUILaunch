package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.terminal.TerminalKeyInterceptor
import com.intellij.openapi.actionSystem.KeyboardShortcut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke

class TerminalKeyInterceptorTest {

    private val terminal = JPanel()

    private val elsewhere = JPanel()

    private var switchCount = 0
    private var closeCount = 0
    private var nextCount = 0
    private var previousCount = 0

    private var nextTabCount = 0
    private var previousTabCount = 0

    private val sentKeys = mutableListOf<Triple<Int, Int, Char>>()

    private val nextTabShortcut = KeyboardShortcut(KeyStroke.getKeyStroke("alt RIGHT"), null)
    private val previousTabShortcut = KeyboardShortcut(KeyStroke.getKeyStroke("alt LEFT"), null)

    private fun newDispatcher(
        canSendKeys: Boolean = true,
        nextTabShortcuts: List<KeyboardShortcut> = listOf(nextTabShortcut),
        previousTabShortcuts: List<KeyboardShortcut> = listOf(previousTabShortcut),
    ) = TerminalKeyInterceptor(
        terminalComponent = terminal,
        escapeModifierMask = KeyEvent.CTRL_DOWN_MASK,
        escapeKeyCode = KeyEvent.VK_SPACE,
        prefixCommandActions = mapOf(
            KeyEvent.VK_E to { switchCount++ },
            KeyEvent.VK_C to { closeCount++ },
            KeyEvent.VK_N to { nextCount++ },
            KeyEvent.VK_P to { previousCount++ },
        ),
        canSendKeys = canSendKeys,
        sendKey = { keyCode, modifiers, keyChar -> sentKeys += Triple(keyCode, modifiers, keyChar) },
        nextTabShortcuts = { nextTabShortcuts },
        previousTabShortcuts = { previousTabShortcuts },
        onNextTab = { nextTabCount++ },
        onPreviousTab = { previousTabCount++ },
    )

    private fun keyPress(
        keyCode: Int,
        modifiers: Int = 0,
        source: Component = terminal,
        whenMs: Long = System.currentTimeMillis(),
        keyChar: Char = KeyEvent.CHAR_UNDEFINED,
    ): KeyEvent = KeyEvent(source, KeyEvent.KEY_PRESSED, whenMs, modifiers, keyCode, keyChar)

    private fun keyTyped(
        keyChar: Char,
        source: Component = terminal,
        whenMs: Long = System.currentTimeMillis(),
    ): KeyEvent = KeyEvent(source, KeyEvent.KEY_TYPED, whenMs, 0, KeyEvent.VK_UNDEFINED, keyChar)

    private fun armPrefix(dispatcher: TerminalKeyInterceptor) {
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK)))
    }

    @Test
    fun comboWhileFocusedEntersPrefixModeAndIsSwallowed() {
        val dispatcher = newDispatcher()
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK)))
        assertEquals(0, switchCount)
    }

    @Test
    fun bareSwitchKeyPassesThrough() {
        val dispatcher = newDispatcher()
        assertFalse(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_SPACE, modifiers = 0)))
        assertEquals(0, switchCount)
    }

    @Test
    fun wrongKeyWithModifierPassesThrough() {
        val dispatcher = newDispatcher()
        assertFalse(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK)))
        assertEquals(0, switchCount)
    }

    @Test
    fun comboWhileNotFocusedPassesThrough() {
        val dispatcher = newDispatcher()
        val event = keyPress(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK, source = elsewhere)
        assertFalse(dispatcher.dispatchKeyEvent(event))
        assertEquals(0, switchCount)
    }

    @Test
    fun duplicateDeliveryFiresOnce() {
        val dispatcher = newDispatcher()
        val event = keyPress(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK)
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertEquals(0, switchCount)
    }

    @Test
    fun comboTrailingTypedCharIsSwallowed() {
        val dispatcher = newDispatcher()
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK)))
        assertTrue(dispatcher.dispatchKeyEvent(keyTyped(' ')))
    }

    @Test
    fun typedCharWithoutComboPassesThrough() {
        val dispatcher = newDispatcher()
        assertFalse(dispatcher.dispatchKeyEvent(keyTyped('a')))
    }

    @Test
    fun keyPressAfterComboStopsSwallowingTyped() {
        val dispatcher = newDispatcher()
        dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK))
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_A)))
        assertFalse(dispatcher.dispatchKeyEvent(keyTyped('a')))
    }

    @Test
    fun prefixThenEFocusesEditor() {
        val dispatcher = newDispatcher()
        armPrefix(dispatcher)
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_E)))
        assertEquals(1, switchCount)
    }

    @Test
    fun prefixThenCCloseActiveTui() {
        val dispatcher = newDispatcher()
        armPrefix(dispatcher)
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_C)))
        assertEquals(1, closeCount)
    }

    @Test
    fun prefixCommandTrailingTypedCharIsSwallowed() {
        val dispatcher = newDispatcher()
        armPrefix(dispatcher)
        assertTrue(dispatcher.dispatchKeyEvent(keyTyped(' ')))
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_C)))
        assertTrue(dispatcher.dispatchKeyEvent(keyTyped('c')))
        assertEquals(1, closeCount)
    }

    @Test
    fun duplicatePrefixCommandPressIsSwallowedWithoutRunningTwice() {
        val dispatcher = newDispatcher()
        armPrefix(dispatcher)
        val event = keyPress(KeyEvent.VK_C)
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertEquals(1, closeCount)
    }

    @Test
    fun prefixThenNSwitchesToNextTui() {
        val dispatcher = newDispatcher()
        armPrefix(dispatcher)
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_N)))
        assertEquals(1, nextCount)
    }

    @Test
    fun prefixThenPSwitchesToPreviousTui() {
        val dispatcher = newDispatcher()
        armPrefix(dispatcher)
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_P)))
        assertEquals(1, previousCount)
    }

    @Test
    fun prefixCommandUsesProvidedActionMap() {
        val dispatcher = TerminalKeyInterceptor(
            terminalComponent = terminal,
            escapeModifierMask = KeyEvent.CTRL_DOWN_MASK,
            escapeKeyCode = KeyEvent.VK_SPACE,
            prefixCommandActions = mapOf(KeyEvent.VK_F to { switchCount++ }),
        )

        armPrefix(dispatcher)
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_F)))

        assertEquals(1, switchCount)
    }

    @Test
    fun escapeWhileFocusedIsForwardedToTheTui() {
        val dispatcher = newDispatcher()
        val escapeChar = KeyEvent.VK_ESCAPE.toChar()
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_ESCAPE, keyChar = escapeChar)))
        assertEquals(listOf(Triple(KeyEvent.VK_ESCAPE, 0, escapeChar)), sentKeys)
    }

    @Test
    fun duplicateEscapePressIsSentOnce() {
        val dispatcher = newDispatcher()
        val event = keyPress(KeyEvent.VK_ESCAPE, keyChar = KeyEvent.VK_ESCAPE.toChar())
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertEquals(1, sentKeys.size)
    }

    @Test
    fun escapeWhileNotFocusedPassesThrough() {
        val dispatcher = newDispatcher()
        assertFalse(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_ESCAPE, source = elsewhere)))
        assertTrue(sentKeys.isEmpty())
    }

    @Test
    fun modifiedEscapeIsLeftToTheIde() {
        val dispatcher = newDispatcher()
        assertFalse(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_ESCAPE, KeyEvent.SHIFT_DOWN_MASK)))
        assertTrue(sentKeys.isEmpty())
    }

    @Test
    fun escapeIsNotConsumedWhenTheSessionCannotSend() {
        val dispatcher = newDispatcher(canSendKeys = false)
        assertFalse(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_ESCAPE)))
        assertTrue(sentKeys.isEmpty())
    }

    @Test
    fun escapeTrailingTypedCharIsSwallowed() {
        val dispatcher = newDispatcher()
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_ESCAPE)))
        assertTrue(dispatcher.dispatchKeyEvent(keyTyped('\u001B')))
    }

    @Test
    fun armedPrefixWinsOverEscape() {
        val dispatcher = newDispatcher()
        armPrefix(dispatcher)
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_ESCAPE)))
        assertTrue(sentKeys.isEmpty())
    }

    @Test
    fun nextTabShortcutWhileFocusedSwitchesTuiTabs() {
        val dispatcher = newDispatcher()
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK)))
        assertEquals(1, nextTabCount)
        assertEquals(0, previousTabCount)
    }

    @Test
    fun previousTabShortcutWhileFocusedSwitchesTuiTabs() {
        val dispatcher = newDispatcher()
        assertTrue(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_LEFT, KeyEvent.ALT_DOWN_MASK)))
        assertEquals(1, previousTabCount)
        assertEquals(0, nextTabCount)
    }

    @Test
    fun tabShortcutWhileNotFocusedPassesThrough() {
        val dispatcher = newDispatcher()
        val event = keyPress(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK, source = elsewhere)
        assertFalse(dispatcher.dispatchKeyEvent(event))
        assertEquals(0, nextTabCount)
    }

    @Test
    fun unboundKeyIsNotMistakenForATabShortcut() {
        val dispatcher = newDispatcher()
        assertFalse(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_RIGHT)))
        assertEquals(0, nextTabCount)
    }

    @Test
    fun twoKeystrokeTabShortcutIsIgnored() {
        val dispatcher = newDispatcher(
            nextTabShortcuts = listOf(
                KeyboardShortcut(KeyStroke.getKeyStroke("alt RIGHT"), KeyStroke.getKeyStroke("alt UP")),
            ),
        )
        assertFalse(dispatcher.dispatchKeyEvent(keyPress(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK)))
        assertEquals(0, nextTabCount)
    }

    @Test
    fun duplicateTabShortcutPressSwitchesOnce() {
        val dispatcher = newDispatcher()
        val event = keyPress(KeyEvent.VK_RIGHT, KeyEvent.ALT_DOWN_MASK)
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertTrue(dispatcher.dispatchKeyEvent(event))
        assertEquals(1, nextTabCount)
    }
}
