package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabActionBase
import com.intellij.ui.content.Content

class TogglePromptBoxInEditorTabAction : ToolWindowEditorTabActionBase() {

    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content) {
        val visible = if (toolWindow.id == TUI_TOOL_WINDOW_ID) promptBoxVisibilityOf(e.project, content) else null
        e.presentation.isEnabledAndVisible = visible != null
        Toggleable.setSelected(e.presentation, visible == true)
    }

    override fun actionPerformed(e: AnActionEvent, content: Content) {
        val service = e.project?.service<TuiAppLaunchService>() ?: return
        val host = service.host ?: return
        service.togglePromptBoxOf(host.handleFor(content))
    }

    private fun promptBoxVisibilityOf(project: Project?, content: Content): Boolean? {
        val service = project?.service<TuiAppLaunchService>() ?: return null
        val host = service.host ?: return null
        return service.promptBoxVisibilityOf(host.handleFor(content))
    }
}
