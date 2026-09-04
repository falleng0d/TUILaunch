package com.github.atm1020.tuilaunch.prompt

import com.github.atm1020.tuilaunch.action.findOrCreatePromptFile
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

internal const val NOTHING_TO_SEND_MESSAGE = "Nothing to send"

private const val SEND_PROMPT_EDIT_NAME = "Send Prompt"

internal fun promptFileDocumentSupplier(project: Project): () -> Document? = {
    findOrCreatePromptFile(project)?.let { FileDocumentManager.getInstance().getDocument(it) }
}

internal class PromptBoxSender(
    private val project: Project,
    private val sendToSession: (text: String, submit: Boolean) -> Boolean,
    private val focusSession: () -> Unit,
    private val promptDocument: () -> Document? = promptFileDocumentSupplier(project),
) {

    fun send(box: PromptBox) {
        val prompt = box.text.trimEnd('\n')
        if (prompt.isBlank()) {
            HintManager.getInstance().showInformationHint(box.editor, NOTHING_TO_SEND_MESSAGE)
            return
        }
        val settings = TuiLauncherSettings.getInstance().state
        if (!sendToSession(prompt, settings.submitPromptOnSend)) {
            HintManager.getInstance().showErrorHint(box.editor, NO_SESSION_MESSAGE)
            return
        }
        appendToThePromptFile(prompt, settings.appendPromptSeparatorOnSend)
        box.clear()
        box.historyReset()
        if (settings.focusTuiAfterPromptBoxSend) focusSession() else box.requestFocus()
    }

    private fun appendToThePromptFile(prompt: String, leaveOpenSlot: Boolean) {
        val document = promptDocument()
        if (document == null || !document.isWritable) {
            thisLogger().warn("Prompt sent but not recorded: this project has no writable $PROMPT_FILE_NAME")
            return
        }
        val edit = promptAppendEdit(document.charsSequence, prompt, leaveOpenSlot)
        WriteCommandAction.writeCommandAction(project).withName(SEND_PROMPT_EDIT_NAME).run<RuntimeException> {
            document.replaceString(edit.from, edit.to, edit.text)
        }
        FileDocumentManager.getInstance().saveDocument(document)
    }
}
