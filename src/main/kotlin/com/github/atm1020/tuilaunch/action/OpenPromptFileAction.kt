package com.github.atm1020.tuilaunch.action

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

private const val PROMPT_FILE_NAME = "PROMPT.md"

class OpenPromptFileAction : DumbAwareAction(
    "Open Prompt File",
    "Open this project's PROMPT.md, creating it if it does not exist yet",
    AllIcons.General.Locate,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openProjectPromptFile(project)
    }
}

internal fun openProjectPromptFile(project: Project): VirtualFile? {
    val promptFile = findOrCreatePromptFile(project) ?: return null
    FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, promptFile), true)
    pinTab(project, promptFile)
    return promptFile
}

internal fun findOrCreatePromptFile(project: Project): VirtualFile? {
    val projectRoot = project.guessProjectDir() ?: return null
    return findOrCreatePromptFileIn(projectRoot)
}

internal fun findOrCreatePromptFileIn(projectRoot: VirtualFile): VirtualFile? =
    projectRoot.findChild(PROMPT_FILE_NAME) ?: createPromptFile(projectRoot)

private fun createPromptFile(projectRoot: VirtualFile): VirtualFile? =
    try {
        runWriteAction { projectRoot.createChildData(null, PROMPT_FILE_NAME) }
    } catch (failure: IOException) {
        val writtenOutsideTheIde = refreshAndFindPromptFile(projectRoot)
        if (writtenOutsideTheIde == null) {
            Logger.getInstance(OpenPromptFileAction::class.java)
                .warn("Cannot create $PROMPT_FILE_NAME in ${projectRoot.path}", failure)
        }
        writtenOutsideTheIde
    }

private fun refreshAndFindPromptFile(projectRoot: VirtualFile): VirtualFile? {
    projectRoot.refresh(false, false)
    return projectRoot.findChild(PROMPT_FILE_NAME)
}

private fun pinTab(project: Project, file: VirtualFile) {
    val editorWindow = (FileEditorManager.getInstance(project) as? FileEditorManagerEx)?.currentWindow ?: return
    if (!editorWindow.isFilePinned(file)) editorWindow.setFilePinned(file, true)
}
