package com.github.atm1020.tuilaunch.copilot

import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_KEY
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionEventAdapter
import com.intellij.codeInsight.inline.completion.InlineCompletionEventType
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertEnvironment
import com.intellij.codeInsight.inline.completion.InlineCompletionInsertHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionElement
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.session.InlineCompletionSession
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

const val COPILOT_INLINE_COMPLETION_PROVIDER_ID = "com.github.atm1020.tuilaunch.CopilotInlineCompletionProvider"
const val TUILAUNCH_NOTIFICATION_GROUP_ID = "TUILaunch"

internal const val COPILOT_UNAVAILABLE_TITLE = "GitHub Copilot is not completing the prompt box"
internal const val NOT_SIGNED_IN_REASON = "No Copilot client on this machine is signed in"

private val SHOWN_COMPLETION_KEY: Key<InlineCompletionItem> = Key.create("TUILaunch.CopilotShownCompletion")
private val REPORTED_COMPLETION_KEY: Key<InlineCompletionItem> = Key.create("TUILaunch.CopilotReportedCompletion")
private val SHOW_LISTENER_KEY: Key<Boolean> = Key.create("TUILaunch.CopilotShowListenerInstalled")

private class PromptBoxDraft(val text: String, val caretOffset: Int, val historyBlocks: List<String>)

private class UsableCompletion(val item: InlineCompletionItem, val ghostText: String)

class CopilotInlineCompletionProvider(
    private val settings: PromptBoxCompletionSettings = PersistentPromptBoxCompletionSettings,
    private val backend: () -> CopilotCompletionBackend = { CopilotLanguageServerService.getInstance() },
    private val announce: (String) -> Unit = ::notifyThatCopilotIsUnavailable,
) : DebouncedInlineCompletionProvider() {

    private val announcedReasons = ConcurrentHashMap.newKeySet<String>()
    private val failureAlreadyLogged = AtomicBoolean(false)

    override val id: InlineCompletionProviderID = InlineCompletionProviderID(COPILOT_INLINE_COMPLETION_PROVIDER_ID)

    override val insertHandler: InlineCompletionInsertHandler = AcceptedCompletionReporter()

    public override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration = DEBOUNCE_DELAY

    override fun restartOn(event: InlineCompletionEvent): Boolean = event is InlineCompletionEvent.ManualCall

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val editor = event.toRequest()?.editor ?: return false
        if (editor.getUserData(PROMPT_BOX_KEY) == null) return false
        if (settings.completionSource != PromptBoxCompletionSource.COPILOT) return false
        if (!asksCopilotForACompletion(event)) return false
        val backend = backend()
        return when (val state = backend.serverState) {
            is CopilotServerState.Ready -> {
                reportShownCompletionsOf(editor)
                true
            }

            CopilotServerState.Stopped -> {
                backend.ensureStarted()
                false
            }

            CopilotServerState.Starting -> false

            else -> {
                announceOnce(state)
                false
            }
        }
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val box = editor.getUserData(PROMPT_BOX_KEY) ?: return InlineCompletionSuggestion.Empty
        val server = backend().completionServer() ?: return InlineCompletionSuggestion.Empty
        return try {
            completionFor(editor, box, server, request)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rpc: JsonRpcException) {
            if (rpc.code !in ORDINARY_SERVER_ERROR_CODES) logOnce(rpc)
            InlineCompletionSuggestion.Empty
        } catch (failure: Exception) {
            logOnce(failure)
            InlineCompletionSuggestion.Empty
        }
    }

    private suspend fun completionFor(
        editor: Editor,
        box: PromptBox,
        server: CopilotCompletionServer,
        request: InlineCompletionRequest,
    ): InlineCompletionSuggestion {
        val draft = readAction {
            PromptBoxDraft(request.document.text, request.endOffset, box.promptHistoryBlocks())
        }
        val document = virtualDocumentOf(editor)
        val snapshot = document.snapshot(draft.historyBlocks, draft.text, settings.includePromptHistory)
        document.sync(server, snapshot.text)
        val items = server.inlineCompletion(
            document.uri,
            document.version,
            snapshot.positionOf(draft.caretOffset),
            triggerKindOf(request.event),
        )
        val usable = items.firstNotNullOfOrNull { item ->
            snapshot.ghostTextFor(item, draft.caretOffset)?.let { UsableCompletion(item, it) }
        } ?: return InlineCompletionSuggestion.Empty
        editor.putUserData(SHOWN_COMPLETION_KEY, usable.item)
        return InlineCompletionSingleSuggestion.build {
            emit(InlineCompletionGrayTextElement(usable.ghostText))
        }
    }

    private fun asksCopilotForACompletion(event: InlineCompletionEvent): Boolean =
        event is InlineCompletionEvent.DocumentChange || event is InlineCompletionEvent.ManualCall

    private fun triggerKindOf(event: InlineCompletionEvent): InlineCompletionTriggerKind =
        if (event is InlineCompletionEvent.ManualCall) {
            InlineCompletionTriggerKind.INVOKED
        } else {
            InlineCompletionTriggerKind.AUTOMATIC
        }

    private fun virtualDocumentOf(editor: Editor): PromptBoxCopilotDocument =
        editor.getUserData(PROMPT_BOX_COPILOT_DOCUMENT_KEY)
            ?: PromptBoxCopilotDocument().also { editor.putUserData(PROMPT_BOX_COPILOT_DOCUMENT_KEY, it) }

    private fun reportShownCompletionsOf(editor: Editor) {
        if (editor.getUserData(SHOW_LISTENER_KEY) == true) return
        val handler = InlineCompletion.getHandlerOrNull(editor) ?: return
        editor.putUserData(SHOW_LISTENER_KEY, true)
        val lifetime = Disposer.newDisposable("TUILaunch Copilot prompt box completions")
        EditorUtil.disposeWithEditor(editor, lifetime)
        handler.addEventListener(ShownCompletionReporter(editor), lifetime)
    }

    private fun announceOnce(state: CopilotServerState) {
        val reason = reasonOf(state) ?: return
        if (announcedReasons.add(reason)) announce(reason)
    }

    private fun reasonOf(state: CopilotServerState): String? = when (state) {
        is CopilotServerState.NotConfigured -> state.reason
        CopilotServerState.NotSignedIn -> NOT_SIGNED_IN_REASON
        is CopilotServerState.Failed -> state.reason
        else -> null
    }

    private fun logOnce(failure: Throwable) {
        if (!failureAlreadyLogged.compareAndSet(false, true)) return
        thisLogger().warn("The GitHub Copilot language server did not complete the prompt box", failure)
    }

    private inner class ShownCompletionReporter(private val editor: Editor) : InlineCompletionEventAdapter {

        override fun onShow(event: InlineCompletionEventType.Show) {
            if (InlineCompletionSession.getOrNull(editor)?.provider !== this@CopilotInlineCompletionProvider) return
            val item = editor.getUserData(SHOWN_COMPLETION_KEY) ?: return
            if (editor.getUserData(REPORTED_COMPLETION_KEY) === item) return
            editor.putUserData(REPORTED_COMPLETION_KEY, item)
            backend().completionServer()?.didShowCompletion(item)
        }
    }

    private inner class AcceptedCompletionReporter : InlineCompletionInsertHandler {

        override fun afterInsertion(
            environment: InlineCompletionInsertEnvironment,
            elements: List<InlineCompletionElement>,
        ) {
            val editor = environment.editor
            val item = editor.getUserData(SHOWN_COMPLETION_KEY) ?: return
            editor.putUserData(SHOWN_COMPLETION_KEY, null)
            editor.putUserData(REPORTED_COMPLETION_KEY, null)
            val command = item.acceptCommand ?: return
            backend().launchFollowUp { server -> server.executeCommand(command, item.acceptArguments) }
        }
    }

    companion object {
        val ORDINARY_SERVER_ERROR_CODES: Set<Int> = setOf(-32802, -32800, -32801, 1000)

        private val DEBOUNCE_DELAY = 120.milliseconds
    }
}

private fun notifyThatCopilotIsUnavailable(reason: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(TUILAUNCH_NOTIFICATION_GROUP_ID)
        .createNotification(
            COPILOT_UNAVAILABLE_TITLE,
            "$reason. The prompt box is using JetBrains AI Assistant meanwhile.",
            NotificationType.WARNING,
        )
        .notify(null)
}
