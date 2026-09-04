package com.github.atm1020.tuilaunch.copilot

import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.google.gson.JsonElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.util.Key

internal val PROMPT_BOX_COPILOT_DOCUMENT_KEY: Key<PromptBoxCopilotDocument> =
    Key.create("TUILaunch.PromptBoxCopilotDocument")

interface CopilotCompletionServer {

    fun didOpen(uri: String, languageId: String, version: Int, text: String)

    fun didChange(uri: String, version: Int, text: String)

    fun didClose(uri: String)

    suspend fun inlineCompletion(
        uri: String,
        version: Int,
        position: LspPosition,
        triggerKind: InlineCompletionTriggerKind,
    ): List<InlineCompletionItem>

    fun didShowCompletion(item: InlineCompletionItem)

    suspend fun executeCommand(command: String, arguments: List<String>): JsonElement
}

interface CopilotCompletionBackend {

    val serverState: CopilotServerState

    fun ensureStarted()

    fun completionServer(): CopilotCompletionServer?

    fun launchFollowUp(work: suspend (CopilotCompletionServer) -> Unit)
}

interface PromptBoxCompletionSettings {

    val completionSource: PromptBoxCompletionSource

    val includePromptHistory: Boolean
}

object PersistentPromptBoxCompletionSettings : PromptBoxCompletionSettings {

    override val completionSource: PromptBoxCompletionSource
        get() = TuiLauncherSettings.getInstance().state.promptBoxCompletionSource

    override val includePromptHistory: Boolean
        get() = TuiLauncherSettings.getInstance().state.copilotPromptHistoryContext
}

class PromptBoxCopilotStarter(
    private val settings: PromptBoxCompletionSettings = PersistentPromptBoxCompletionSettings,
    private val backend: () -> CopilotCompletionBackend = { CopilotLanguageServerService.getInstance() },
) {

    fun promptBoxShown() {
        if (settings.completionSource != PromptBoxCompletionSource.COPILOT) return
        backend().ensureStarted()
    }
}

class PromptBoxCopilotEditorListener(
    private val backend: () -> CopilotCompletionBackend = { CopilotLanguageServerService.getInstance() },
) : EditorFactoryListener {

    override fun editorReleased(event: EditorFactoryEvent) {
        closeVirtualDocumentOf(event.editor)
    }

    private fun closeVirtualDocumentOf(editor: Editor) {
        val document = editor.getUserData(PROMPT_BOX_COPILOT_DOCUMENT_KEY) ?: return
        editor.putUserData(PROMPT_BOX_COPILOT_DOCUMENT_KEY, null)
        backend().completionServer()?.let { document.close(it) }
    }
}
