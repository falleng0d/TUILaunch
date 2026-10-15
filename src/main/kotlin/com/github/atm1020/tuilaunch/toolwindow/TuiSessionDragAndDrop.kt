package com.github.atm1020.tuilaunch.toolwindow

import com.github.atm1020.tuilaunch.editor.TuiSessionFile
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.openapi.wm.impl.content.ToolWindowInEditorSupport
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.ui.content.Content
import com.intellij.ui.docking.DockContainer
import com.intellij.ui.docking.DockManager
import com.intellij.ui.docking.DockableContent
import java.awt.event.HierarchyEvent
import javax.swing.JComponent

internal fun installSessionDragAndDrop(project: Project, toolWindow: ToolWindow) {
    ToolWindowContentUi.setToolWindowInEditorSupport(toolWindow, TuiSessionInEditorSupport(project))
    registerTheDockContainerWhileShowing(project, toolWindow)
}

private fun registerTheDockContainerWhileShowing(project: Project, toolWindow: ToolWindow) {
    val component = toolWindow.component
    val container = TuiSessionDockContainer(project, component)
    var registration: Disposable? = null
    fun followTheShowingState() {
        val current = registration
        if (component.isShowing && current == null) {
            val parent = Disposer.newDisposable(toolWindow.disposable, "TUILaunch session dock container")
            DockManager.getInstance(project).register(container, parent)
            registration = parent
        } else if (!component.isShowing && current != null) {
            Disposer.dispose(current)
            registration = null
        }
    }
    component.addHierarchyListener { event ->
        val showingChanged = event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
        if (showingChanged) followTheShowingState()
    }
    followTheShowingState()
}

private class TuiSessionInEditorSupport(private val project: Project) : ToolWindowInEditorSupport {

    override fun canOpenInEditor(project: Project, content: Content): Boolean {
        val service = project.service<TuiAppLaunchService>()
        return service.handleFor(content)?.let(service::canMoveToEditor) == true
    }

    override fun openInEditor(content: Content, targetWindow: EditorWindow) {
        val service = project.service<TuiAppLaunchService>()
        val handle = service.handleFor(content) ?: return
        service.moveToEditor(handle, targetWindow)
    }
}

private class TuiSessionDockContainer(
    private val project: Project,
    private val component: JComponent,
) : DockContainer {

    override fun getAcceptArea(): RelativeRectangle = RelativeRectangle(component)

    override fun getContentResponse(content: DockableContent<*>, point: RelativePoint?): DockContainer.ContentResponse =
        if (content.key is TuiSessionFile) DockContainer.ContentResponse.ACCEPT_MOVE else DockContainer.ContentResponse.DENY

    override fun getContainerComponent(): JComponent = component

    override fun add(content: DockableContent<*>, dropTarget: RelativePoint?) {
        val file = content.key as? TuiSessionFile ?: return
        project.service<TuiAppLaunchService>().moveToToolWindow(file)
    }

    override fun isEmpty(): Boolean = false

    override fun isDisposeWhenEmpty(): Boolean = false
}
