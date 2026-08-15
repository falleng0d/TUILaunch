package com.github.atm1020.tuilaunch.terminal

import com.intellij.openapi.Disposable
import javax.swing.JComponent

/** One running TUI app: its UI component, focus control, key input, and exit signal. */
class TerminalSession(
    val component: JComponent,
    private val requestFocus: () -> Unit,
    private val registerTerminationCallback: ((() -> Unit) -> Unit),
    /**
     * Whether [sendKey] actually reaches the child process. False when the underlying widget could not
     * be unwrapped to a JediTerm one, in which case callers must leave the key to the IDE instead of
     * consuming it into a no-op.
     */
    val canSendKeys: Boolean = false,
    private val sendKey: (keyCode: Int, modifiers: Int, keyChar: Char) -> Unit = { _, _, _ -> },
) {
    fun requestFocus() = requestFocus.invoke()

    /**
     * Types a key into the running app, encoded exactly as the terminal itself would have encoded it.
     * Used to forward keystrokes the IDE would otherwise steal. [keyChar] backs the fallback for keys
     * the terminal's own encoder has no byte mapping for (e.g. Escape), mirroring how JediTerm itself
     * falls back to the raw character. No-op when [canSendKeys] is false.
     */
    fun sendKey(keyCode: Int, modifiers: Int, keyChar: Char) = sendKey.invoke(keyCode, modifiers, keyChar)

    /** Registers a callback fired (on the EDT) when the underlying process exits. */
    fun onTerminated(callback: () -> Unit) = registerTerminationCallback(callback)
}

interface TerminalSessionFactory {
    fun create(parent: Disposable, command: String): TerminalSession

    fun createAsync(
        parent: Disposable,
        command: String,
        onCreated: (TerminalSession) -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        try {
            onCreated(create(parent, command))
        } catch (throwable: Throwable) {
            onFailed(throwable)
        }
    }
}
