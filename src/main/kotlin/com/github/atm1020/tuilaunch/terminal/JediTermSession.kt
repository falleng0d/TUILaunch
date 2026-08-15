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
        // The classic Gen-1 widget is the only one that exposes a JediTerm starter, i.e. a write path
        // to the child process. `startShellTerminalWidget` always returns one today; if that ever
        // changes we simply lose the ability to forward keys rather than swallowing them.
        val jediTermWidget = JBTerminalWidget.asJediTermWidget(widget)
        val session = TerminalSession(
            component = widget.component,
            requestFocus = { widget.requestFocus() },
            registerTerminationCallback = { callback ->
                widget.addTerminationCallback({ callback() }, parent)
            },
            canSendKeys = jediTermWidget != null,
            // Encode through JediTerm's own encoder rather than hard-coding bytes, so a forwarded key
            // is byte-identical to one the terminal handled itself. `userInput = true` also scrolls to
            // the cursor and clears the selection, matching real typing. `TerminalKeyEncoder` has no
            // entry for every key code (notably VK_ESCAPE), in which case `getCode` returns null; mirror
            // `TerminalPanel.processTerminalKeyPressed` / `processCharacter`'s fallback of sending the
            // raw key char instead, so those keys still reach the child process.
            sendKey = { keyCode, modifiers, keyChar ->
                jediTermWidget?.terminalStarter?.let { starter ->
                    encodeAndSend(
                        getCode = starter::getCode,
                        sendBytes = { bytes -> starter.sendBytes(bytes, true) },
                        sendString = { string -> starter.sendString(string, true) },
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

    /**
     * Picks the "run this command, then exit" argument for the given shell. The flag is shell-specific:
     * cmd.exe uses `/c`, PowerShell uses `-Command`, and POSIX shells (bash/zsh/sh/fish, including Git
     * Bash and WSL bash on Windows) use `-c`. All three cause the shell to exit once the command
     * finishes, which fires the termination callback that closes the tab.
     */
    private fun runCommandArgs(shellExe: String, command: String): List<String> =
        when (File(shellExe).name.removeSuffix(".exe").lowercase()) {
            "cmd" -> listOf("/c", command)
            "powershell", "pwsh" -> listOf("-Command", command)
            else -> listOf("-c", command)
        }

    /**
     * Claims the keys a focused TUI app needs: Escape (which the platform would otherwise turn into
     * "focus the editor"), the IDE's tab-navigation shortcuts, and — when enabled — the configured
     * prefix combo (e.g. Ctrl+Space). Registration is unconditional because Escape capture has no
     * toggle; only the prefix branch is optional. The dispatcher is unregistered when [parent] is
     * disposed (i.e. when the tab closes).
     */
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
            nextTabShortcuts = { keyboardShortcutsOf("NextTab") },
            previousTabShortcuts = { keyboardShortcutsOf("PreviousTab") },
            onNextTab = onNextTab,
            onPreviousTab = onPreviousTab,
        )
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addKeyEventDispatcher(dispatcher)
        Disposer.register(parent) { focusManager.removeKeyEventDispatcher(dispatcher) }
    }

    /**
     * The keyboard shortcuts currently bound to an IDE action. Resolved per event rather than cached
     * so a keymap switch or rebind takes effect immediately, and never hard-coded — `NextTab` alone
     * differs across the default, macOS, and system-shortcut keymaps.
     */
    private fun keyboardShortcutsOf(actionId: String): List<KeyboardShortcut> {
        val action = ActionManager.getInstance().getAction(actionId) ?: return emptyList()
        return action.shortcutSet.shortcuts.filterIsInstance<KeyboardShortcut>()
    }

    /** Translates the persisted modifier name into an AWT extended-modifier mask. */
    private fun modifierMaskOf(modifier: String): Int = when (modifier) {
        "ALT" -> KeyEvent.ALT_DOWN_MASK
        else -> KeyEvent.CTRL_DOWN_MASK
    }
}

/**
 * Encodes a forwarded key exactly the way JediTerm's own `TerminalPanel` would: try the terminal's key
 * encoder first ([getCode]), and only when it has no byte mapping for this key code — as is the case
 * for `VK_ESCAPE`, which `TerminalKeyEncoder` does not list — fall back to sending the raw [keyChar],
 * provided it is defined. This mirrors `TerminalPanel.processTerminalKeyPressed` falling through to
 * `processCharacter` when `getCodeForKey` returns null, so a forwarded key is byte-identical to one the
 * terminal handled itself. Extracted as a standalone function (rather than left inline in the `sendKey`
 * lambda) so the fallback path is unit-testable without a real JediTerm widget.
 */
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
