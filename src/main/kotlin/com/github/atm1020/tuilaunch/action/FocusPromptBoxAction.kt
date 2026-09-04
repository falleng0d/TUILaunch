package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

internal const val FOCUS_PROMPT_BOX_ACTION_ID = "TUILauncher.FocusPromptBox"

class FocusPromptBoxAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val service = e.project?.service<TuiAppLaunchService>()
        e.presentation.isEnabled = service?.isPromptBoxVisible() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.focusPromptBox()
    }
}
