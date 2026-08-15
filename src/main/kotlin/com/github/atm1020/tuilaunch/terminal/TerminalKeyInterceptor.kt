package com.github.atm1020.tuilaunch.terminal

import com.intellij.openapi.actionSystem.KeyboardShortcut
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Claims the keys a focused TUI app needs before the IDE — or the bundled terminal — can act on them.
 *
 * Registered globally on the `KeyboardFocusManager`, this sees key events before the terminal
 * component's own key handling, so it can swallow keys — the TUI never receives them — and run plugin
 * actions instead, or forward a key the platform would otherwise have stolen. Three responsibilities:
 *
 * 1. **Prefix combo** (optional): a tmux-style modifier combo (e.g. Ctrl+Space) arms a one-shot
 *    command key. Disabled when [escapeKeyCode] is `null` or [prefixCommandActions] is empty.
 * 2. **Escape**: the platform's `TerminalEscapeKeyListener` moves focus to the editor on bare Escape
 *    in any tool window that is not the bundled "Terminal" one, so ours always loses it. We intercept
 *    first and forward the key through JediTerm's own encoder ([sendKey]), which is byte-identical to
 *    what the terminal would have sent on its own. Modified Escape (notably `shift ESCAPE` =
 *    `HideActiveWindow`) is deliberately left to the IDE as a keyboard escape hatch.
 * 3. **Tab navigation**: the IDE's `NextTab` / `PreviousTab` keystrokes switch TUI tabs instead of
 *    editor tabs while a TUI is focused. Keystrokes are resolved by the caller at event time, so a
 *    custom keymap keeps working.
 *
 * Every branch only acts while focus is inside [terminalComponent]: the guard checks the *event's own
 * component*, which is the component that had focus when the key was generated. That is deliberately
 * component-level rather than `ToolWindow.isActive`, which is also true when the tool window's tab bar
 * or header has focus. Every other key, and these keys pressed elsewhere in the IDE, pass through
 * untouched.
 *
 * A key press produces a `KEY_PRESSED` *and* a separate `KEY_TYPED` carrying the character (the space
 * of Ctrl+Space). Consuming only the `KEY_PRESSED` would still let the `KEY_TYPED` reach the terminal,
 * typing a stray space. So after consuming a press, the trailing typed char is swallowed too, until
 * the next key press.
 *
 * @param escapeModifierMask the required AWT extended-modifier mask (e.g. [KeyEvent.CTRL_DOWN_MASK]).
 * @param escapeKeyCode the AWT `VK_` key code combined with the modifier (e.g. [KeyEvent.VK_SPACE]),
 *   or `null` when the prefix combo is disabled.
 * @param canSendKeys whether [sendKey] actually reaches the child process. False means we could not
 *   unwrap a JediTerm widget, so Escape is left alone rather than consumed into nothing.
 * @param nextTabShortcuts the shortcuts currently bound to the IDE's next-tab action, queried per
 *   event so a keymap switch or rebind takes effect immediately. Same for [previousTabShortcuts].
 */
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

    // IntelliJ delivers each KEY_PRESSED to the dispatcher twice; remember the handled press so the
    // duplicate is swallowed without invoking the action a second time. For Escape this is
    // load-bearing: firing twice would send 0x1B to the CLI twice.
    private var lastFiredWhen = -1L
    private var lastFiredKeyCode = KeyEvent.VK_UNDEFINED

    // True after consuming a key press: consume the trailing KEY_TYPED char so it never lands in the
    // terminal or editor. Cleared by the next unhandled key press.
    private var consumeNextTypedEvent = false

    private var prefixArmed = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.id == KeyEvent.KEY_TYPED) {
            if (!consumeNextTypedEvent) return false
            e.consume()
            return true
        }
        if (e.id != KeyEvent.KEY_PRESSED) return false

        // IntelliJ can deliver the same handled press twice; swallow the duplicate before it reaches
        // the terminal, without firing the command again.
        if (e.`when` == lastFiredWhen && e.keyCode == lastFiredKeyCode) {
            e.consume()
            return true
        }

        val source = e.component
        if (source == null || !SwingUtilities.isDescendingFrom(source, terminalComponent)) {
            prefixArmed = false
            consumeNextTypedEvent = false
            return false
        }

        // An armed prefix claims the next key outright, ahead of Escape and tab navigation.
        if (prefixArmed) {
            prefixArmed = false
            val action = prefixCommandActions[e.keyCode]
            if (action == null) {
                consumeNextTypedEvent = false
                e.consume()
                return true
            }

            action()

            return capture(e)
        }

        if (isPrefixCombo(e)) {
            prefixArmed = true
            return capture(e)
        }

        // Bare Escape only: any modifier means the IDE keeps the keystroke. Modifiers are 0 here, so
        // the legacy and extended masks coincide. The char is spelled out rather than read off the
        // event: this branch is Escape by definition, and a synthetic event with no keyChar would
        // otherwise be forwarded as nothing at all.
        if (canSendKeys && e.keyCode == KeyEvent.VK_ESCAPE && e.modifiersEx == 0) {
            sendKey(KeyEvent.VK_ESCAPE, e.modifiersEx, ESCAPE_CHAR)
            return capture(e)
        }

        val tabAction = tabNavigationActionFor(e)
        if (tabAction != null) {
            tabAction()
            return capture(e)
        }

        consumeNextTypedEvent = false // any other press cancels a pending typed-event consume
        return false
    }

    private fun isPrefixCombo(e: KeyEvent): Boolean =
        escapeKeyCode != null &&
            prefixCommandActions.isNotEmpty() &&
            e.keyCode == escapeKeyCode &&
            e.modifiersEx == escapeModifierMask

    /** The tab switch bound to this keystroke, or `null` when it is not a tab-navigation shortcut. */
    private fun tabNavigationActionFor(e: KeyEvent): (() -> Unit)? {
        val keyStroke = KeyStroke.getKeyStrokeForEvent(e)
        return when {
            nextTabShortcuts().matches(keyStroke) -> onNextTab
            previousTabShortcuts().matches(keyStroke) -> onPreviousTab
            else -> null
        }
    }

    /**
     * Whether any single-keystroke binding matches. Two-stroke shortcuts are skipped: a
     * `KeyEventDispatcher` sees one keystroke at a time and cannot arbitrate a chord, so claiming the
     * first stroke would break the chord rather than complete it.
     */
    private fun List<KeyboardShortcut>.matches(keyStroke: KeyStroke): Boolean =
        any { it.secondKeyStroke == null && it.firstKeyStroke == keyStroke }

    /** Swallows a handled press: consume it, remember it for de-duplication, eat its typed char. */
    private fun capture(e: KeyEvent): Boolean {
        e.consume()
        lastFiredWhen = e.`when`
        lastFiredKeyCode = e.keyCode
        consumeNextTypedEvent = true
        return true
    }

    private companion object {
        /** The char AWT carries on an Escape key press, and the byte a terminal expects: `ESC`. */
        const val ESCAPE_CHAR = '\u001B'
    }
}
