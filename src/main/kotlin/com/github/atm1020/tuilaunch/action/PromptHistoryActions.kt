package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_DATA_KEY
import com.github.atm1020.tuilaunch.prompt.PromptHistoryDirection
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CompositeShortcutSet
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

internal const val PROMPT_HISTORY_PREVIOUS_ACTION_ID = "TUILauncher.PromptHistoryPrevious"
internal const val PROMPT_HISTORY_NEXT_ACTION_ID = "TUILauncher.PromptHistoryNext"

private const val EDITOR_UP_ACTION_ID = "EditorUp"
private const val EDITOR_DOWN_ACTION_ID = "EditorDown"

internal fun promptHistoryActionId(direction: PromptHistoryDirection): String = when (direction) {
    PromptHistoryDirection.PREVIOUS -> PROMPT_HISTORY_PREVIOUS_ACTION_ID
    PromptHistoryDirection.NEXT -> PROMPT_HISTORY_NEXT_ACTION_ID
}

internal fun promptHistoryShortcutSet(direction: PromptHistoryDirection): ShortcutSet {
    val caretMovement = caretMovementShortcutSet(direction)
    val boundInTheKeymap = ActionManager.getInstance().getAction(promptHistoryActionId(direction))
        ?: return caretMovement
    return CompositeShortcutSet(boundInTheKeymap.shortcutSet, caretMovement)
}

private fun caretMovementShortcutSet(direction: PromptHistoryDirection): ShortcutSet {
    val caretMovementActionId = when (direction) {
        PromptHistoryDirection.PREVIOUS -> EDITOR_UP_ACTION_ID
        PromptHistoryDirection.NEXT -> EDITOR_DOWN_ACTION_ID
    }
    return ActionManager.getInstance().getActionOrStub(caretMovementActionId)?.shortcutSet
        ?: CustomShortcutSet.EMPTY
}

private fun caretMovementKeystrokes(direction: PromptHistoryDirection): List<KeyStroke> =
    caretMovementShortcutSet(direction).shortcuts
        .filterIsInstance<KeyboardShortcut>()
        .filter { it.secondKeyStroke == null }
        .map { it.firstKeyStroke }

abstract class PromptHistoryAction internal constructor(
    private val direction: PromptHistoryDirection,
) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val box = e.getData(PROMPT_BOX_DATA_KEY)
        e.presentation.isEnabled = box != null &&
            (!triggeredByACaretMovementKey(e) || box.caretAtHistoryEdge(direction))
    }

    override fun actionPerformed(e: AnActionEvent) {
        val box = e.getData(PROMPT_BOX_DATA_KEY) ?: return
        when (direction) {
            PromptHistoryDirection.PREVIOUS -> box.historyPrevious()
            PromptHistoryDirection.NEXT -> box.historyNext()
        }
    }

    private fun triggeredByACaretMovementKey(e: AnActionEvent): Boolean {
        val keyEvent = e.inputEvent as? KeyEvent ?: return false
        return KeyStroke.getKeyStrokeForEvent(keyEvent) in caretMovementKeystrokes(direction)
    }
}

class PromptHistoryPreviousAction : PromptHistoryAction(PromptHistoryDirection.PREVIOUS)

class PromptHistoryNextAction : PromptHistoryAction(PromptHistoryDirection.NEXT)
