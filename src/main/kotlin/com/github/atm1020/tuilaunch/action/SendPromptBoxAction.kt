package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_DATA_KEY
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

class SendPromptBoxAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(PROMPT_BOX_DATA_KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(PROMPT_BOX_DATA_KEY)?.send()
    }
}
