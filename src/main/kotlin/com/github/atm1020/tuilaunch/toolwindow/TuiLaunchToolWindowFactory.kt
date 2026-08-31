package com.github.atm1020.tuilaunch.toolwindow

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi

class TuiLaunchToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun init(toolWindow: ToolWindow) {
        toolWindow.component.putClientProperty(ToolWindowContentUi.HIDE_ID_LABEL, "true")
        (toolWindow as? ToolWindowEx)?.setTabActions(
            ActionManager.getInstance().getAction("TUILaunch.NewSession")
        )
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setTitleActions(
            listOfNotNull(ActionManager.getInstance().getAction("TUILaunch.OpenPromptFile"))
        )
        invokeLater {
            if (!project.isDisposed) project.service<TuiAppLaunchService>().restoreSavedTabs()
        }
    }
}
