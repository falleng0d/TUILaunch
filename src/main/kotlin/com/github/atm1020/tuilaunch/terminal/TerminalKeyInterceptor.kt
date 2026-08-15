package com.github.atm1020.tuilaunch.terminal

import com.intellij.openapi.actionSystem.KeyboardShortcut
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class TerminalKeyInterceptor(
    private val terminalComponent: Component,
    private val escapeModifierMask: Int = 0,
    private val escapeKeyCode: Int? = null,
    private val prefixCommandActions: Map<Int, () -> Unit> = emptyMap(),
    private val canSendKeys: Boolean = false,
    private val sendKey: (keyCode: Int, modifiers: Int, keyChar: Char) -> Unit = { _, _, _ -> },
    private val nextTabShortcuts: () -> List<KeyboardShortcut> = { emptyList() },
    private val previousTabShortcuts: () -> List<KeyboardShortcut> = { emptyList() },
    private val onNextTab: () -> Unit = {},
    private val onPreviousTab: () -> Unit = {},
) : KeyEventDispatcher {

    private var lastFiredWhen = -1L
    private var lastFiredKeyCode = KeyEvent.VK_UNDEFINED

    private var consumeNextTypedEvent = false

    private var prefixArmed = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.id == KeyEvent.KEY_TYPED) {
            if (!consumeNextTypedEvent) return false
            e.consume()
            return true
        }
        if (e.id != KeyEvent.KEY_PRESSED) return false

        if (isRepeatDeliveryOfHandledPress(e)) {
            e.consume()
            return true
        }

        if (!isFocusInsideTerminal(e)) {
            prefixArmed = false
            consumeNextTypedEvent = false
            return false
        }

        if (prefixArmed) {
            prefixArmed = false
            val action = prefixCommandActions[e.keyCode]
            if (action == null) {
                consumeNextTypedEvent = false
                e.consume()
                return true
            }

            action()

            return swallowHandledPress(e)
        }

        if (isPrefixCombo(e)) {
            prefixArmed = true
            return swallowHandledPress(e)
        }

        val isBareEscape = e.keyCode == KeyEvent.VK_ESCAPE && e.modifiersEx == 0
        if (canSendKeys && isBareEscape) {
            sendKey(KeyEvent.VK_ESCAPE, e.modifiersEx, ESCAPE_CHAR)
            return swallowHandledPress(e)
        }

        val tabAction = tabNavigationActionFor(e)
        if (tabAction != null) {
            tabAction()
            return swallowHandledPress(e)
        }

        consumeNextTypedEvent = false
        return false
    }

    private fun isRepeatDeliveryOfHandledPress(e: KeyEvent): Boolean =
        e.`when` == lastFiredWhen && e.keyCode == lastFiredKeyCode

    private fun isFocusInsideTerminal(e: KeyEvent): Boolean {
        val source = e.component ?: return false
        return SwingUtilities.isDescendingFrom(source, terminalComponent)
    }

    private fun isPrefixCombo(e: KeyEvent): Boolean =
        escapeKeyCode != null &&
            prefixCommandActions.isNotEmpty() &&
            e.keyCode == escapeKeyCode &&
            e.modifiersEx == escapeModifierMask

    private fun tabNavigationActionFor(e: KeyEvent): (() -> Unit)? {
        val keyStroke = KeyStroke.getKeyStrokeForEvent(e)
        return when {
            nextTabShortcuts().matchesSingleStroke(keyStroke) -> onNextTab
            previousTabShortcuts().matchesSingleStroke(keyStroke) -> onPreviousTab
            else -> null
        }
    }

    private fun List<KeyboardShortcut>.matchesSingleStroke(keyStroke: KeyStroke): Boolean =
        any { it.secondKeyStroke == null && it.firstKeyStroke == keyStroke }

    private fun swallowHandledPress(e: KeyEvent): Boolean {
        e.consume()
        lastFiredWhen = e.`when`
        lastFiredKeyCode = e.keyCode
        consumeNextTypedEvent = true
        return true
    }

    private companion object {
        const val ESCAPE_CHAR = '\u001B'
    }
}
