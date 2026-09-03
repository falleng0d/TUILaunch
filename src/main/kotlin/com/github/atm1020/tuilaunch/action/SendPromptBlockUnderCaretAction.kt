package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.prompt.aPromptBlockHoldsTheCaret
import com.github.atm1020.tuilaunch.prompt.caretLineOf
import com.github.atm1020.tuilaunch.prompt.promptBlockTextUnderTheCaret
import com.github.atm1020.tuilaunch.prompt.sendPromptBlock
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction

private const val NO_BLOCK_UNDER_THE_CARET_MESSAGE = "No prompt block under the cursor"

class SendPromptBlockUnderCaretAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = editorOf(e)
        e.presentation.isEnabled = editor != null && aPromptBlockHoldsTheCaret(editor)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = editorOf(e) ?: return
        val project = editor.project ?: e.project ?: return
        val blockText = promptBlockTextUnderTheCaret(editor)
        if (blockText == null) {
            HintManager.getInstance().showErrorHint(editor, NO_BLOCK_UNDER_THE_CARET_MESSAGE)
            return
        }
        sendPromptBlock(project, editor, caretLineOf(editor), blockText)
    }

    private fun editorOf(e: AnActionEvent): Editor? = e.getData(CommonDataKeys.EDITOR)
}
