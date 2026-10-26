package com.github.atm1020.tuilaunch.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.project.Project
import javax.swing.JComponent
import javax.swing.SwingUtilities

private const val EDITOR_WINDOW_CLASS = "com.intellij.openapi.fileEditor.impl.EditorWindow"
private const val TAB_LIMIT_METHOD = "doTrimSize"

internal fun theEditorTabLimitIsClosingATab(): Boolean =
    callersAreTheEditorTabLimit(StackWalker.getInstance().walk { frames -> frames.map { it.className to it.methodName }.toList() })

internal fun callersAreTheEditorTabLimit(callers: List<Pair<String, String>>): Boolean =
    (EDITOR_WINDOW_CLASS to TAB_LIMIT_METHOD) in callers

open class SessionEditorHost(private val project: Project?) {

    open fun open(file: TuiSessionFile, window: EditorWindow?) {
        FileEditorManagerEx.getInstanceEx(requireProject()).openFile(file, window, openOptions())
    }

    open fun select(file: TuiSessionFile) {
        FileEditorManager.getInstance(requireProject()).openFile(file, false, true)
    }

    open fun close(file: TuiSessionFile) {
        whileClosingToReopen(file) { FileEditorManager.getInstance(requireProject()).closeFile(file) }
        file.isValid = false
    }

    open fun closeCopiesOutside(file: TuiSessionFile, shownEditorComponent: JComponent) {
        val manager = FileEditorManagerEx.getInstanceEx(requireProject())
        val windowsWithTheFile = manager.windows.filter { it.isFileOpen(file) }
        if (windowsWithTheFile.size < 2) return
        val copies = windowsWithTheFile.filterNot { window -> window.holds(file, shownEditorComponent) }
        if (copies.size == windowsWithTheFile.size) return
        copies.filterNot { window -> window.isStillBuilding(file) }.forEach { window -> whileClosingToReopen(file) { manager.closeFile(file, window) } }
    }

    private fun EditorWindow.holds(file: TuiSessionFile, editorComponent: JComponent): Boolean {
        val composite = getComposite(file) ?: return false
        return SwingUtilities.isDescendingFrom(editorComponent, composite.component)
    }

    private fun EditorWindow.isStillBuilding(file: TuiSessionFile): Boolean =
        getComposite(file)?.allEditors.isNullOrEmpty()

    private fun whileClosingToReopen(file: TuiSessionFile, close: () -> Unit) {
        file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true)
        try {
            close()
        } finally {
            file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null)
        }
    }

    open fun isOpen(file: TuiSessionFile): Boolean = FileEditorManager.getInstance(requireProject()).isFileOpen(file)

    open fun titleChanged(file: TuiSessionFile) {
        FileEditorManagerEx.getInstanceEx(requireProject()).updateFilePresentation(file)
    }

    open fun onSelected(parentDisposable: Disposable, listener: (TuiSessionFile) -> Unit) {
        requireProject().messageBus.connect(parentDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    (event.newFile as? TuiSessionFile)?.let(listener)
                }
            },
        )
    }

    private fun requireProject(): Project = requireNotNull(project) { "Project is required" }

    companion object {
        internal fun openOptions(): FileEditorOpenOptions =
            FileEditorOpenOptions(requestFocus = true, pin = TuiLauncherSettings.getInstance().state.pinSessionsMovedToEditor)
    }
}
