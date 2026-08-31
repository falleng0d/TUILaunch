package com.github.atm1020.tuilaunch.terminal

import com.intellij.openapi.Disposable
import javax.swing.JComponent

class TerminalSession(
    val component: JComponent,
    private val requestFocus: () -> Unit,
    private val registerTerminationCallback: ((() -> Unit) -> Unit),
    val canSendKeys: Boolean = false,
    private val sendKey: (keyCode: Int, modifiers: Int, keyChar: Char) -> Unit = { _, _, _ -> },
    private val sendText: (String) -> Boolean = { false },
) {
    fun requestFocus() = requestFocus.invoke()

    fun sendKey(keyCode: Int, modifiers: Int, keyChar: Char) = sendKey.invoke(keyCode, modifiers, keyChar)

    fun sendText(text: String): Boolean = sendText.invoke(text)

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
