package com.github.atm1020.tuilaunch.prompt

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager

internal const val PROMPT_FILE_NAME = "PROMPT.md"
internal const val NO_SESSION_MESSAGE = "No TUI session is open"

private val IGNORED_EDITOR_KINDS = setOf(EditorKind.DIFF, EditorKind.PREVIEW, EditorKind.CONSOLE)

internal var promptEditorFocusRequest: (Project, Editor) -> Unit = { project, editor ->
    IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
}

internal fun editorShowsThePromptFile(editor: Editor): Boolean =
    editor.editorKind !in IGNORED_EDITOR_KINDS &&
        !editor.isViewer &&
        fileNameOf(editor) == PROMPT_FILE_NAME

private fun fileNameOf(editor: Editor): String? =
    FileDocumentManager.getInstance().getFile(editor.document)?.name ?: editor.virtualFile?.name

internal fun sendPromptBlock(project: Project, editor: Editor, blockLine: Int, blockText: String) {
    val settings = TuiLauncherSettings.getInstance().state
    val keepFocusInThePromptFile = settings.focusPromptFileAfterSend
    val sent = project.service<TuiAppLaunchService>().sendTextToActiveSession(
        blockText,
        submit = settings.submitPromptOnSend,
        focusSession = !keepFocusInThePromptFile,
    )
    if (!sent) {
        HintManager.getInstance().showErrorHint(editor, NO_SESSION_MESSAGE)
        return
    }
    val theSentBlockEndsTheFile = isLastPromptBlock(editor.document.charsSequence, blockLine)
    if (settings.appendPromptSeparatorOnSend && theSentBlockEndsTheFile) {
        openNextPromptSlot(project, editor)
    }
    if (keepFocusInThePromptFile) promptEditorFocusRequest(project, editor)
}

internal fun openNextPromptSlot(project: Project, editor: Editor) {
    val document = editor.document
    if (!document.isWritable) return
    val edit = promptSeparatorEdit(document.charsSequence)
    if (edit != null) {
        WriteCommandAction.runWriteCommandAction(project) {
            document.replaceString(edit.from, edit.to, edit.text)
        }
    }
    editor.caretModel.moveToOffset(edit?.caretOffset ?: document.textLength)
    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
}

internal fun promptBlockTextUnder(editor: Editor, highlighter: RangeHighlighter): String? {
    if (!highlighter.isValid) return null
    val document = editor.document
    return promptBlockTextAt(document.charsSequence, document.getLineNumber(highlighter.startOffset))
}

internal fun promptBlockLineUnder(editor: Editor, highlighter: RangeHighlighter): Int =
    editor.document.getLineNumber(highlighter.startOffset)

internal fun promptBlockTextUnderTheCaret(editor: Editor): String? {
    if (!editorShowsThePromptFile(editor)) return null
    val document = editor.document
    return promptBlockTextContainingLine(document.charsSequence, caretLineOf(editor))
}

internal fun aPromptBlockHoldsTheCaret(editor: Editor): Boolean {
    if (!editorShowsThePromptFile(editor)) return false
    return promptBlockContainsLine(editor.document.charsSequence, caretLineOf(editor))
}

internal fun caretLineOf(editor: Editor): Int =
    editor.document.getLineNumber(editor.caretModel.offset)
