package com.github.atm1020.tuilaunch.services

import com.github.atm1020.tuilaunch.model.ACTION_ID_PREFIX
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.terminal.JediTermSessionFactory
import com.github.atm1020.tuilaunch.terminal.TerminalSession
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSize
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSizeAxis
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.event.KeyEvent

const val TUI_TOOL_WINDOW_ID = "TUILaunch"

private const val SUBMIT_KEY_CHAR = '\r'

internal fun uniqueSessionTitle(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var suffix = 1
    while ("$base $suffix" in taken) suffix++
    return "$base $suffix"
}

@Service(Service.Level.PROJECT)
class TuiAppLaunchService(private val project: Project) {

    var sessionFactory: TerminalSessionFactory = JediTermSessionFactory(
        project = project,
        prefixCommandActions = { prefixCommandActions() },
        onNextTab = { nextTuiTab() },
        onPreviousTab = { previousTuiTab() },
    )
    var host: IdeToolWindowHost? = null
    var activeToolWindowIdProvider: () -> String? = {
        ToolWindowManager.getInstance(project).activeToolWindowId
    }
    var editorFocusRequest: () -> Unit = {
        val component = FileEditorManager.getInstance(project).selectedTextEditor?.contentComponent
        if (component != null) IdeFocusManager.getInstance(project).requestFocus(component, true)
    }
    private var hostListenersInstalled = false
    private var windowRevealedByLaunch = false
    private var applyingSize = false
    private var restoringTabs = false
    private var restoreAttempted = false

    private data class OpenTab(
        val sessionId: String,
        val appName: String,
        var title: String,
        val session: TerminalSession,
        val handle: Any,
        val disposable: Disposable,
        var openedFromTui: Boolean,
    )

    private data class PendingLaunch(val appName: String, val disposable: Disposable)

    private val tabsBySessionId = mutableMapOf<String, OpenTab>()
    private val pendingLaunchesBySessionId = mutableMapOf<String, PendingLaunch>()

    private val closingSessions = mutableSetOf<String>()
    private val sessionIdsRemovedForDrag = mutableSetOf<String>()
    private var sessionSequence = 0
    private var activeSessionIdBeingRemoved: String? = null

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
    }

    private fun tabFor(handle: Any): OpenTab? = tabsBySessionId.values.firstOrNull { it.handle == handle }

    private fun activeOrLastOpenTab(host: IdeToolWindowHost): OpenTab? {
        val openTabs = tabsNotClosing().values
        val activeHandle = host.activeTab()
        return openTabs.firstOrNull { it.handle == activeHandle } ?: openTabs.lastOrNull()
    }

    private fun onTabSelected(host: IdeToolWindowHost, handle: Any) {
        val tab = tabFor(handle) ?: return
        applySavedSize(host, tab.appName)
        recordOpenTabs()
    }

    private fun recordOpenTabs() {
        if (restoringTabs) return
        if (!TuiLauncherSettings.getInstance().state.restoreOpenTabs) return
        val host = host ?: return
        val activeHandle = host.activeTab()
        val records = host.orderedHandles().mapNotNull { handle ->
            val tab = tabFor(handle) ?: return@mapNotNull null
            if (tab.sessionId in closingSessions) return@mapNotNull null
            TuiSessionRecord(tab.appName, tab.title, handle == activeHandle)
        }
        TuiOpenTabsService.getInstance(project).replaceTabs(records)
    }

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
        openNewTab(host, sessionId = newSessionId(appName), appName = appName, command = command, title = title)
    }

    fun launchNew(appName: String, command: String) {
        val host = hostWithListeners() ?: return
        val title = uniqueSessionTitle(appName, tabsBySessionId.values.mapTo(mutableSetOf()) { it.title })
        windowRevealedByLaunch = !host.isVisible()
        openNewTab(host, sessionId = newSessionId(appName), appName = appName, command = command, title = title)
    }

    private fun newSessionId(appName: String): String = "$appName#${sessionSequence++}"

    fun restoreSavedTabs() {
        if (!TuiLauncherSettings.getInstance().state.restoreOpenTabs) return
        if (restoreAttempted) return
        restoreAttempted = true
        val saved = TuiOpenTabsService.getInstance(project).state.tabs.toList()
        if (saved.isEmpty()) return
        val host = hostWithListeners() ?: return
        restoringTabs = true
        restoreTabAt(host, saved, 0, null, null)
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
            onOpened = { tab ->
                restoreTabAt(host, saved, index + 1, if (record.selected) tab else tabToSelect, tab)
            },
            onFailed = { restoreTabAt(host, saved, index + 1, tabToSelect, lastRestoredTab) },
        )
    }

    private fun finishRestore(host: IdeToolWindowHost, tabToSelect: OpenTab?) {
        restoringTabs = false
        if (tabToSelect != null) host.selectTab(tabToSelect.handle)
        recordOpenTabs()
    }

    fun renameTab(handle: Any, newTitle: String) {
        tabFor(handle)?.title = newTitle
        recordOpenTabs()
    }

    fun focusTui() {
        if (tabsBySessionId.isEmpty()) return
        val host = hostWithListeners() ?: return
        val tab = activeOrLastOpenTab(host) ?: return
        selectTuiTab(host, tab)
    }

    fun focusEditor() {
        editorFocusRequest()
    }

    fun sendTextToActiveSession(text: String, submit: Boolean = false, focusSession: Boolean = true): Boolean {
        val host = hostWithListeners() ?: return false
        val tab = activeOrLastOpenTab(host) ?: return false
        selectTuiTab(host, tab, requestFocus = focusSession)
        if (!tab.session.sendText(text)) return false
        if (submit) tab.session.sendKey(KeyEvent.VK_ENTER, 0, SUBMIT_KEY_CHAR)
        return true
    }

    fun toggleFocus() {
        if (isTuiFocused()) focusEditor() else focusTui()
    }

    fun toggleToolWindow() {
        if (tabsBySessionId.isEmpty()) return
        val host = hostWithListeners() ?: return
        if (host.isVisible()) host.hide() else host.show()
    }

    fun toggleToolWindowAndFocus() {
        if (tabsBySessionId.isEmpty()) return
        val host = hostWithListeners() ?: return
        if (host.isVisible()) host.hide() else focusTui()
    }

    fun closeActiveTui() {
        val host = hostWithListeners() ?: return
        val tab = activeOrLastOpenTab(host) ?: return
        closeTab(tab.sessionId)
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
            val activeHandle = host.activeTab()
            val originIndex = stripHandles.indexOfFirst { it == activeHandle }
            if (originIndex < 0) return@invokeLater
            val selectableTabs = tabsNotClosing().values
            val tab = (1 until stripHandles.size)
                .asSequence()
                .map { stripHandles[Math.floorMod(originIndex + offset * it, stripHandles.size)] }
                .mapNotNull { handle -> selectableTabs.firstOrNull { it.handle == handle } }
                .firstOrNull() ?: return@invokeLater
            selectTuiTab(host, tab, requestFocus = requestFocus)
        }
    }

    private fun openNewTab(
        host: IdeToolWindowHost,
        sessionId: String,
        appName: String,
        command: String,
        title: String,
        onOpened: ((OpenTab) -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        val disposable = Disposer.newCheckedDisposable("TUILaunch-$sessionId")
        pendingLaunchesBySessionId[sessionId] = PendingLaunch(appName, disposable)
        sessionFactory.createAsync(
            parent = disposable,
            command = command,
            onCreated = { session ->
                if (pendingLaunchesBySessionId.remove(sessionId)?.disposable !== disposable || disposable.isDisposed) {
                    return@createAsync
                }
                val handle = host.addTab(session.component, title, disposable)
                val tab = OpenTab(
                    sessionId = sessionId,
                    appName = appName,
                    title = title,
                    session = session,
                    handle = handle,
                    disposable = disposable,
                    openedFromTui = isTuiFocused(),
                )
                tabsBySessionId[sessionId] = tab
                session.onTerminated { closeTab(sessionId) }
                recordOpenTabs()
                if (onOpened != null) onOpened(tab) else selectTuiTab(host, tab)
            },
            onFailed = { throwable ->
                pendingLaunchesBySessionId.remove(sessionId)
                Disposer.dispose(disposable)
                thisLogger().warn("Failed to launch TUI app: $command", throwable)
                onFailed?.invoke()
            },
        )
    }

    private fun selectTuiTab(
        host: IdeToolWindowHost,
        tab: OpenTab,
        requestFocus: Boolean = true,
    ) {
        val selectionWillChange = host.activeTab() != tab.handle
        if (selectionWillChange) recordActiveTabSize(host)
        if (requestFocus) tab.openedFromTui = isTuiFocused()
        host.show()
        host.selectTab(tab.handle)
        if (!selectionWillChange) applySavedSize(host, tab.appName)
        if (requestFocus) tab.session.requestFocus()
    }

    private fun isTuiFocused(): Boolean = activeToolWindowIdProvider() == TUI_TOOL_WINDOW_ID

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

    private fun closeTab(sessionId: String) {
        val tab = tabsBySessionId[sessionId] ?: return
        if (!closingSessions.add(sessionId)) return

        val host = resolveHost()
        if (host == null) {
            forgetTab(sessionId)
            Disposer.dispose(tab.disposable)
            return
        }

        val closingActiveTab = host.activeTab() == tab.handle
        if (closingActiveTab) {
            host.currentSize()?.let { storeWindowSize(host, tab.appName, it) }
            if (!tab.openedFromTui) focusEditor()
        }

        invokeLater {
            forgetTab(sessionId)
            host.removeTab(tab.handle)
            if (windowRevealedByLaunch && host.isPinned()) {
                host.hide()
            }
        }
        Disposer.dispose(tab.disposable)
    }

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
            if (tab != null && host.isTabRemovedForDrag(tab.handle)) return@forEach
            sessionIdsRemovedForDrag.remove(sessionId)
            if (tab == null || host.isTabAttachedToToolWindow(tab.handle)) return@forEach
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
        forgetTab(tab.sessionId)
        Disposer.dispose(tab.disposable)
        val lastTuiClosedAfterComingFromEditor =
            removedTabWasActive && !tab.openedFromTui && tabsNotClosing().isEmpty()
        if (lastTuiClosedAfterComingFromEditor) focusEditor()
    }

    private fun forgetTab(sessionId: String) {
        tabsBySessionId.remove(sessionId)
        closingSessions.remove(sessionId)
        sessionIdsRemovedForDrag.remove(sessionId)
    }
}
