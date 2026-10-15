package com.github.atm1020.tuilaunch.editor

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

private const val EDITOR_TYPE_ID = "tuilaunch-session-editor"
private const val EDITOR_NAME = "TUILaunch Session"

class TuiSessionFileEditor(
    private val project: Project,
    private val file: TuiSessionFile,
) : UserDataHolderBase(), FileEditor {

    private val panel = JPanel(BorderLayout())

    init {
        file.newestEditor = this
        if (file.component.parent == null) panel.add(file.component, BorderLayout.CENTER)
        panel.addHierarchyListener { event ->
            val showingChanged = event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L
            if (showingChanged && panel.isShowing) SwingUtilities.invokeLater(::takeTheSessionIfStillShown)
        }
    }

    private fun takeTheSessionIfStillShown() {
        val noNewerCopyExists = file.newestEditor === this || file.newestEditor == null
        if (!panel.isShowing || !file.isValid || !noNewerCopyExists) return
        if (file.component.parent !== panel) {
            panel.add(file.component, BorderLayout.CENTER)
            panel.revalidate()
            panel.repaint()
        }
        ApplicationManager.getApplication().invokeLater(
            {
                if (panel.isShowing && file.isValid) {
                    project.service<TuiAppLaunchService>().editorHost.closeCopiesOutside(file, panel)
                }
            },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = file.preferredFocusedComponent

    override fun getName(): String = EDITOR_NAME

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        if (file.newestEditor === this) file.newestEditor = null
        val closingToOpenElsewhere = file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true
        if (closingToOpenElsewhere || project.isDisposed) return
        project.service<TuiAppLaunchService>().editorTabClosed(file)
    }
}

class TuiSessionFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is TuiSessionFile && file.isValid

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        TuiSessionFileEditor(project, file as TuiSessionFile)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
