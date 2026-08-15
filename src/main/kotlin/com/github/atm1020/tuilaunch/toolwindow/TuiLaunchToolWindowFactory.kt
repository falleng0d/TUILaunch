package com.github.atm1020.tuilaunch.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowEx

class TuiLaunchToolWindowFactory : ToolWindowFactory {
    override fun init(toolWindow: ToolWindow) {
        (toolWindow as? ToolWindowEx)?.setTabActions(
            ActionManager.getInstance().getAction("TUILaunch.NewSession")
        )
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) = Unit
}
