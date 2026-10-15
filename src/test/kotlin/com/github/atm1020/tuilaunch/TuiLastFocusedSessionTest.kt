package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JPanel

class TuiLastFocusedSessionTest : BasePlatformTestCase() {

    private val hosts = mutableListOf<FakeHost>()
    private val codeEditor = JPanel()

    override fun setUp() {
        super.setUp()
        resetSettings()
    }

    override fun tearDown() {
        try {
            hosts.flatMap { it.disposables }.forEach { Disposer.dispose(it) }
            resetSettings()
        } finally {
            super.tearDown()
        }
    }

    private fun resetSettings() {
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            restoreOpenTabs = false
            promptBoxVisible = false
            rememberPromptBoxVisibilityPerApp = false
        }
    }

    private fun newService(sessions: List<FakeSession>): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        hosts.add(host)
        service.host = host
        service.sessionFactory = FakeFactory(sessions)
        return service to host
    }

    private fun launchTabs(sessions: List<FakeSession>): Pair<TuiAppLaunchService, FakeHost> {
        val (service, host) = newService(sessions)
        repeat(sessions.size) { service.launchNew("claude", "claude") }
        return service to host
    }

    private fun moveToEditor(service: TuiAppLaunchService, host: FakeHost, from: Int): Any =
        host.moveTabToEditor(from) { handle -> service.tabMovedToEditor(handle) }

    fun testAToolWindowSessionFocusedLastLeavesTheActiveToolWindowTabAsTheTarget() {
        val sessions = List(3) { FakeSession() }
        val (service, host) = launchTabs(sessions)
        moveToEditor(service, host, 0)
        host.moveFocusTo(sessions[0].component)
        host.moveFocusTo(sessions[1].component)
        host.selectTab(host.tabs.last())

        service.sendTextToActiveSession("a prompt")

        assertEquals(listOf("a prompt"), sessions[2].sentText)
        assertNull(host.selectedEditorTab)
    }

    fun testAnEditorSessionFocusedLastReceivesTheActionsInsideItsEditorTab() {
        val sessions = List(2) { FakeSession() }
        val (service, host) = launchTabs(sessions)
        val moved = moveToEditor(service, host, 0)
        host.moveFocusTo(sessions[0].component)
        host.moveFocusTo(codeEditor)
        val toolWindowShowsBeforeTheSend = host.showCount

        service.sendTextToActiveSession("a prompt")

        assertEquals(listOf("a prompt"), sessions[0].sentText)
        assertSame(moved, host.selectedEditorTab)
        assertEquals(toolWindowShowsBeforeTheSend, host.showCount)

        service.closeActiveTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.editorTabs.isEmpty())
        assertEquals(1, host.tabs.size)
    }

    fun testTheActionsFallBackToTheActiveToolWindowTabOnceTheLastFocusedEditorSessionCloses() {
        val sessions = List(2) { FakeSession() }
        val (service, host) = launchTabs(sessions)
        val moved = moveToEditor(service, host, 0)
        host.moveFocusTo(sessions[0].component)
        host.closeEditorTab(moved)

        service.sendTextToActiveSession("a prompt")

        assertEquals(listOf("a prompt"), sessions[1].sentText)
    }

    fun testToggleFocusTreatsAFocusedEditorSessionAsTheTui() {
        val sessions = List(2) { FakeSession() }
        val (service, host) = launchTabs(sessions)
        val moved = moveToEditor(service, host, 0)
        var editorFocusRequests = 0
        service.editorFocusRequest = { editorFocusRequests++ }
        host.moveFocusTo(sessions[0].component)

        service.toggleFocus()
        assertEquals(1, editorFocusRequests)

        host.moveFocusTo(codeEditor)
        service.toggleFocus()
        assertEquals(1, editorFocusRequests)
        assertSame(moved, host.selectedEditorTab)
    }

    fun testTheEditorTabMenuTogglesThePromptBoxOfItsOwnSessionOnly() {
        TuiLauncherSettings.getInstance().state.apply {
            rememberPromptBoxVisibilityPerApp = true
            tuiApps = mutableListOf(TuiAppConfig("claude", "claude"), TuiAppConfig("codex", "codex"))
        }
        val (service, host) = newService(listOf(FakeSession(), FakeSession()))
        service.toggle("TUILauncher.claude", "claude", "claude")
        service.toggle("TUILauncher.codex", "codex", "codex")
        val claude = moveToEditor(service, host, 0)
        val codex = host.tabs.single()

        service.togglePromptBoxOf(claude)

        assertEquals(true, service.promptBoxVisibilityOf(claude))
        assertEquals(false, service.promptBoxVisibilityOf(codex))
        assertNull(service.promptBoxVisibilityOf(Any()))
    }

    fun testTheIdeTabShortcutSwitchesEditorTabsInsideAnEditorSessionAndTuiTabsInTheToolWindow() {
        val sessions = List(3) { FakeSession() }
        val (service, host) = launchTabs(sessions)
        moveToEditor(service, host, 0)
        val (first, second) = host.tabs
        host.selectTab(first)

        service.tabShortcutPressedIn(sessions[0].component, forward = true)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(true), host.editorTabSwitches)
        assertSame(first, host.activeTab())

        service.tabShortcutPressedIn(sessions[1].component, forward = true)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(listOf(true), host.editorTabSwitches)
        assertSame(second, host.activeTab())
    }

    fun testASessionReturnedIntoAToolWindowSplitFollowsTheAnchorAgain() {
        val (service, host) = launchTabs(listOf(FakeSession()))
        val handle = host.tabs.single()
        moveToEditor(service, host, 0)

        host.returnTabFromEditorIntoASplit(handle)
        host.triggerAnchorChanged(true)

        assertFalse((host.componentOf(handle) as Splitter).isVertical)
    }
}
