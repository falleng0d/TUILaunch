package com.github.atm1020.tuilaunch.services

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.terminal.JediTermSessionFactory
import com.github.atm1020.tuilaunch.terminal.TerminalSession
import com.github.atm1020.tuilaunch.terminal.TerminalSessionFactory
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSize
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager

const val TUI_TOOL_WINDOW_ID = "TUILaunch"

private const val ACTION_ID_PREFIX = "TUILauncher."

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
    private var sizeListenerInstalled = false
    private var windowRevealedByLaunch = false
    private var applyingSize = false

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
    private var sessionSequence = 0

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

    private fun ensureSizeListener(host: IdeToolWindowHost) {
        if (sizeListenerInstalled) return
        sizeListenerInstalled = true
        host.onSizeChanged { recordActiveTabSize(host) }
        host.onTabSelected { handle -> onTabSelected(host, handle) }
        host.onTabRemoved { handle -> forgetRemovedTab(handle) }
    }

    private fun onTabSelected(host: IdeToolWindowHost, handle: Any) {
        val tab = tabsBySessionId.values.firstOrNull { it.handle == handle } ?: return
        applySavedSize(host, tab.appName)
    }

    private fun applySavedSize(host: IdeToolWindowHost, appName: String) {
        val size = savedSize(appName) ?: return
        applyingSize = true
        try {
            host.applySize(size)
        } finally {
            invokeLater { applyingSize = false }
        }
    }

    fun toggle(actionId: String, command: String, title: String) {
        val host = resolveHost() ?: return
        ensureSizeListener(host)
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
        val host = resolveHost() ?: return
        ensureSizeListener(host)
        val title = uniqueSessionTitle(appName, tabsBySessionId.values.mapTo(mutableSetOf()) { it.title })
        windowRevealedByLaunch = !host.isVisible()
        openNewTab(host, sessionId = newSessionId(appName), appName = appName, command = command, title = title)
    }

    private fun newSessionId(appName: String): String = "$appName#${sessionSequence++}"

    fun renameTab(handle: Any, newTitle: String) {
        tabsBySessionId.values.firstOrNull { it.handle == handle }?.title = newTitle
    }

    fun focusTui() {
        if (tabsBySessionId.isEmpty()) return
        val host = resolveHost() ?: return
        ensureSizeListener(host)
        val activeHandle = host.activeTab()
        val live = tabsNotClosing().values.toList()
        val tab = live.firstOrNull { it.handle == activeHandle } ?: live.lastOrNull() ?: return
        selectTuiTab(host, tab)
    }

    fun focusEditor() {
        editorFocusRequest()
    }

    fun toggleFocus() {
        val onTui = isTuiFocused()
        if (onTui) focusEditor() else focusTui()
    }

    fun toggleToolWindow() {
        if (tabsBySessionId.isEmpty()) return
        val host = resolveHost() ?: return
        ensureSizeListener(host)
        if (host.isVisible()) {
            host.hide()
        } else {
            host.show()
        }
    }

    fun toggleToolWindowAndFocus() {
        if (tabsBySessionId.isEmpty()) return
        val host = resolveHost() ?: return
        ensureSizeListener(host)
        if (host.isVisible()) {
            host.hide()
        } else {
            focusTui()
        }
    }

    fun closeActiveTui() {
        val host = resolveHost() ?: return
        ensureSizeListener(host)
        val activeHandle = host.activeTab()
        val closableTabs = tabsNotClosing()
        val sessionId = closableTabs.entries.firstOrNull { it.value.handle == activeHandle }?.key
            ?: closableTabs.entries.lastOrNull()?.key
            ?: return
        closeTab(sessionId)
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
        return buildMap {
            state.focusEditorKeyCode?.let { putIfAbsent(it) { focusEditor() } }
            state.closeTuiKeyCode?.let { putIfAbsent(it) { closeActiveTui() } }
            state.nextTuiKeyCode?.let { putIfAbsent(it) { nextTuiTab() } }
            state.previousTuiKeyCode?.let { putIfAbsent(it) { previousTuiTab() } }
            state.toggleToolWindowKeyCode?.let { putIfAbsent(it) { toggleToolWindow() } }
            state.nextTuiWithoutFocusKeyCode?.let { putIfAbsent(it) { nextTuiTabWithoutFocus() } }
            state.previousTuiWithoutFocusKeyCode?.let { putIfAbsent(it) { previousTuiTabWithoutFocus() } }
            state.tuiApps.forEach { app ->
                app.shortcutKeyCode?.let { keyCode ->
                    putIfAbsent(keyCode) { toggle("TUILauncher.${app.name}", app.command, app.name) }
                }
            }
        }
    }

    private fun selectRelativeTuiTab(offset: Int, requestFocus: Boolean) {
        if (tabsBySessionId.size < 2) return
        val host = resolveHost() ?: return
        ensureSizeListener(host)
        invokeLater {
            val selectableTabs = tabsNotClosing().values
            val orderedTabs = host.orderedHandles().mapNotNull { handle ->
                selectableTabs.firstOrNull { it.handle == handle }
            }
            if (orderedTabs.size < 2) return@invokeLater
            val activeHandle = host.activeTab()
            val originIndex = orderedTabs.indexOfFirst { it.handle == activeHandle }.coerceAtLeast(0)
            val tab = orderedTabs[Math.floorMod(originIndex + offset, orderedTabs.size)]
            selectTuiTab(host, tab, requestFocus = requestFocus)
        }
    }

    private fun openNewTab(
        host: IdeToolWindowHost,
        sessionId: String,
        appName: String,
        command: String,
        title: String,
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
                tabsBySessionId[sessionId] = OpenTab(
                    sessionId = sessionId,
                    appName = appName,
                    title = title,
                    session = session,
                    handle = handle,
                    disposable = disposable,
                    openedFromTui = isTuiFocused(),
                )
                session.onTerminated { closeTab(sessionId) }
                selectTuiTab(host, tabsBySessionId.getValue(sessionId), recordCurrent = true)
            },
            onFailed = { throwable ->
                pendingLaunchesBySessionId.remove(sessionId)
                Disposer.dispose(disposable)
                thisLogger().warn("Failed to launch TUI app: $command", throwable)
            },
        )
    }

    private fun selectTuiTab(
        host: IdeToolWindowHost,
        tab: OpenTab,
        recordCurrent: Boolean = true,
        requestFocus: Boolean = true,
    ) {
        val selectionWillChange = host.activeTab() != tab.handle
        if (recordCurrent && selectionWillChange) recordActiveTabSize(host)
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
        val activeTab = tabsBySessionId.values.firstOrNull { it.handle == activeHandle } ?: return
        val size = host.currentSize() ?: return
        configFor(activeTab.appName)?.let { config ->
            config.windowWidth = size.width
            config.windowHeight = size.height
        }
    }

    private fun savedSize(appName: String): ToolWindowSize? {
        val config = configFor(appName) ?: return null
        val width = config.windowWidth ?: return null
        val height = config.windowHeight ?: return null
        if (width <= 0 || height <= 0) return null
        return ToolWindowSize(width, height)
    }

    private fun configFor(appName: String): TuiAppConfig? =
        TuiLauncherSettings.getInstance().state.tuiApps.firstOrNull { it.name == appName }

    private fun closeTab(sessionId: String) {
        val tab = tabsBySessionId[sessionId] ?: return
        if (!closingSessions.add(sessionId)) return
        var shouldRestoreEditorFocus = false

        resolveHost()?.let { h ->
            val closingActiveTab = h.activeTab() == tab.handle
            shouldRestoreEditorFocus = closingActiveTab && !tab.openedFromTui
            if (closingActiveTab) {
                h.currentSize()?.let { size ->
                    configFor(tab.appName)?.let { config ->
                        config.windowWidth = size.width
                        config.windowHeight = size.height
                    }
                }
            }
        }

        if (shouldRestoreEditorFocus) focusEditor()
        val host = resolveHost()
        if (host == null) {
            forgetTab(sessionId)
        } else {
            invokeLater {
                forgetTab(sessionId)
                host.removeTab(tab.handle)
                if (windowRevealedByLaunch && host.isPinned()) {
                    host.hide()
                }
            }
        }
        Disposer.dispose(tab.disposable)
    }

    private fun forgetRemovedTab(handle: Any) {
        val entry = tabsBySessionId.entries.firstOrNull { it.value.handle == handle } ?: return
        val tab = entry.value
        forgetTab(entry.key)
        Disposer.dispose(tab.disposable)
    }

    private fun forgetTab(sessionId: String) {
        tabsBySessionId.remove(sessionId)
        closingSessions.remove(sessionId)
    }
}
