package com.github.atm1020.tuilaunch.services

import com.github.atm1020.tuilaunch.copilot.PromptBoxCopilotStarter
import com.github.atm1020.tuilaunch.editor.SessionEditorHost
import com.github.atm1020.tuilaunch.editor.TuiSessionFile
import com.github.atm1020.tuilaunch.model.ACTION_ID_PREFIX
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.PromptBoxSender
import com.github.atm1020.tuilaunch.prompt.existingPromptFileDocumentSupplier
import com.github.atm1020.tuilaunch.prompt.promptFileDocumentSupplier
import com.github.atm1020.tuilaunch.resume.AgentCliKind
import com.github.atm1020.tuilaunch.resume.AgentCommand
import com.github.atm1020.tuilaunch.resume.AgentSessionEnvironment
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategies
import com.github.atm1020.tuilaunch.resume.AgentSessionStrategy
import com.github.atm1020.tuilaunch.resume.AgentStateFiles
import com.github.atm1020.tuilaunch.resume.ClaudeSessionStrategy
import com.github.atm1020.tuilaunch.resume.CodexSessionStrategy
import com.github.atm1020.tuilaunch.resume.OmpSessionStrategy
import com.github.atm1020.tuilaunch.resume.OpenCodeServer
import com.github.atm1020.tuilaunch.resume.OpenCodeServerService
import com.github.atm1020.tuilaunch.resume.OpenCodeSessionStrategy
import com.github.atm1020.tuilaunch.resume.TabIdentity
import com.github.atm1020.tuilaunch.terminal.JediTermSessionFactory
import com.github.atm1020.tuilaunch.terminal.TerminalSession
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.github.atm1020.tuilaunch.terminal.cleanTerminalScreen
import com.github.atm1020.tuilaunch.toolwindow.APPEND_TAB
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSize
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSizeAxis
import com.github.atm1020.tuilaunch.toolwindow.TuiTabLayout
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.beans.PropertyChangeListener
import java.util.UUID
import javax.swing.SwingUtilities

const val TUI_TOOL_WINDOW_ID = "TUILaunch"

private const val SUBMIT_KEY_CHAR = '\r'
private const val EARLY_EXIT_RELAUNCH_WINDOW_MILLIS = 15_000L
private const val FOCUS_OWNER_PROPERTY = "focusOwner"
private val RESUME_ARGUMENTS = setOf(
    ClaudeSessionStrategy.RESUME_FLAG,
    CodexSessionStrategy.RESUME_SUBCOMMAND,
    OmpSessionStrategy.RESUME_FLAG,
    OpenCodeSessionStrategy.SESSION_FLAG,
)

internal fun uniqueSessionTitle(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var suffix = 1
    while ("$base $suffix" in taken) suffix++
    return "$base $suffix"
}

@Service(Service.Level.PROJECT)
class TuiAppLaunchService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    var sessionFactory: TerminalSessionFactory = JediTermSessionFactory(
        project = project,
        prefixCommandActions = { prefixCommandActions() },
        onNextTab = { nextTuiTab() },
        onPreviousTab = { previousTuiTab() },
    )
    var host: IdeToolWindowHost? = null
    var editorHost: SessionEditorHost = SessionEditorHost(project)
    var activeToolWindowIdProvider: () -> String? = {
        ToolWindowManager.getInstance(project).activeToolWindowId
    }
    var focusOwnerProvider: () -> Component? = {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    }
    var editorFocusRequest: () -> Unit = {
        val editors = FileEditorManager.getInstance(project)
        val component = editors.selectedTextEditor?.contentComponent
            ?: editors.selectedEditors.firstNotNullOfOrNull { (it as? TextEditor)?.editor?.contentComponent }
        if (component != null) IdeFocusManager.getInstance(project).requestFocus(component, true)
    }
    var promptBoxCompletions: PromptBoxCopilotStarter = PromptBoxCopilotStarter()
    var agentSessionEnvironment: () -> AgentSessionEnvironment = {
        AgentSessionEnvironment.fromSystem(
            stateDirectory = AgentSessionEnvironment.stateDirectoryFor(project.locationHash),
            bundledDirectory = AgentSessionEnvironment.bundledDirectory(),
        )
    }
    var openCodeServer: () -> OpenCodeServer? = {
        if (TuiLauncherSettings.getInstance().state.shareOpenCodeServer) {
            OpenCodeServerService.getInstance(project)
        } else {
            null
        }
    }
    var agentSessionStrategies: (AgentCliKind, AgentSessionEnvironment, Boolean) -> AgentSessionStrategy =
        { kind, environment, hookAllowed ->
            AgentSessionStrategies.forKind(kind, environment, hookAllowed) { openCodeServer() }
        }
    var clock: () -> Long = { System.currentTimeMillis() }
    private var hostListenersInstalled = false
    private var editorListenersInstalled = false
    private var windowRevealedByLaunch = false
    private var applyingSize = false
    private var restoringTabs = false
    private var restoreAttempted = false
    private var projectIsClosing = false

    private sealed interface SessionPlace {
        data class InToolWindow(val handle: Any) : SessionPlace

        data class InEditor(val file: TuiSessionFile) : SessionPlace
    }

    private data class OpenTab(
        val sessionId: String,
        val appName: String,
        var title: String,
        val session: TerminalSession,
        val layout: TuiTabLayout,
        val promptBox: PromptBox,
        var place: SessionPlace,
        val disposable: Disposable,
        var openedFromTui: Boolean,
        val tabUuid: String,
        val agentSessionId: String?,
        val agentCliKind: String?,
        val launchedAt: Long,
        val restoredFromRecord: Boolean,
        val commandWasDecorated: Boolean,
        val agentSessionWasRequested: Boolean,
        val agentStrategy: AgentSessionStrategy?,
    ) {
        val handle: Any? get() = (place as? SessionPlace.InToolWindow)?.handle

        val editorFile: TuiSessionFile? get() = (place as? SessionPlace.InEditor)?.file
    }

    private data class PendingLaunch(val appName: String, val disposable: Disposable)

    private data class AgentSetup(
        val kind: AgentCliKind,
        val parsed: AgentCommand,
        val tab: TabIdentity,
        val strategy: AgentSessionStrategy,
    )

    private class PendingTab(
        val host: IdeToolWindowHost,
        val sessionId: String,
        val appName: String,
        val command: String,
        val title: String,
        val intent: LaunchIntent,
        val index: Int,
        val onOpened: ((OpenTab) -> Unit)?,
        val onFailed: (() -> Unit)?,
        val disposable: CheckedDisposable,
        val setup: AgentSetup?,
    )

    private sealed interface LaunchIntent {
        val tabUuid: String

        data class Fresh(override val tabUuid: String) : LaunchIntent

        data class Restore(override val tabUuid: String, val record: TuiSessionRecord) : LaunchIntent
    }

    private data class AgentLaunch(
        val command: String,
        val environment: Map<String, String> = emptyMap(),
        val commandWasDecorated: Boolean,
        val agentSessionWasRequested: Boolean,
        val agentSessionId: String?,
        val cliKind: String?,
        val strategy: AgentSessionStrategy?,
    )

    private val tabsBySessionId = mutableMapOf<String, OpenTab>()
    private val pendingLaunchesBySessionId = mutableMapOf<String, PendingLaunch>()
    private val promptBoxPreferences = PromptBoxPreferences { TuiLauncherSettings.getInstance().state }
    private val promptDocument = promptFileDocumentSupplier(project)
    private val existingPromptDocument = existingPromptFileDocumentSupplier(project)

    private val closingSessions = mutableSetOf<String>()
    private val sessionIdsRemovedForDrag = mutableSetOf<String>()
    private var sessionSequence = 0
    private var activeSessionIdBeingRemoved: String? = null
    private var lastFocusedSessionId: String? = null

    private fun tabsNotClosing(): Map<String, OpenTab> =
        tabsBySessionId.filterKeys { it !in closingSessions }

    private fun resolveHost(): IdeToolWindowHost? {
        host?.let { return it }
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TUI_TOOL_WINDOW_ID)
        if (tw == null) {
            thisLogger().warn("TUILaunch tool window not registered")
            return null
        }
        return IdeToolWindowHost(tw).also { host = it }
    }

    private fun hostWithListeners(): IdeToolWindowHost? = resolveHost()?.also {
        ensureHostListeners(it)
        closeTabsLostToADrag(it)
    }

    private fun ensureHostListeners(host: IdeToolWindowHost) {
        if (hostListenersInstalled) return
        hostListenersInstalled = true
        host.onSizeChanged { recordActiveTabSize(host) }
        host.onTabSelected { handle -> onTabSelected(host, handle) }
        host.onTabAdded { handle -> onTabAdded(handle) }
        host.onTabRemoved(
            beforeRemoval = { handle -> onTabRemoving(host, handle) },
            afterRemoval = { handle -> onTabRemoved(host, handle) },
        )
        host.onAnchorChanged { dockedHorizontally ->
            tabsBySessionId.values
                .filter { it.handle != null }
                .forEach { it.layout.setDockedHorizontally(dockedHorizontally) }
        }
    }

    private fun ensureEditorListeners() {
        if (editorListenersInstalled) return
        editorListenersInstalled = true
        editorHost.onSelected(this) { file -> tabIn(file)?.let { lastFocusedSessionId = it.sessionId } }
        trackTheFocusedSession()
    }

    private fun trackTheFocusedSession() {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = PropertyChangeListener { event -> onFocusOwnerChanged(event.newValue as? Component) }
        focusManager.addPropertyChangeListener(FOCUS_OWNER_PROPERTY, listener)
        Disposer.register(this) { focusManager.removePropertyChangeListener(FOCUS_OWNER_PROPERTY, listener) }
    }

    internal fun onFocusOwnerChanged(owner: Component?) {
        sessionHolding(owner ?: return)?.let { lastFocusedSessionId = it.sessionId }
    }

    private fun sessionHolding(component: Component): OpenTab? =
        tabsNotClosing().values.firstOrNull { SwingUtilities.isDescendingFrom(component, it.layout.component) }

    private fun tabFor(handle: Any): OpenTab? = tabsBySessionId.values.firstOrNull { it.handle == handle }

    private fun tabIn(file: TuiSessionFile): OpenTab? = tabsBySessionId.values.firstOrNull { it.editorFile === file }

    private fun activeToolWindowTab(host: IdeToolWindowHost): OpenTab? {
        val toolWindowTabs = tabsNotClosing().values.filter { it.handle != null }
        val activeHandle = host.activeTab() ?: return toolWindowTabs.lastOrNull()
        return toolWindowTabs.firstOrNull { it.handle == activeHandle } ?: toolWindowTabs.lastOrNull()
    }

    private fun activeSession(host: IdeToolWindowHost): OpenTab? {
        val openTabs = tabsNotClosing()
        val lastFocused = lastFocusedSessionId?.let { openTabs[it] }
        if (lastFocused?.editorFile != null) return lastFocused
        return activeToolWindowTab(host) ?: openTabs.values.lastOrNull()
    }

    private fun onTabSelected(host: IdeToolWindowHost, handle: Any) {
        val tab = tabFor(handle) ?: return
        applySavedSize(host, tab.appName)
        applySavedPromptBoxState(tab)
        recordOpenTabs()
    }

    private fun recordOpenTabs() {
        if (restoringTabs) return
        if (!TuiLauncherSettings.getInstance().state.restoreOpenTabs) return
        val host = host ?: return
        val activeHandle = host.activeTab()
        val toolWindowRecords = host.orderedHandles().mapNotNull { handle ->
            val tab = tabFor(handle) ?: return@mapNotNull null
            if (tab.sessionId in closingSessions) return@mapNotNull null
            recordOf(tab, selected = handle == activeHandle)
        }
        val editorRecords = tabsNotClosing().values
            .filter { it.editorFile != null }
            .map { recordOf(it, selected = false) }
        TuiOpenTabsService.getInstance(project).replaceTabs(toolWindowRecords + editorRecords)
    }

    private fun recordOf(tab: OpenTab, selected: Boolean): TuiSessionRecord = TuiSessionRecord(
        tab.appName,
        tab.title,
        selected,
        tab.tabUuid,
        tab.agentSessionId,
        tab.agentCliKind,
    )

    private fun applySavedSize(host: IdeToolWindowHost, appName: String) {
        val size = savedSize(host, appName) ?: return
        applyingSize = true
        try {
            host.applySize(size)
        } finally {
            invokeLater { applyingSize = false }
        }
    }

    fun toggle(actionId: String, command: String, title: String) {
        val host = hostWithListeners() ?: return
        val appName = actionId.removePrefix(ACTION_ID_PREFIX)
        val existing = tabsNotClosing().values.firstOrNull { it.appName == appName }
        if (existing != null) {
            selectTuiTab(host, existing)
            return
        }
        if (pendingLaunchesBySessionId.values.any { it.appName == appName }) return

        windowRevealedByLaunch = !host.isVisible()
        openNewTab(
            host = host,
            sessionId = newSessionId(appName),
            appName = appName,
            command = command,
            title = title,
            intent = LaunchIntent.Fresh(newTabUuid()),
        )
    }

    fun launchNew(appName: String, command: String) {
        val host = hostWithListeners() ?: return
        val title = uniqueSessionTitle(appName, tabsBySessionId.values.mapTo(mutableSetOf()) { it.title })
        windowRevealedByLaunch = !host.isVisible()
        openNewTab(
            host = host,
            sessionId = newSessionId(appName),
            appName = appName,
            command = command,
            title = title,
            intent = LaunchIntent.Fresh(newTabUuid()),
        )
    }

    private fun newSessionId(appName: String): String = "$appName#${sessionSequence++}"

    private fun newTabUuid(): String = UUID.randomUUID().toString()

    fun restoreSavedTabs() {
        if (!TuiLauncherSettings.getInstance().state.restoreOpenTabs) return
        if (restoreAttempted) return
        restoreAttempted = true
        val saved = TuiOpenTabsService.getInstance(project).state.tabs.toList()
        deleteAgentStateOfTabsThatAreGone(saved)
        if (saved.isEmpty()) return
        val host = hostWithListeners() ?: return
        restoringTabs = true
        restoreTabAt(host, saved, 0, null, null)
    }

    private fun deleteAgentStateOfTabsThatAreGone(saved: List<TuiSessionRecord>) {
        if (!agentSessionsAreResumed()) return
        val stateDirectory = agentSessionEnvironment().stateDirectory
        val directories = listOf(
            ClaudeSessionStrategy.DIRECTORY_NAME,
            CodexSessionStrategy.DIRECTORY_NAME,
            OpenCodeSessionStrategy.DIRECTORY_NAME,
        ).map { stateDirectory.resolve(it) }
        val restoredTabUuids = saved.mapNotNullTo(mutableSetOf()) { it.tabUuid }
        scope.launch {
            directories.forEach { AgentStateFiles.deleteStateFilesExcept(it, restoredTabUuids) }
        }
    }

    private fun restoreTabAt(
        host: IdeToolWindowHost,
        saved: List<TuiSessionRecord>,
        index: Int,
        tabToSelect: OpenTab?,
        lastRestoredTab: OpenTab?,
    ) {
        if (index >= saved.size) {
            finishRestore(host, tabToSelect ?: lastRestoredTab)
            return
        }
        val record = saved[index]
        val config = configFor(record.appName)
        if (config == null) {
            thisLogger().info("Skipping restore of TUI tab '${record.title}': app '${record.appName}' is no longer configured")
            restoreTabAt(host, saved, index + 1, tabToSelect, lastRestoredTab)
            return
        }
        val title = uniqueSessionTitle(
            record.title.ifBlank { record.appName },
            tabsBySessionId.values.mapTo(mutableSetOf()) { it.title },
        )
        openNewTab(
            host = host,
            sessionId = newSessionId(record.appName),
            appName = record.appName,
            command = config.command,
            title = title,
            intent = LaunchIntent.Restore(record.tabUuid ?: newTabUuid(), record),
            onOpened = { tab ->
                restoreTabAt(host, saved, index + 1, if (record.selected) tab else tabToSelect, tab)
            },
            onFailed = { restoreTabAt(host, saved, index + 1, tabToSelect, lastRestoredTab) },
        )
    }

    private fun finishRestore(host: IdeToolWindowHost, tabToSelect: OpenTab?) {
        restoringTabs = false
        tabToSelect?.handle?.let(host::selectTab)
        recordOpenTabs()
    }

    fun releasePromptBoxEditors() {
        tabsBySessionId.values.forEach { it.promptBox.releaseEditor() }
    }

    fun projectClosing() {
        projectIsClosing = true
        releasePromptBoxEditors()
    }

    fun renameTab(handle: Any, newTitle: String) {
        tabFor(handle)?.title = newTitle
        recordOpenTabs()
    }

    fun focusTui() {
        if (tabsBySessionId.isEmpty()) return
        val host = hostWithListeners() ?: return
        val tab = activeSession(host) ?: return
        selectTuiTab(host, tab)
    }

    fun focusEditor() {
        editorFocusRequest()
    }

    fun sendTextToActiveSession(text: String, submit: Boolean = false, focusSession: Boolean = true): Boolean {
        val host = hostWithListeners() ?: return false
        val tab = activeSession(host) ?: return false
        selectTuiTab(host, tab, requestFocus = focusSession)
        return sendTo(tab.session, text, submit)
    }

    fun sendFileReference(reference: String): Boolean {
        val host = hostWithListeners() ?: return false
        val tab = activeSession(host) ?: return false
        if (tab.layout.promptBoxVisible) {
            tab.promptBox.insertAtCaret(reference)
            tab.promptBox.requestFocus()
            return true
        }
        selectTuiTab(host, tab, requestFocus = false)
        return sendTo(tab.session, "$reference ", submit = false)
    }

    private fun sendTo(session: TerminalSession, text: String, submit: Boolean): Boolean {
        if (!session.sendText(text)) return false
        if (submit) session.sendKey(KeyEvent.VK_ENTER, 0, SUBMIT_KEY_CHAR)
        return true
    }

    fun isPromptBoxVisible(inToolWindow: Boolean = false): Boolean? {
        if (tabsNotClosing().isEmpty()) return null
        val host = resolveHost() ?: return null
        val tab = targetSession(host, inToolWindow) ?: return null
        return tab.layout.promptBoxVisible
    }

    fun setPromptBoxVisible(visible: Boolean, inToolWindow: Boolean = false) {
        val host = hostWithListeners() ?: return
        val tab = targetSession(host, inToolWindow) ?: return
        val promptBoxHadFocus = tab.promptBox.hasFocus()
        rememberAndShowPromptBox(tab, visible)
        if (visible) {
            tab.promptBox.requestFocus()
        } else if (promptBoxHadFocus) {
            tab.session.requestFocus()
        }
    }

    private fun targetSession(host: IdeToolWindowHost, inToolWindow: Boolean): OpenTab? =
        if (inToolWindow) activeToolWindowTab(host) else activeSession(host)

    private fun rememberAndShowPromptBox(tab: OpenTab, visible: Boolean) {
        promptBoxPreferences.rememberVisibility(tab.appName, visible)
        tabsSharingPromptBoxVisibilityWith(tab).forEach { showPromptBox(it, visible) }
    }

    private fun hidePromptBoxOf(sessionId: String) {
        val tab = tabsNotClosing()[sessionId] ?: return
        rememberAndShowPromptBox(tab, false)
    }

    fun togglePromptBox() {
        val visible = isPromptBoxVisible() ?: return
        setPromptBoxVisible(!visible)
    }

    fun focusPromptBox() {
        val host = hostWithListeners() ?: return
        val tab = activeSession(host) ?: return
        val theSessionIsShowing = tab.editorFile != null || host.isVisible()
        if (theSessionIsShowing && promptBoxHoldsTheFocus(tab)) {
            setPromptBoxVisible(false)
            return
        }
        tab.openedFromTui = isTuiFocused()
        selectTuiTab(host, tab, requestFocus = false)
        if (tab.layout.promptBoxVisible) tab.promptBox.requestFocus() else setPromptBoxVisible(true)
    }

    fun toggleFocus() {
        if (isTuiFocused()) focusEditor() else focusTui()
    }

    fun toggleToolWindow() {
        if (noSessionIsInTheToolWindow()) return
        val host = hostWithListeners() ?: return
        if (host.isVisible()) host.hide() else host.show()
    }

    fun toggleToolWindowAndFocus() {
        if (noSessionIsInTheToolWindow()) return
        val host = hostWithListeners() ?: return
        if (host.isVisible()) {
            host.hide()
            return
        }
        activeToolWindowTab(host)?.let { selectTuiTab(host, it) }
    }

    private fun noSessionIsInTheToolWindow(): Boolean = tabsBySessionId.values.none { it.handle != null }

    fun closeActiveTui() {
        val host = hostWithListeners() ?: return
        val tab = activeSession(host) ?: return
        closeTab(tab.sessionId, theUserClosedTheTab = true)
    }

    fun nextTuiTab() {
        selectRelativeTuiTab(1, requestFocus = true)
    }

    fun previousTuiTab() {
        selectRelativeTuiTab(-1, requestFocus = true)
    }

    fun nextTuiTabWithoutFocus() {
        selectRelativeTuiTab(1, requestFocus = false)
    }

    fun previousTuiTabWithoutFocus() {
        selectRelativeTuiTab(-1, requestFocus = false)
    }

    fun prefixCommandActions(): Map<Int, () -> Unit> {
        val state = TuiLauncherSettings.getInstance().state
        val builtInCommands = listOf(
            state.focusEditorKeyCode to ::focusEditor,
            state.closeTuiKeyCode to ::closeActiveTui,
            state.nextTuiKeyCode to ::nextTuiTab,
            state.previousTuiKeyCode to ::previousTuiTab,
            state.toggleToolWindowKeyCode to ::toggleToolWindow,
            state.nextTuiWithoutFocusKeyCode to ::nextTuiTabWithoutFocus,
            state.previousTuiWithoutFocusKeyCode to ::previousTuiTabWithoutFocus,
            state.focusPromptBoxKeyCode to ::focusPromptBox,
        )
        return buildMap {
            builtInCommands.forEach { (keyCode, command) -> keyCode?.let { putIfAbsent(it, command) } }
            state.tuiApps.forEach { app ->
                app.shortcutKeyCode?.let { keyCode ->
                    putIfAbsent(keyCode) { toggle(ACTION_ID_PREFIX + app.name, app.command, app.name) }
                }
            }
        }
    }

    private fun selectRelativeTuiTab(offset: Int, requestFocus: Boolean) {
        val host = hostWithListeners() ?: return
        invokeLater {
            val stripHandles = host.orderedHandles()
            if (stripHandles.size < 2) return@invokeLater
            val activeHandle = host.activeTab() ?: return@invokeLater
            val originIndex = stripHandles.indexOfFirst { it == activeHandle }
            if (originIndex < 0) return@invokeLater
            val selectableTabs = tabsNotClosing().values
            val promptBoxHadFocus = tabFor(activeHandle)?.let { promptBoxHoldsTheFocus(it) } == true
            val tab = (1 until stripHandles.size)
                .asSequence()
                .map { stripHandles[Math.floorMod(originIndex + offset * it, stripHandles.size)] }
                .mapNotNull { handle -> selectableTabs.firstOrNull { it.handle == handle } }
                .firstOrNull() ?: return@invokeLater
            selectTuiTab(
                host,
                tab,
                requestFocus = requestFocus,
                keepFocusInThePromptBox = promptBoxHadFocus,
            )
        }
    }

    private fun openNewTab(
        host: IdeToolWindowHost,
        sessionId: String,
        appName: String,
        command: String,
        title: String,
        intent: LaunchIntent,
        index: Int = APPEND_TAB,
        onOpened: ((OpenTab) -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        val disposable = Disposer.newCheckedDisposable("TUILaunch-$sessionId")
        pendingLaunchesBySessionId[sessionId] = PendingLaunch(appName, disposable)
        val pending = PendingTab(
            host = host,
            sessionId = sessionId,
            appName = appName,
            command = command,
            title = title,
            intent = intent,
            index = index,
            onOpened = onOpened,
            onFailed = onFailed,
            disposable = disposable,
            setup = agentSetupFor(command, intent),
        )
        if (pending.setup?.strategy?.launchWaitsForPreparation != true) {
            startTheSession(pending)
            return
        }
        val setup = pending.setup
        scope.launch {
            prepareInBackground(setup)
            onTheEventThread {
                val theLaunchIsStillOurs = pendingLaunchesBySessionId[sessionId]?.disposable === disposable
                if (theLaunchIsStillOurs && !disposable.isDisposed) {
                    startTheSession(pending)
                    return@onTheEventThread
                }
                if (theLaunchIsStillOurs) pendingLaunchesBySessionId.remove(sessionId)
                releaseTheServerOf(pending)
                pending.onFailed?.invoke()
            }
        }
    }

    private suspend fun prepareInBackground(setup: AgentSetup) {
        try {
            setup.strategy.prepareLaunchInBackground(setup.tab, setup.parsed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            thisLogger().warn("Could not prepare the ${setup.kind} launch of '${setup.parsed.command}'", failure)
        }
    }

    private fun onTheEventThread(work: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(work, ModalityState.nonModal())
    }

    private fun startTheSession(pending: PendingTab) {
        val host = pending.host
        val sessionId = pending.sessionId
        val appName = pending.appName
        val title = pending.title
        val intent = pending.intent
        val disposable = pending.disposable
        val agentLaunch = agentLaunchFor(pending.command, intent, pending.setup)
        val launchedAt = clock()
        sessionFactory.createAsync(
            parent = disposable,
            command = agentLaunch.command,
            environment = agentLaunch.environment,
            onCreated = { session ->
                if (pendingLaunchesBySessionId.remove(sessionId)?.disposable !== disposable || disposable.isDisposed) {
                    releaseTheServerOf(pending)
                    return@createAsync
                }
                val promptBox = PromptBox(
                    project = project,
                    parentDisposable = disposable,
                    sender = promptBoxSenderFor(sessionId, session),
                    focusSession = { session.requestFocus() },
                    spendKeyPress = session::spendKeyPress,
                    existingPromptDocument = existingPromptDocument,
                    screenText = screenTextOf(session, AgentCommand.parse(pending.command).kind),
                )
                val layout = newTabLayout(host, appName, session, promptBox)
                val handle = host.addTab(layout.component, title, disposable, pending.index)
                val tab = OpenTab(
                    sessionId = sessionId,
                    appName = appName,
                    title = title,
                    session = session,
                    layout = layout,
                    promptBox = promptBox,
                    place = SessionPlace.InToolWindow(handle),
                    disposable = disposable,
                    openedFromTui = isTuiFocused(),
                    tabUuid = intent.tabUuid,
                    agentSessionId = agentLaunch.agentSessionId,
                    agentCliKind = agentLaunch.cliKind,
                    launchedAt = launchedAt,
                    restoredFromRecord = intent is LaunchIntent.Restore,
                    commandWasDecorated = agentLaunch.commandWasDecorated,
                    agentSessionWasRequested = agentLaunch.agentSessionWasRequested,
                    agentStrategy = agentLaunch.strategy,
                )
                tabsBySessionId[sessionId] = tab
                layout.onPromptBoxPercentChanged = { percent -> onPromptBoxPercentChanged(tab, percent) }
                session.onTerminated { onSessionTerminated(host, tab) }
                recordOpenTabs()
                val opened = pending.onOpened
                if (opened != null) opened(tab) else selectTuiTab(host, tab)
            },
            onFailed = { throwable ->
                pendingLaunchesBySessionId.remove(sessionId)
                releaseTheServerOf(pending)
                Disposer.dispose(disposable)
                thisLogger().warn("Failed to launch TUI app: ${agentLaunch.command}", throwable)
                pending.onFailed?.invoke()
            },
        )
    }

    private fun releaseTheServerOf(pending: PendingTab) {
        val setup = pending.setup ?: return
        setup.strategy.tabClosed(setup.tab)
    }

    private fun agentSetupFor(command: String, intent: LaunchIntent): AgentSetup? {
        if (!agentSessionsAreResumed()) {
            return unmanaged(command, "restoring agent sessions is turned off in the settings")
        }
        val projectPath = project.basePath ?: return unmanaged(command, "the project has no base path")
        val parsed = AgentCommand.parse(command)
        val kind = parsed.kind
        if (kind == null) {
            return unmanaged(command, "none of ${parsed.tokens} names an agent CLI this plugin knows")
        }
        if (!parsed.isManageable) return unmanaged(command, whyItCannotBeManaged(parsed))
        val tab = TabIdentity(intent.tabUuid, projectPath, project.locationHash)
        val strategy = agentSessionStrategies(kind, agentSessionEnvironment(), !parsed.bringsItsOwnHookFlag)
        strategy.prepareLaunch(tab)
        return AgentSetup(kind, parsed, tab, strategy)
    }

    private fun agentLaunchFor(command: String, intent: LaunchIntent, setup: AgentSetup?): AgentLaunch {
        val plainLaunch = AgentLaunch(
            command = command,
            commandWasDecorated = false,
            agentSessionWasRequested = false,
            agentSessionId = null,
            cliKind = null,
            strategy = null,
        )
        if (setup == null) return plainLaunch
        val (kind, parsed, tab, strategy) = setup
        val arguments = when (intent) {
            is LaunchIntent.Fresh -> strategy.launchArguments(tab)
            is LaunchIntent.Restore -> strategy.restoreArguments(tab)
        }
        val environment = when (intent) {
            is LaunchIntent.Fresh -> strategy.launchEnvironment(tab)
            is LaunchIntent.Restore -> strategy.restoreEnvironment(tab)
        }
        if (arguments.isEmpty() && environment.isEmpty()) {
            strategy.tabClosed(tab)
            return plainLaunch.alsoLog(command, "the $kind strategy had nothing to add")
        }
        val decorated = parsed.withArguments(arguments)
        thisLogger().info("TUILaunch runs $kind as: $decorated with ${environment.keys}")
        return AgentLaunch(
            command = decorated,
            environment = environment,
            commandWasDecorated = true,
            agentSessionWasRequested = arguments.any { it in RESUME_ARGUMENTS },
            agentSessionId = agentSessionIdFor(kind, strategy, tab, intent),
            cliKind = kind.name,
            strategy = strategy,
        )
    }

    private fun unmanaged(command: String, reason: String): AgentSetup? {
        thisLogger().info("TUILaunch runs '$command' without session management because $reason")
        return null
    }

    private fun AgentLaunch.alsoLog(command: String, reason: String): AgentLaunch {
        thisLogger().info("TUILaunch runs '$command' without session management because $reason")
        return this
    }

    private fun whyItCannotBeManaged(parsed: AgentCommand): String = when {
        parsed.userSelectsASession -> "the command already picks a session itself"
        parsed.chainsOtherCommands -> "the command chains other commands"
        parsed.setsASessionVariableItself -> "the command already sets a session variable itself"
        else -> "the command cannot be managed"
    }

    private fun agentSessionsAreResumed(): Boolean {
        val state = TuiLauncherSettings.getInstance().state
        return state.restoreOpenTabs && state.restoreAgentSessions
    }

    private fun agentSessionIdFor(
        kind: AgentCliKind,
        strategy: AgentSessionStrategy,
        tab: TabIdentity,
        intent: LaunchIntent,
    ): String? = when (kind) {
        AgentCliKind.CLAUDE -> claudeSessionId(strategy, tab, intent)
        AgentCliKind.CODEX -> codexSessionIdOnRestore(strategy, tab, intent)
        AgentCliKind.OPENCODE -> openCodeSessionIdOnRestore(strategy, tab, intent)
        AgentCliKind.OMP -> ompSessionIdOnRestore(strategy, tab, intent)
    }

    private fun claudeSessionId(
        strategy: AgentSessionStrategy,
        tab: TabIdentity,
        intent: LaunchIntent,
    ): String? {
        if (intent !is LaunchIntent.Restore) return tab.tabUuid
        val claude = strategy as? ClaudeSessionStrategy ?: return tab.tabUuid
        return claude.readSessionId(claude.stateFile(tab)) ?: tab.tabUuid
    }

    private fun codexSessionIdOnRestore(
        strategy: AgentSessionStrategy,
        tab: TabIdentity,
        intent: LaunchIntent,
    ): String? {
        if (intent !is LaunchIntent.Restore) return null
        val codex = strategy as? CodexSessionStrategy ?: return null
        return codex.readSessionId(codex.stateFile(tab))
    }

    private fun openCodeSessionIdOnRestore(
        strategy: AgentSessionStrategy,
        tab: TabIdentity,
        intent: LaunchIntent,
    ): String? {
        if (intent !is LaunchIntent.Restore) return null
        val openCode = strategy as? OpenCodeSessionStrategy ?: return null
        return openCode.readSessionId(openCode.stateFile(tab))
    }

    private fun ompSessionIdOnRestore(
        strategy: AgentSessionStrategy,
        tab: TabIdentity,
        intent: LaunchIntent,
    ): String? {
        if (intent !is LaunchIntent.Restore) return null
        val omp = strategy as? OmpSessionStrategy ?: return null
        return omp.readSessionId(omp.activeSessionFile(tab))
    }

    private fun onSessionTerminated(host: IdeToolWindowHost, tab: OpenTab) {
        if (tab.sessionId in closingSessions) return
        if (tabsBySessionId[tab.sessionId] !== tab) return
        if (aResumedAgentSessionDiedOnStartup(tab)) {
            relaunchWithoutTheAgentSession(host, tab)
        } else {
            closeTab(tab.sessionId)
        }
    }

    private fun aResumedAgentSessionDiedOnStartup(tab: OpenTab): Boolean =
        tab.restoredFromRecord &&
            tab.agentSessionWasRequested &&
            clock() - tab.launchedAt < EARLY_EXIT_RELAUNCH_WINDOW_MILLIS

    private fun relaunchWithoutTheAgentSession(host: IdeToolWindowHost, tab: OpenTab) {
        val config = configFor(tab.appName)
        if (config == null) {
            closeTab(tab.sessionId)
            return
        }
        val handle = tab.handle
        val position = handle?.let { host.orderedHandles().indexOf(it) } ?: APPEND_TAB
        val theTabWasSelected = handle != null && host.activeTab() == handle
        thisLogger().info(
            "Reopened TUI tab '${tab.title}' ended right after launch; starting it again as a new tab",
        )
        cleanUpAgentSession(tab)
        closeTab(tab.sessionId)
        openNewTab(
            host = host,
            sessionId = newSessionId(tab.appName),
            appName = tab.appName,
            command = config.command,
            title = tab.title,
            intent = LaunchIntent.Fresh(newTabUuid()),
            index = position,
            onOpened = { relaunched -> if (theTabWasSelected) relaunched.handle?.let(host::selectTab) },
        )
    }

    private fun cleanUpAgentSession(tab: OpenTab) {
        if (!tab.commandWasDecorated) return
        val strategy = tab.agentStrategy ?: return
        val identity = identityOf(tab) ?: return
        scope.launch { strategy.cleanUp(identity) }
    }

    private fun identityOf(tab: OpenTab): TabIdentity? {
        val projectPath = project.basePath ?: return null
        return TabIdentity(tab.tabUuid, projectPath, project.locationHash)
    }

    private fun screenTextOf(session: TerminalSession, kind: AgentCliKind?): () -> String? = {
        session.screen()?.let { cleanTerminalScreen(it, kind) }?.takeIf { it.isNotEmpty() }
    }

    private fun promptBoxSenderFor(sessionId: String, session: TerminalSession): PromptBoxSender = PromptBoxSender(
        project = project,
        sendToSession = { text, submit -> sendTo(session, text, submit) },
        focusSession = { session.requestFocus() },
        promptDocument = promptDocument,
        hideBox = { hidePromptBoxOf(sessionId) },
    )

    private fun newTabLayout(
        host: IdeToolWindowHost,
        appName: String,
        session: TerminalSession,
        promptBox: PromptBox,
    ): TuiTabLayout {
        val promptBoxVisible = promptBoxPreferences.visibilityFor(appName)
        if (promptBoxVisible) showPromptBoxEditor(promptBox)
        return TuiTabLayout(
            terminal = session.component,
            promptBox = promptBox.component,
            dockedHorizontally = host.isDockedHorizontally(),
            promptBoxPercent = promptBoxPreferences.percentFor(appName),
            promptBoxVisible = promptBoxVisible,
        )
    }

    private fun applySavedPromptBoxState(tab: OpenTab) {
        showPromptBox(tab, promptBoxPreferences.visibilityFor(tab.appName))
        tab.layout.promptBoxPercent = promptBoxPreferences.percentFor(tab.appName)
    }

    private fun showPromptBox(tab: OpenTab, visible: Boolean) {
        if (visible) showPromptBoxEditor(tab.promptBox)
        tab.layout.promptBoxVisible = visible
    }

    private fun showPromptBoxEditor(promptBox: PromptBox) {
        promptBox.installEditor()
        promptBoxCompletions.promptBoxShown()
    }

    private fun onPromptBoxPercentChanged(tab: OpenTab, percent: Int) {
        promptBoxPreferences.rememberPercent(tab.appName, percent)
        tabsSharingPromptBoxSizeWith(tab)
            .filter { it !== tab }
            .forEach { it.layout.promptBoxPercent = percent }
    }

    private fun tabsSharingPromptBoxVisibilityWith(tab: OpenTab): List<OpenTab> =
        tabsSharingPreferencesWith(tab, promptBoxPreferences.visibilityIsPerApp())

    private fun tabsSharingPromptBoxSizeWith(tab: OpenTab): List<OpenTab> =
        tabsSharingPreferencesWith(tab, promptBoxPreferences.sizeIsPerApp())

    private fun tabsSharingPreferencesWith(tab: OpenTab, perApp: Boolean): List<OpenTab> {
        val openTabs = tabsNotClosing().values
        return if (perApp) openTabs.filter { it.appName == tab.appName } else openTabs.toList()
    }

    private fun selectTuiTab(
        host: IdeToolWindowHost,
        tab: OpenTab,
        requestFocus: Boolean = true,
        keepFocusInThePromptBox: Boolean = false,
    ) {
        if (requestFocus) tab.openedFromTui = isTuiFocused()
        lastFocusedSessionId = tab.sessionId
        val handle = tab.handle
        if (handle == null) {
            tab.editorFile?.let(editorHost::select)
        } else {
            val selectionWillChange = host.activeTab() != handle
            if (selectionWillChange) recordActiveTabSize(host)
            host.show()
            host.selectTab(handle)
            if (!selectionWillChange) applySavedSize(host, tab.appName)
        }
        if (requestFocus) focusTabContent(tab, keepFocusInThePromptBox)
    }

    private fun focusTabContent(tab: OpenTab, keepFocusInThePromptBox: Boolean) {
        val thePromptBoxCanTakeTheFocus = keepFocusInThePromptBox && tab.layout.promptBoxVisible
        if (thePromptBoxCanTakeTheFocus) tab.promptBox.requestFocus() else tab.session.requestFocus()
    }

    private fun promptBoxHoldsTheFocus(tab: OpenTab): Boolean =
        tab.layout.promptBoxVisible && tab.promptBox.hasFocus()

    private fun isTuiFocused(): Boolean =
        activeToolWindowIdProvider() == TUI_TOOL_WINDOW_ID || anEditorSessionHoldsTheFocus()

    private fun anEditorSessionHoldsTheFocus(): Boolean {
        val owner = focusOwnerProvider() ?: return false
        return sessionHolding(owner)?.editorFile != null
    }

    private fun recordActiveTabSize(host: IdeToolWindowHost) {
        if (applyingSize) return
        val activeHandle = host.activeTab() ?: return
        val activeTab = tabFor(activeHandle) ?: return
        val size = host.currentSize() ?: return
        storeWindowSize(host, activeTab.appName, size)
    }

    private fun storeWindowSize(host: IdeToolWindowHost, appName: String, size: ToolWindowSize) {
        configFor(appName)?.let { config ->
            config.windowWidth = size.width
            config.windowHeight = size.height
            config.windowSizeAxis = host.sizeAxis().name
        }
    }

    private fun savedSize(host: IdeToolWindowHost, appName: String): ToolWindowSize? {
        val config = configFor(appName) ?: return null
        if (recordedSizeAxis(config) != host.sizeAxis()) return null
        val width = config.windowWidth ?: return null
        val height = config.windowHeight ?: return null
        if (width <= 0 || height <= 0) return null
        return ToolWindowSize(width, height)
    }

    private fun recordedSizeAxis(config: TuiAppConfig): ToolWindowSizeAxis =
        ToolWindowSizeAxis.entries.firstOrNull { it.name == config.windowSizeAxis }
            ?: ToolWindowSizeAxis.HEIGHT

    private fun configFor(appName: String): TuiAppConfig? =
        TuiLauncherSettings.getInstance().state.tuiApps.firstOrNull { it.name == appName }

    private fun closeTab(sessionId: String, theUserClosedTheTab: Boolean = false) {
        val tab = tabsBySessionId[sessionId] ?: return
        if (!closingSessions.add(sessionId)) return
        if (theUserClosedTheTab) cleanUpAgentSession(tab)

        val handle = when (val place = tab.place) {
            is SessionPlace.InEditor -> {
                closeEditorSession(tab, place.file)
                return
            }
            is SessionPlace.InToolWindow -> place.handle
        }

        val host = resolveHost()
        if (host == null) {
            forgetTab(sessionId)
            Disposer.dispose(tab.disposable)
            return
        }

        val closingActiveTab = host.activeTab() == handle
        if (closingActiveTab) {
            host.currentSize()?.let { storeWindowSize(host, tab.appName, it) }
            if (!tab.openedFromTui) focusEditor()
        }

        invokeLater {
            forgetTab(sessionId)
            host.removeTab(handle)
            if (windowRevealedByLaunch && host.isPinned()) {
                host.hide()
            }
        }
        tab.layout.promptBoxVisible = false
        Disposer.dispose(tab.disposable)
    }

    private fun closeEditorSession(tab: OpenTab, file: TuiSessionFile) {
        forgetTab(tab.sessionId)
        editorHost.close(file)
        tab.layout.promptBoxVisible = false
        Disposer.dispose(tab.disposable)
        recordOpenTabs()
    }

    fun handleFor(content: Content): Any? = resolveHost()?.handleFor(content)

    fun canMoveToEditor(handle: Any): Boolean = tabFor(handle)?.takeIf { it.sessionId !in closingSessions } != null

    fun moveToEditor(handle: Any, window: EditorWindow? = null) {
        val host = resolveHost() ?: return
        val tab = tabFor(handle) ?: return
        if (tab.sessionId in closingSessions) return
        ensureEditorListeners()
        if (host.activeTab() == handle) host.currentSize()?.let { storeWindowSize(host, tab.appName, it) }
        val file = TuiSessionFile(
            sessionId = tab.sessionId,
            title = tab.title,
            component = tab.layout.component,
            focusTarget = { tab.session.preferredFocusableComponent },
        )
        Disposer.register(this, tab.disposable)
        tab.place = SessionPlace.InEditor(file)
        sessionIdsRemovedForDrag.remove(tab.sessionId)
        if (host.isTabAttachedToToolWindow(handle)) host.removeTab(handle)
        if (noSessionIsInTheToolWindow()) host.hide()
        tab.layout.setDockedHorizontally(false)
        lastFocusedSessionId = tab.sessionId
        editorHost.open(file, window)
        recordOpenTabs()
    }

    fun moveToToolWindow(file: TuiSessionFile) {
        val host = hostWithListeners() ?: return
        val tab = tabIn(file) ?: return
        if (tab.sessionId in closingSessions) return
        editorHost.close(file)
        tab.layout.setDockedHorizontally(host.isDockedHorizontally())
        val handle = host.addTab(tab.layout.component, tab.title, tab.disposable)
        tab.place = SessionPlace.InToolWindow(handle)
        recordOpenTabs()
        selectTuiTab(host, tab)
    }

    fun editorTabClosed(file: TuiSessionFile) {
        ApplicationManager.getApplication().invokeLater(
            {
                if (projectIsClosing || editorHost.isOpen(file)) return@invokeLater
                val tab = tabIn(file) ?: return@invokeLater
                closeTab(tab.sessionId, theUserClosedTheTab = true)
            },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    fun renameEditorSession(file: TuiSessionFile, newTitle: String) {
        val tab = tabIn(file) ?: return
        tab.title = newTitle
        file.retitle(newTitle)
        editorHost.titleChanged(file)
        recordOpenTabs()
    }

    fun sessionTitleOf(file: TuiSessionFile): String? = tabIn(file)?.title

    override fun dispose() = Unit

    private fun onTabRemoving(host: IdeToolWindowHost, handle: Any) {
        activeSessionIdBeingRemoved = null
        if (host.isTabRemovedForDrag(handle)) return
        val tab = tabFor(handle) ?: return
        if (host.activeTab() != tab.handle) return
        activeSessionIdBeingRemoved = tab.sessionId
        host.currentSize()?.let { storeWindowSize(host, tab.appName, it) }
    }

    private fun onTabRemoved(host: IdeToolWindowHost, handle: Any) {
        if (!host.isTabRemovedForDrag(handle)) {
            recordOpenTabs()
            forgetRemovedTab(handle)
            return
        }
        tabFor(handle)?.let { sessionIdsRemovedForDrag.add(it.sessionId) }
    }

    private fun onTabAdded(handle: Any) {
        tabFor(handle)?.let { sessionIdsRemovedForDrag.remove(it.sessionId) }
        recordOpenTabs()
    }

    private fun closeTabsLostToADrag(host: IdeToolWindowHost) {
        if (sessionIdsRemovedForDrag.isEmpty()) return
        var forgotATab = false
        sessionIdsRemovedForDrag.toList().forEach { sessionId ->
            val tab = tabsBySessionId[sessionId]
            val handle = tab?.handle
            if (handle != null && host.isTabRemovedForDrag(handle)) return@forEach
            sessionIdsRemovedForDrag.remove(sessionId)
            if (tab == null || handle == null || host.isTabAttachedToToolWindow(handle)) return@forEach
            forgetTab(sessionId)
            Disposer.dispose(tab.disposable)
            forgotATab = true
        }
        if (forgotATab) recordOpenTabs()
    }

    private fun forgetRemovedTab(handle: Any) {
        val tab = tabFor(handle) ?: return
        val removedTabWasActive = activeSessionIdBeingRemoved == tab.sessionId
        activeSessionIdBeingRemoved = null
        cleanUpAgentSession(tab)
        forgetTab(tab.sessionId)
        Disposer.dispose(tab.disposable)
        val lastTuiClosedAfterComingFromEditor =
            removedTabWasActive && !tab.openedFromTui && tabsNotClosing().isEmpty()
        if (lastTuiClosedAfterComingFromEditor) focusEditor()
    }

    private fun forgetTab(sessionId: String) {
        val tab = tabsBySessionId.remove(sessionId)
        closingSessions.remove(sessionId)
        sessionIdsRemovedForDrag.remove(sessionId)
        releaseAgentResources(tab ?: return)
    }

    private fun releaseAgentResources(tab: OpenTab) {
        val strategy = tab.agentStrategy ?: return
        val identity = identityOf(tab) ?: return
        strategy.tabClosed(identity)
    }
}

class ReleasePromptBoxEditorsOnProjectClose : ProjectCloseListener {

    override fun projectClosing(project: Project) {
        project.getServiceIfCreated(TuiAppLaunchService::class.java)?.projectClosing()
    }
}
