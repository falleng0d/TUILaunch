package com.github.atm1020.tuilaunch.toolwindow

import com.github.atm1020.tuilaunch.editor.TuiSessionFile
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.AbstractPainter
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi
import com.intellij.openapi.wm.impl.content.ToolWindowInEditorSupport
import com.intellij.ui.ExperimentalUI
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.awt.RelativeRectangle
import com.intellij.ui.content.Content
import com.intellij.ui.docking.DockContainer
import com.intellij.ui.docking.DockManager
import com.intellij.ui.docking.DockableContent
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Image
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import javax.swing.SwingUtilities
import javax.swing.JComponent

internal fun installSessionDragAndDrop(project: Project, toolWindow: ToolWindow) {
    ToolWindowContentUi.setToolWindowInEditorSupport(toolWindow, TuiSessionInEditorSupport(project))
    registerTheDockContainerWhileShowing(project, toolWindow)
}

private fun registerTheDockContainerWhileShowing(project: Project, toolWindow: ToolWindow) {
    val component = toolWindow.component
    val container = TuiSessionDockContainer(project, component) { (toolWindow as? ToolWindowEx)?.decorator ?: component }
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
    private val wholeToolWindow: () -> JComponent,
) : DockContainer {

    private var dropHighlight: Disposable? = null

    override fun getAcceptArea(): RelativeRectangle = RelativeRectangle(wholeToolWindow())

    override fun getContentResponse(content: DockableContent<*>, point: RelativePoint?): DockContainer.ContentResponse =
        if (content.key is TuiSessionFile) DockContainer.ContentResponse.ACCEPT_MOVE else DockContainer.ContentResponse.DENY

    override fun getContainerComponent(): JComponent = component

    override fun startDropOver(content: DockableContent<*>, point: RelativePoint?): Image? {
        highlightTheDropArea()
        return null
    }

    override fun processDropOver(content: DockableContent<*>, point: RelativePoint?): Image? {
        highlightTheDropArea()
        return null
    }

    override fun resetDropOver(content: DockableContent<*>) = removeTheDropHighlight()

    override fun add(content: DockableContent<*>, dropTarget: RelativePoint?) {
        removeTheDropHighlight()
        val file = content.key as? TuiSessionFile ?: return
        project.service<TuiAppLaunchService>().moveToToolWindow(file)
    }

    override fun isEmpty(): Boolean = false

    override fun isDisposeWhenEmpty(): Boolean = false

    private fun highlightTheDropArea() {
        if (dropHighlight != null) return
        val area = wholeToolWindow()
        val glassPane = IdeGlassPaneUtil.find(area)
        val highlight = Disposer.newDisposable("TUILaunch session drop highlight")
        val painter = DropAreaPainter(area)
        glassPane.addPainter(area, painter, highlight)
        painter.setNeedsRepaint(true, true)
        Disposer.register(highlight) { (glassPane as? JComponent)?.repaint() }
        dropHighlight = highlight
    }

    private fun removeTheDropHighlight() {
        dropHighlight?.let(Disposer::dispose)
        dropHighlight = null
    }
}

private class DropAreaPainter(private val area: JComponent) : AbstractPainter() {

    override fun needsRepaint(): Boolean = true

    override fun executePaint(component: Component, g: Graphics2D) {
        val bounds = SwingUtilities.convertRectangle(area, Rectangle(0, 0, area.width, area.height), component)
        GraphicsUtil.setupAAPainting(g)
        g.color = dropAreaColor()
        g.fill(bounds)
    }
}

private fun dropAreaColor(): Color =
    if (ExperimentalUI.isNewUI()) JBUI.CurrentTheme.ToolWindow.DragAndDrop.AREA_BACKGROUND
    else JBUI.CurrentTheme.DragAndDrop.Area.BACKGROUND
