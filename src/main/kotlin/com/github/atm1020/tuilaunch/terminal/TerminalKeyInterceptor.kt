package com.github.atm1020.tuilaunch.terminal

import com.intellij.openapi.actionSystem.KeyboardShortcut
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

internal const val NEXT_TAB_ACTION_ID = "NextTab"
internal const val PREVIOUS_TAB_ACTION_ID = "PreviousTab"

class TerminalKeyInterceptor(
    private val terminalComponent: Component,
    private val escapeModifierMask: Int = 0,
    private val escapeKeyCode: Int? = null,
    private val prefixCommandActions: Map<Int, () -> Unit> = emptyMap(),
    private val canSendKeys: Boolean = false,
    private val sendKey: (keyCode: Int, modifiers: Int, keyChar: Char) -> Unit = { _, _, _ -> },
    private val nextTabShortcuts: () -> List<KeyboardShortcut> = { emptyList() },
    private val previousTabShortcuts: () -> List<KeyboardShortcut> = { emptyList() },
    private val onNextTab: () -> Boolean = { false },
    private val onPreviousTab: () -> Boolean = { false },
    private val promptBoxSideShortcuts: () -> List<KeyboardShortcut> = { emptyList() },
    private val onTogglePromptBoxSide: () -> Boolean = { false },
    private val ideActionShortcuts: () -> Map<String, List<KeyboardShortcut>> = { emptyMap() },
    private val runIdeAction: (actionId: String, event: KeyEvent) -> Unit = { _, _ -> },
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

        if (e.isConsumed || !isFocusInsideTerminal(e)) return leaveThePressToTheIde()

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

        val ideActionId = ideActionFor(e)
        if (ideActionId != null) {
            runIdeAction(ideActionId, e)
            return swallowHandledPress(e)
        }

        val tabNavigation = tabNavigationFor(e)
        if (tabNavigation != null) {
            val (ideActionId, switchTuiTab) = tabNavigation
            if (!switchTuiTab()) runIdeAction(ideActionId, e)
            return swallowHandledPress(e)
        }

        val isPromptBoxSideStroke = promptBoxSideShortcuts().matchesSingleStroke(KeyStroke.getKeyStrokeForEvent(e))
        if (isPromptBoxSideStroke && onTogglePromptBoxSide()) return swallowHandledPress(e)

        return leaveThePressToTheIde()
    }

    fun spendKeyPress(event: KeyEvent) {
        prefixArmed = false
        lastFiredWhen = event.`when`
        lastFiredKeyCode = event.keyCode
        consumeNextTypedEvent = true
    }

    private fun leaveThePressToTheIde(): Boolean {
        prefixArmed = false
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

    private fun tabNavigationFor(e: KeyEvent): Pair<String, () -> Boolean>? {
        val keyStroke = KeyStroke.getKeyStrokeForEvent(e)
        return when {
            nextTabShortcuts().matchesSingleStroke(keyStroke) -> NEXT_TAB_ACTION_ID to onNextTab
            previousTabShortcuts().matchesSingleStroke(keyStroke) -> PREVIOUS_TAB_ACTION_ID to onPreviousTab
            else -> null
        }
    }

    private fun ideActionFor(e: KeyEvent): String? {
        val keyStroke = KeyStroke.getKeyStrokeForEvent(e)
        return ideActionShortcuts().entries.firstOrNull { (_, shortcuts) -> shortcuts.matchesSingleStroke(keyStroke) }?.key
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
