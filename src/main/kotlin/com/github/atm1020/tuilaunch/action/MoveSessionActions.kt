package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.editor.TuiSessionFile
import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase
import com.intellij.ui.content.Content
import java.awt.Component

class MoveSessionToEditorAction : ToolWindowContextMenuActionBase() {

    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val service = e.project?.service<TuiAppLaunchService>()
        val handle = content?.let { service?.handleFor(it) }
        e.presentation.isEnabledAndVisible =
            toolWindow.id == TUI_TOOL_WINDOW_ID && handle != null && service?.canMoveToEditor(handle) == true
    }

    override fun actionPerformed(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val service = e.project?.service<TuiAppLaunchService>() ?: return
        val handle = content?.let(service::handleFor) ?: return
        service.moveToEditor(handle)
    }
}

class MoveSessionToToolWindowAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && sessionFileOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = sessionFileOf(e) ?: return
        e.project?.service<TuiAppLaunchService>()?.moveToToolWindow(file)
    }
}

class RenameEditorSessionAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && sessionFileOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val file = sessionFileOf(e) ?: return
        val service = e.project?.service<TuiAppLaunchService>() ?: return
        showSessionRenameBalloon(
            anchor = renameAnchor(e, file),
            currentName = service.sessionTitleOf(file) ?: file.name,
            parentDisposable = service,
        ) { newName -> service.renameEditorSession(file, newName) }
    }

    private fun renameAnchor(e: AnActionEvent, file: TuiSessionFile): Component {
        e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)?.takeIf { it.isShowing }?.let { return it }
        e.inputEvent?.component?.takeIf { it.isShowing }?.let { return it }
        return file.component
    }
}

private fun sessionFileOf(e: AnActionEvent): TuiSessionFile? =
    (e.getData(CommonDataKeys.VIRTUAL_FILE) ?: e.getData(PlatformCoreDataKeys.FILE_EDITOR)?.file) as? TuiSessionFile
