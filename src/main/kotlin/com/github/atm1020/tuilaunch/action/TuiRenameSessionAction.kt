package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowContextMenuActionBase
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.Content
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.SwingHelper
import java.awt.Component
import java.awt.Font
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.event.DocumentEvent

private const val OUTLINE_PROPERTY = "JComponent.outline"
private const val OUTLINE_ERROR = "error"

class TuiRenameSessionAction : ToolWindowContextMenuActionBase() {

    override fun update(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        e.presentation.isEnabledAndVisible =
            e.project != null && toolWindow.id == TUI_TOOL_WINDOW_ID && content != null
    }

    override fun actionPerformed(e: AnActionEvent, toolWindow: ToolWindow, content: Content?) {
        val project = e.project ?: return
        val target = content ?: return
        showRenameBalloon(renameAnchor(e, toolWindow), target, project)
    }

    private fun renameAnchor(e: AnActionEvent, toolWindow: ToolWindow): Component {
        e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)?.takeIf { it.isShowing }?.let { return it }
        e.inputEvent?.component?.takeIf { it.isShowing }?.let { return it }
        return toolWindow.component
    }

    private fun showRenameBalloon(anchor: Component, content: Content, project: Project) {
        val textField = JBTextField(content.displayName.orEmpty())
        textField.selectAll()

        val label = JBLabel("New session name:")
        label.font = label.font.deriveFont(Font.BOLD)
        val panel = SwingHelper.newLeftAlignedVerticalPanel(
            label,
            Box.createVerticalStrut(JBUI.scale(2)),
            textField,
        )
        panel.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                IdeFocusManager.findInstance().requestFocus(textField, true)
            }
        })

        val balloon = JBPopupFactory.getInstance()
            .createDialogBalloonBuilder(panel, null)
            .setShowCallout(true)
            .setCloseButtonEnabled(false)
            .setAnimationCycle(0)
            .setDisposable(content)
            .setHideOnKeyOutside(true)
            .setHideOnClickOutside(true)
            .setRequestFocus(true)
            .setBlockClicksThroughBalloon(true)
            .createBalloon()

        textField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        val newName = textField.text
                        if (newName.isEmpty()) {
                            textField.putClientProperty(OUTLINE_PROPERTY, OUTLINE_ERROR)
                            textField.repaint()
                        } else {
                            applyContentDisplayName(content, project, newName)
                            balloon.hide()
                        }
                    }
                    KeyEvent.VK_ESCAPE -> balloon.hide()
                }
            }
        })
        textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (textField.getClientProperty(OUTLINE_PROPERTY) == OUTLINE_ERROR) {
                    textField.putClientProperty(OUTLINE_PROPERTY, null)
                    textField.repaint()
                }
            }
        })

        val point = RelativePoint(anchor, Point(anchor.width / 2, anchor.height))
        balloon.show(point, Balloon.Position.below)
    }

    private fun applyContentDisplayName(content: Content, project: Project, newContentName: String) {
        val service = project.service<TuiAppLaunchService>()
        val host = service.host
            ?: IdeToolWindowHost(ToolWindowManager.getInstance(project).getToolWindow(TUI_TOOL_WINDOW_ID))
                .also { service.host = it }
        val handle = host.handleFor(content)
        host.setTabTitle(handle, newContentName)
        service.renameTab(handle, newContentName)
    }
}
