package com.github.atm1020.tuilaunch.terminal

import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.JBTerminalWidget
import org.jetbrains.plugins.terminal.LocalTerminalDirectRunner
import org.jetbrains.plugins.terminal.ShellStartupOptions
import org.jetbrains.plugins.terminal.TerminalProjectOptionsProvider
import org.jetbrains.plugins.terminal.runner.LocalTerminalStartCommandBuilder
import java.awt.KeyboardFocusManager
import java.io.File
import java.awt.event.KeyEvent

private const val SEND_AS_USER_INPUT = true

class JediTermSessionFactory(
    private val project: Project,
    private val prefixCommandActions: () -> Map<Int, () -> Unit> = { emptyMap() },
    private val onNextTab: () -> Unit = {},
    private val onPreviousTab: () -> Unit = {},
) : TerminalSessionFactory {
    override fun create(parent: Disposable, command: String): TerminalSession {
        val shellPath = TerminalProjectOptionsProvider.getInstance(project).shellPath
        return create(parent, command, shellPath)
    }

    override fun createAsync(
        parent: Disposable,
        command: String,
        onCreated: (TerminalSession) -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val shellPath = TerminalProjectOptionsProvider.getInstance(project).shellPath
                invokeLater {
                    if ((parent as? CheckedDisposable)?.isDisposed != true) {
                        onCreated(create(parent, command, shellPath))
                    }
                }
            } catch (throwable: Throwable) {
                invokeLater { onFailed(throwable) }
            }
        }
    }

    private fun create(parent: Disposable, command: String, shellPath: String): TerminalSession {
        val runner = LocalTerminalDirectRunner.createTerminalRunner(project)
        val workingDir = project.basePath ?: System.getProperty("user.home")
        val baseShellCommand = LocalTerminalStartCommandBuilder.convertShellPathToCommand(shellPath)
        val options = ShellStartupOptions.Builder()
            .shellCommand(baseShellCommand + runCommandArgs(baseShellCommand.first(), command))
            .workingDirectory(workingDir)
            .build()
        val widget = runner.startShellTerminalWidget(parent, options, false)
        val jediTermWidget = JBTerminalWidget.asJediTermWidget(widget)
        val session = TerminalSession(
            component = widget.component,
            requestFocus = { widget.requestFocus() },
            registerTerminationCallback = { callback ->
                widget.addTerminationCallback({ callback() }, parent)
            },
            canSendKeys = jediTermWidget != null,
            sendKey = { keyCode, modifiers, keyChar ->
                jediTermWidget?.terminalStarter?.let { starter ->
                    encodeAndSend(
                        getCode = starter::getCode,
                        sendBytes = { bytes -> starter.sendBytes(bytes, SEND_AS_USER_INPUT) },
                        sendString = { string -> starter.sendString(string, SEND_AS_USER_INPUT) },
                        keyCode = keyCode,
                        modifiers = modifiers,
                        keyChar = keyChar,
                    )
                }
            },
        )
        installKeyInterceptor(session, parent)
        return session
    }

    private fun runCommandArgs(shellExe: String, command: String): List<String> =
        when (File(shellExe).name.removeSuffix(".exe").lowercase()) {
            "cmd" -> listOf("/c", command)
            "powershell", "pwsh" -> listOf("-Command", command)
            else -> listOf("-c", command)
        }

    private fun installKeyInterceptor(session: TerminalSession, parent: Disposable) {
        val state = TuiLauncherSettings.getInstance().state
        val prefixEnabled = state.tmuxKeybindingsEnabled && state.escapeKeyCode != null
        val actions = if (prefixEnabled) prefixCommandActions() else emptyMap()

        val dispatcher = TerminalKeyInterceptor(
            terminalComponent = session.component,
            escapeModifierMask = modifierMaskOf(state.escapeModifier),
            escapeKeyCode = if (prefixEnabled) state.escapeKeyCode else null,
            prefixCommandActions = actions,
            canSendKeys = session.canSendKeys,
            sendKey = { keyCode, modifiers, keyChar -> session.sendKey(keyCode, modifiers, keyChar) },
            nextTabShortcuts = { currentKeyboardShortcutsOf("NextTab") },
            previousTabShortcuts = { currentKeyboardShortcutsOf("PreviousTab") },
            onNextTab = onNextTab,
            onPreviousTab = onPreviousTab,
        )
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addKeyEventDispatcher(dispatcher)
        Disposer.register(parent) { focusManager.removeKeyEventDispatcher(dispatcher) }
    }

    private fun currentKeyboardShortcutsOf(actionId: String): List<KeyboardShortcut> {
        val action = ActionManager.getInstance().getAction(actionId) ?: return emptyList()
        return action.shortcutSet.shortcuts.filterIsInstance<KeyboardShortcut>()
    }

    private fun modifierMaskOf(modifier: String): Int = when (modifier) {
        "ALT" -> KeyEvent.ALT_DOWN_MASK
        else -> KeyEvent.CTRL_DOWN_MASK
    }
}

internal fun encodeAndSend(
    getCode: (Int, Int) -> ByteArray?,
    sendBytes: (ByteArray) -> Unit,
    sendString: (String) -> Unit,
    keyCode: Int,
    modifiers: Int,
    keyChar: Char,
) {
    val code = getCode(keyCode, modifiers)
    if (code != null) {
        sendBytes(code)
    } else if (keyChar != KeyEvent.CHAR_UNDEFINED) {
        sendString(keyChar.toString())
    }
}
