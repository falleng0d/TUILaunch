package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.SendPromptBoxAction
import com.github.atm1020.tuilaunch.copilot.CopilotCompletionBackend
import com.github.atm1020.tuilaunch.copilot.CopilotCompletionServer
import com.github.atm1020.tuilaunch.copilot.CopilotServerState
import com.github.atm1020.tuilaunch.copilot.InlineCompletionItem
import com.github.atm1020.tuilaunch.copilot.InlineCompletionTriggerKind
import com.github.atm1020.tuilaunch.copilot.LspPosition
import com.github.atm1020.tuilaunch.copilot.PromptBoxCompletionSettings
import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.SEND_PROMPT_BOX_ACTION_ID
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategy
import com.github.atm1020.tuilaunch.resume.TabIdentity
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.terminal.TerminalSession
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSize
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSizeAxis
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionHandler
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.editor.InlineCompletionEditorType
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.PlatformTestUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

private const val MAXIMUM_LEFTOVER_SESSIONS = 100
private const val GHOST_TEXT = "a suggestion nobody typed"
private const val GHOST_TEXT_TIMEOUT_SECONDS = 30
private const val CLAUDE_STATE_FILE_ARGUMENT = "\$0"

internal const val SEND_PROMPT_BOX_TEST_KEYSTROKE = "control ENTER"

internal fun claudeHookSettings(stateFile: Path): String =
    """{"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"/bin/sh","args":["-c",""" +
        """"cat > \"$CLAUDE_STATE_FILE_ARGUMENT.tmp\" && mv \"$CLAUDE_STATE_FILE_ARGUMENT.tmp\" """ +
        """\"$CLAUDE_STATE_FILE_ARGUMENT\"","$stateFile"],"timeout":5}]}]}}"""

internal fun codexHookToml(event: String): String =
    """hooks.$event=[{hooks=[{type="command",command="{ cat; echo; } >> \"${'$'}TUILAUNCH_CODEX_STATE\"",""" +
        "async=true,timeout=5}]}]"

internal fun codexHookArguments(): List<String> = listOf(
    "-c",
    codexHookToml("SessionStart"),
    "-c",
    codexHookToml("UserPromptSubmit"),
)

private class GhostTextProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID = InlineCompletionProviderID("TUILaunchGhostText")

    override fun isEnabled(event: InlineCompletionEvent): Boolean = true

    override fun isEditorTypeSupported(editorType: InlineCompletionEditorType): Boolean = true

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion =
        InlineCompletionSingleSuggestion.build { emit(InlineCompletionGrayTextElement(GHOST_TEXT)) }
}

internal fun showGhostTextIn(box: PromptBox, parentDisposable: Disposable) {
    val provider = GhostTextProvider()
    InlineCompletionHandler.registerTestHandler(provider, parentDisposable)
    askForAnInlineCompletionIn(box.editor, provider.id)
    PlatformTestUtil.waitWithEventsDispatching(
        "The test provider never rendered ghost text in the prompt box",
        { InlineCompletionContext.getOrNull(box.editor)?.isCurrentlyDisplaying() == true },
        GHOST_TEXT_TIMEOUT_SECONDS,
    )
}

internal fun askForAnInlineCompletionIn(editor: Editor, providerId: InlineCompletionProviderID) {
    val handler = requireNotNull(InlineCompletion.getHandlerOrNull(editor)) {
        "No inline completion handler was installed on the editor"
    }
    handler.invokeEvent(InlineCompletionEvent.ManualCall(editor, providerId, UserDataHolderBase()))
    val execution = CompletableFuture.runAsync { runBlocking { handler.awaitExecution() } }
    PlatformTestUtil.waitWithEventsDispatching(
        "The inline completion request never finished",
        { execution.isDone },
        GHOST_TEXT_TIMEOUT_SECONDS,
    )
    execution.join()
}

internal sealed interface CopilotServerCall {

    data class Opened(val uri: String, val languageId: String, val version: Int, val text: String) : CopilotServerCall

    data class Changed(val uri: String, val version: Int, val text: String) : CopilotServerCall

    data class Closed(val uri: String) : CopilotServerCall

    data class Asked(
        val uri: String,
        val version: Int,
        val position: LspPosition,
        val triggerKind: InlineCompletionTriggerKind,
    ) : CopilotServerCall

    data class Shown(val item: InlineCompletionItem) : CopilotServerCall

    data class Executed(val command: String, val arguments: List<String>) : CopilotServerCall
}

internal class FakeCopilotCompletionServer(
    private val items: List<InlineCompletionItem> = emptyList(),
    private val failure: Throwable? = null,
) : CopilotCompletionServer {

    private val recorded = Collections.synchronizedList(mutableListOf<CopilotServerCall>())

    val calls: List<CopilotServerCall> get() = synchronized(recorded) { recorded.toList() }

    override fun didOpen(uri: String, languageId: String, version: Int, text: String) {
        recorded.add(CopilotServerCall.Opened(uri, languageId, version, text))
    }

    override fun didChange(uri: String, version: Int, text: String) {
        recorded.add(CopilotServerCall.Changed(uri, version, text))
    }

    override fun didClose(uri: String) {
        recorded.add(CopilotServerCall.Closed(uri))
    }

    override suspend fun inlineCompletion(
        uri: String,
        version: Int,
        position: LspPosition,
        triggerKind: InlineCompletionTriggerKind,
    ): List<InlineCompletionItem> {
        recorded.add(CopilotServerCall.Asked(uri, version, position, triggerKind))
        failure?.let { throw it }
        return items
    }

    override fun didShowCompletion(item: InlineCompletionItem) {
        recorded.add(CopilotServerCall.Shown(item))
    }

    override suspend fun executeCommand(command: String, arguments: List<String>): JsonElement {
        recorded.add(CopilotServerCall.Executed(command, arguments))
        return JsonNull.INSTANCE
    }
}

internal class FakeCopilotCompletionBackend(
    override var serverState: CopilotServerState = CopilotServerState.Ready("octocat"),
    private val server: CopilotCompletionServer? = null,
) : CopilotCompletionBackend {

    var startRequests = 0
        private set

    override fun ensureStarted() {
        startRequests++
    }

    override fun completionServer(): CopilotCompletionServer? = server

    override fun launchFollowUp(work: suspend (CopilotCompletionServer) -> Unit) {
        val target = server ?: return
        runBlocking { work(target) }
    }
}

internal class FakePromptBoxCompletionSettings(
    override var completionSource: PromptBoxCompletionSource = PromptBoxCompletionSource.COPILOT,
    override var includePromptHistory: Boolean = false,
) : PromptBoxCompletionSettings

internal fun registerTheSendPromptBoxAction(parentDisposable: Disposable) {
    val actionManager = ActionManager.getInstance()
    if (actionManager.getAction(SEND_PROMPT_BOX_ACTION_ID) != null) return
    actionManager.registerAction(SEND_PROMPT_BOX_ACTION_ID, SendPromptBoxAction())
    val keymap = KeymapManager.getInstance().activeKeymap
    keymap.addShortcut(
        SEND_PROMPT_BOX_ACTION_ID,
        KeyboardShortcut(KeyStroke.getKeyStroke(SEND_PROMPT_BOX_TEST_KEYSTROKE), null),
    )
    Disposer.register(parentDisposable) {
        unbindTheSendPromptBoxShortcut()
        actionManager.unregisterAction(SEND_PROMPT_BOX_ACTION_ID)
    }
}

internal fun unbindTheSendPromptBoxShortcut() {
    KeymapManager.getInstance().activeKeymap.removeAllActionShortcuts(SEND_PROMPT_BOX_ACTION_ID)
}

internal fun closeSessionsLeftOpenByEarlierTests(service: TuiAppLaunchService) {
    repeat(MAXIMUM_LEFTOVER_SESSIONS) {
        if (!aSessionIsStillOpen(service)) return
        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }
    error("A session left open by an earlier test could not be closed")
}

private fun aSessionIsStillOpen(service: TuiAppLaunchService): Boolean {
    val probeHost = FakeHost()
    service.host = probeHost
    service.focusTui()
    return probeHost.showCount > 0
}

internal class RecordingSessionStrategy(
    private val arguments: List<String> = listOf("--recorded"),
) : AgentSessionStrategy {

    private val recorded = Collections.synchronizedList(mutableListOf<String>())

    val calls: List<String> get() = synchronized(recorded) { recorded.toList() }

    override fun prepareLaunch(tab: TabIdentity) {
        recorded.add("prepareLaunch")
    }

    override fun launchArguments(tab: TabIdentity): List<String> {
        recorded.add("launchArguments")
        return arguments
    }

    override fun restoreArguments(tab: TabIdentity): List<String> {
        recorded.add("restoreArguments")
        return arguments
    }
}

internal fun testCoroutineScope(parentDisposable: Disposable): CoroutineScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    Disposer.register(parentDisposable) { scope.cancel() }
    return scope
}

internal fun temporaryAgentSessionEnvironment(): AgentSessionEnvironment {
    val root = FileUtil.createTempDirectory("tuilaunch-agent-sessions", null, true).toPath()
    return AgentSessionEnvironment(
        homeDirectory = root.resolve("home"),
        stateDirectory = root.resolve("state"),
        bundledDirectory = root.resolve("integrations"),
        claudeConfigDir = root.resolve("claude").toString(),
        piCodingAgentDir = root.resolve("omp").toString(),
    )
}

internal data class SentKey(val keyCode: Int, val modifiers: Int, val keyChar: Char)

internal class FakeSession(private val terminalAcceptsText: Boolean = true) {
    val component: JComponent = JPanel()
    var focusCount = 0
    val sentText = mutableListOf<String>()
    val sentKeys = mutableListOf<SentKey>()
    private var terminationCallback: (() -> Unit)? = null

    fun requestFocus() {
        focusCount++
    }

    fun terminate() {
        terminationCallback?.invoke()
    }

    fun asTerminalSession(): TerminalSession = TerminalSession(
        component = component,
        requestFocus = { requestFocus() },
        registerTerminationCallback = { callback -> terminationCallback = callback },
        sendKey = { keyCode, modifiers, keyChar -> sentKeys.add(SentKey(keyCode, modifiers, keyChar)) },
        sendText = { text ->
            sentText.add(text)
            terminalAcceptsText
        },
    )
}

internal class FakeFactory(private val sessions: List<FakeSession>) : TerminalSessionFactory {
    private var index = 0
    val commands = mutableListOf<String>()

    constructor(session: FakeSession) : this(listOf(session))

    override fun create(parent: Disposable, command: String): TerminalSession {
        commands.add(command)
        return sessions[index++].asTerminalSession()
    }
}

internal class DeferredFactory(private val sessions: List<FakeSession>) : TerminalSessionFactory {
    private var onCreated: ((TerminalSession) -> Unit)? = null
    private var onFailed: ((Throwable) -> Unit)? = null
    private var index = 0
    var createCount = 0
    val commands = mutableListOf<String>()

    constructor(session: FakeSession) : this(listOf(session))

    override fun create(parent: Disposable, command: String): TerminalSession = error("Use async creation")

    override fun createAsync(
        parent: Disposable,
        command: String,
        onCreated: (TerminalSession) -> Unit,
        onFailed: (Throwable) -> Unit,
    ) {
        createCount++
        commands.add(command)
        this.onCreated = onCreated
        this.onFailed = onFailed
    }

    fun finish() {
        val callback = takePendingCreation() ?: return
        callback(sessions[index++].asTerminalSession())
    }

    fun fail() {
        val callback = onFailed ?: return
        takePendingCreation()
        callback(IllegalStateException("launch failed"))
    }

    private fun takePendingCreation(): ((TerminalSession) -> Unit)? {
        val callback = onCreated
        onCreated = null
        onFailed = null
        return callback
    }
}

internal class FakeHost : IdeToolWindowHost(null) {
    var visible = false
    var pinned = true
    var showCount = 0
    var dockedHorizontally = false
    private var selected: Any? = null
    val tabs = mutableListOf<Any>()
    val titles = mutableListOf<String>()
    val disposables = mutableListOf<CheckedDisposable>()
    var size: ToolWindowSize? = null
    var axis = ToolWindowSizeAxis.HEIGHT
    val appliedSizes = mutableListOf<ToolWindowSize>()
    var emitStaleResizeOnApply = false
    private var sizeChanged: (() -> Unit)? = null
    private var tabSelected: ((Any) -> Unit)? = null
    private var tabAdded: ((Any) -> Unit)? = null
    private var tabRemoving: ((Any) -> Unit)? = null
    private var tabRemoved: ((Any) -> Unit)? = null
    private var anchorChanged: ((Boolean) -> Unit)? = null
    private val tabsRemovedForDrag = mutableSetOf<Any>()
    private val disposableByTab = mutableMapOf<Any, CheckedDisposable>()
    private val componentByTab = mutableMapOf<Any, JComponent>()

    override fun isVisible(): Boolean = visible
    override fun isPinned(): Boolean = pinned
    override fun show() {
        visible = true
        showCount++
    }

    override fun hide() {
        visible = false
    }

    override fun addTab(component: JComponent, title: String, disposable: Disposable, index: Int): Any {
        val handle = Any()
        tabs.add(if (index in tabs.indices) index else tabs.size, handle)
        titles.add(title)
        componentByTab[handle] = component
        (disposable as? CheckedDisposable)?.let {
            disposables.add(it)
            disposableByTab[handle] = it
        }
        tabAdded?.invoke(handle)
        if (selected == null) {
            selected = handle
            tabSelected?.invoke(handle)
        }
        return handle
    }

    override fun selectTab(handle: Any) {
        if (selected === handle) return
        selected = handle
        tabSelected?.invoke(handle)
    }

    override fun activeTab(): Any? = selected

    override fun orderedHandles(): List<Any> = tabs.toList()

    override fun removeTab(handle: Any) {
        val index = tabs.indexOf(handle)
        if (index < 0) return
        tabRemoving?.invoke(handle)
        val removingSelectedTab = selected === handle
        if (removingSelectedTab) selected = null
        tabs.removeAt(index)
        tabRemoved?.invoke(handle)
        if (removingSelectedTab) {
            selected = tabs.getOrNull(index) ?: tabs.getOrNull(index - 1)
            selected?.let { tabSelected?.invoke(it) }
        }
    }

    fun dragTab(from: Int, to: Int) {
        val handle = detachTabForDrag(from)
        tabs.add(to, handle)
        tabAdded?.invoke(handle)
        selectTab(handle)
        tabsRemovedForDrag.remove(handle)
    }

    fun dragTabOutOfTheStrip(from: Int) {
        tabsRemovedForDrag.remove(detachTabForDrag(from))
    }

    fun dragTabIntoTheEditor(from: Int) {
        val handle = detachTabForDrag(from)
        disposableByTab[handle]?.let { Disposer.dispose(it) }
        tabsRemovedForDrag.remove(handle)
    }

    private fun detachTabForDrag(from: Int): Any {
        val handle = tabs[from]
        tabsRemovedForDrag.add(handle)
        if (selected === handle) selected = tabs.getOrNull(from + 1) ?: tabs.getOrNull(from - 1)
        tabs.removeAt(from)
        tabRemoved?.invoke(handle)
        selected?.let { tabSelected?.invoke(it) }
        return handle
    }

    fun visiblePositionOfActiveTab(): Int = tabs.indexOf(selected) + 1

    fun componentOf(handle: Any): JComponent? = componentByTab[handle]

    override fun isDockedHorizontally(): Boolean = dockedHorizontally

    override fun onAnchorChanged(listener: (Boolean) -> Unit) {
        anchorChanged = listener
    }

    fun triggerAnchorChanged(dockedHorizontally: Boolean) {
        this.dockedHorizontally = dockedHorizontally
        anchorChanged?.invoke(dockedHorizontally)
    }

    override fun isTabRemovedForDrag(handle: Any): Boolean = handle in tabsRemovedForDrag

    override fun isTabAttachedToToolWindow(handle: Any): Boolean = handle in tabs

    override fun currentSize(): ToolWindowSize? = size

    override fun sizeAxis(): ToolWindowSizeAxis = axis

    override fun applySize(size: ToolWindowSize) {
        appliedSizes.add(size)
        if (emitStaleResizeOnApply) {
            sizeChanged?.invoke()
        }
        this.size = size
    }

    override fun onSizeChanged(listener: () -> Unit) {
        sizeChanged = listener
    }

    override fun onTabSelected(listener: (Any) -> Unit) {
        tabSelected = listener
    }

    override fun onTabAdded(listener: (Any) -> Unit) {
        tabAdded = listener
    }

    override fun onTabRemoved(beforeRemoval: (Any) -> Unit, afterRemoval: (Any) -> Unit) {
        tabRemoving = beforeRemoval
        tabRemoved = afterRemoval
    }

    fun triggerSizeChanged() {
        sizeChanged?.invoke()
    }

    fun triggerTabSelected(handle: Any) {
        tabSelected?.invoke(handle)
    }

    fun triggerTabRemoved(handle: Any) {
        tabRemoved?.invoke(handle)
    }
}
