package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareToggleAction

class TogglePromptBoxAction : DumbAwareToggleAction(
    "Toggle Prompt Box",
    "Show or hide the prompt box under the active TUI session",
    AllIcons.FileTypes.Text,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = promptBoxVisibility(e) != null
    }

    override fun isSelected(e: AnActionEvent): Boolean = promptBoxVisibility(e) == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        e.project?.service<TuiAppLaunchService>()?.setPromptBoxVisible(state)
    }

    private fun promptBoxVisibility(e: AnActionEvent): Boolean? =
        e.project?.service<TuiAppLaunchService>()?.isPromptBoxVisible()
}
