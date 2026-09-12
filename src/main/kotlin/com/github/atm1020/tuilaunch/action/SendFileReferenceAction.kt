package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.prompt.LineRange
import com.github.atm1020.tuilaunch.prompt.NO_SESSION_MESSAGE
import com.github.atm1020.tuilaunch.prompt.fileReference
import com.github.atm1020.tuilaunch.prompt.pathRelativeToRoot
import com.github.atm1020.tuilaunch.prompt.selectedLineRange
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

private const val NO_FILE_MESSAGE = "No file to reference"

class SendFileReferenceAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && fileOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = fileOf(e) ?: return
        val reference = referenceFor(project, file, editor)
        if (reference == null) {
            editor?.let { HintManager.getInstance().showErrorHint(it, NO_FILE_MESSAGE) }
            return
        }
        if (!project.service<TuiAppLaunchService>().sendFileReference(reference)) {
            editor?.let { HintManager.getInstance().showErrorHint(it, NO_SESSION_MESSAGE) }
        }
    }

    private fun fileOf(e: AnActionEvent): VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)

    private fun referenceFor(project: Project, file: VirtualFile, editor: Editor?): String? {
        val root = project.basePath ?: return null
        val relativePath = pathRelativeToRoot(root, file.path) ?: return null
        return fileReference(relativePath, editor?.let(::selectionLines))
    }

    private fun selectionLines(editor: Editor): LineRange? {
        val selection = editor.selectionModel
        if (!selection.hasSelection()) return null
        val document = editor.document
        val end = selection.selectionEnd
        return selectedLineRange(
            startLine = document.getLineNumber(selection.selectionStart),
            endLine = document.getLineNumber(end),
            endsAtLineStart = end == document.getLineStartOffset(document.getLineNumber(end)),
        )
    }
}
