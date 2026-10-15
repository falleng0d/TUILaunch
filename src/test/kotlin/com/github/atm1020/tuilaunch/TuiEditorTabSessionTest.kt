package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.services.TuiOpenTabsService
import com.github.atm1020.tuilaunch.toolwindow.ToolWindowSize
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TuiEditorTabSessionTest : BasePlatformTestCase() {

    private val strategy = RecordingSessionStrategy()

    override fun setUp() {
        super.setUp()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            restoreOpenTabs = true
            restoreAgentSessions = true
            promptBoxVisible = false
        }
        TuiOpenTabsService.getInstance(project).replaceTabs(emptyList())
    }

    override fun tearDown() {
        try {
            TuiLauncherSettings.getInstance().state.apply {
                tuiApps.clear()
                restoreOpenTabs = false
            }
            TuiOpenTabsService.getInstance(project).replaceTabs(emptyList())
        } finally {
            super.tearDown()
        }
    }

    private fun immediateScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Disposer.register(testRootDisposable) { scope.cancel() }
        return scope
    }

    private fun launchTabs(count: Int, sessions: List<FakeSession> = List(count) { FakeSession() }): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project, immediateScope())
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(sessions)
        val environment = temporaryAgentSessionEnvironment()
        service.agentSessionEnvironment = { environment }
        service.agentSessionStrategies = { _, _, _ -> strategy }
        repeat(count) { service.launchNew("claude", "claude") }
        return service to host
    }

    private fun moveToEditor(service: TuiAppLaunchService, host: FakeHost, from: Int): Any =
        host.moveTabToEditor(from) { handle -> service.tabMovedToEditor(handle) }

    private fun savedTitles(): List<String> = TuiOpenTabsService.getInstance(project).state.tabs.map { it.title }

    private fun splitterOf(host: FakeHost, handle: Any): Splitter = host.componentOf(handle) as Splitter

    private fun runAnAction(service: TuiAppLaunchService) {
        service.focusTui()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    fun testASessionMovedToTheEditorStaysAliveAndStaysInTheSavedTabs() {
        val (service, host) = launchTabs(2)

        moveToEditor(service, host, 0)
        runAnAction(service)

        assertTrue(host.disposables.none { it.isDisposed })
        assertEquals(listOf("claude 1", "claude"), savedTitles())
    }

    fun testASessionReturnedFromTheEditorStaysAliveAndRejoinsTheStrip() {
        val (service, host) = launchTabs(2)
        val moved = moveToEditor(service, host, 0)

        host.returnTabFromEditor(moved)
        runAnAction(service)

        assertTrue(host.disposables.none { it.isDisposed })
        assertEquals(moved, host.tabs.last())
        assertEquals(listOf("claude 1", "claude"), savedTitles())
    }

    fun testClosingTheEditorTabCleansUpLikeAUserClose() {
        val (service, host) = launchTabs(2, List(3) { FakeSession() })
        val moved = moveToEditor(service, host, 0)

        host.closeEditorTab(moved)

        assertTrue(host.disposables[0].isDisposed)
        assertEquals(listOf("claude 1"), savedTitles())
        assertTrue("cleanUp" in strategy.calls)
        assertTrue("tabClosed" in strategy.calls)
        service.launchNew("claude", "claude")
        assertEquals("claude", host.titles.last())
    }

    fun testAnEditorTabReleasedByTheProjectCloseKeepsItsAgentSessionAndItsSavedTab() {
        val (service, host) = launchTabs(2)
        val moved = moveToEditor(service, host, 0)

        service.prepareForProjectClose()
        host.closeEditorTab(moved)

        assertEquals(listOf("claude 1", "claude"), savedTitles())
        assertFalse("cleanUp" in strategy.calls)
    }

    fun testAnEditorSessionWhoseProcessEndsClosesItsEditorTab() {
        val sessions = listOf(FakeSession(), FakeSession())
        val (service, host) = launchTabs(2, sessions)
        moveToEditor(service, host, 0)

        sessions[0].terminate()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.editorTabs.isEmpty())
        assertTrue(host.disposables[0].isDisposed)
        assertEquals(1, host.tabs.size)
        assertEquals(listOf("claude 1"), savedTitles())
    }

    fun testTheEditorStacksThePromptBoxBelowTheTuiWhateverTheAnchor() {
        val host = FakeHost().apply { dockedHorizontally = true }
        val service = TuiAppLaunchService(project, immediateScope())
        service.host = host
        service.sessionFactory = FakeFactory(FakeSession())
        service.launchNew("claude", "claude")
        val handle = host.tabs.single()
        assertFalse(splitterOf(host, handle).isVertical)

        moveToEditor(service, host, 0)
        host.triggerAnchorChanged(false)
        host.triggerAnchorChanged(true)
        assertTrue(splitterOf(host, handle).isVertical)

        host.returnTabFromEditor(handle)
        assertFalse(splitterOf(host, handle).isVertical)
    }

    fun testTabCyclingSkipsEditorSessions() {
        val (service, host) = launchTabs(3)
        val moved = moveToEditor(service, host, 1)
        val (first, last) = host.tabs
        host.selectTab(first)

        val visited = (1..2).map {
            service.nextTuiTab()
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            host.activeTab()
        }

        assertEquals(listOf(last, first), visited)
        assertNull(host.selectedEditorTab)
        assertFalse(moved in visited)
    }

    fun testTogglingAnAppInTheEditorSelectsItsEditorTabWithoutTouchingTheToolWindowSize() {
        val claude = TuiAppConfig(name = "claude", command = "claude", windowWidth = 900, windowHeight = 700)
        val codex = TuiAppConfig(name = "codex", command = "codex")
        TuiLauncherSettings.getInstance().state.tuiApps = mutableListOf(claude, codex)
        val service = TuiAppLaunchService(project, immediateScope())
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(listOf(FakeSession(), FakeSession()))
        service.toggle("TUILauncher.claude", "claude", "claude")
        service.toggle("TUILauncher.codex", "codex", "codex")
        val moved = moveToEditor(service, host, 0)
        host.appliedSizes.clear()
        host.size = ToolWindowSize(500, 400)

        service.toggle("TUILauncher.claude", "claude", "claude")

        assertSame(moved, host.selectedEditorTab)
        assertEquals(2, host.disposables.size)
        assertTrue(host.appliedSizes.isEmpty())
        assertEquals(900 to 700, claude.windowWidth to claude.windowHeight)
    }
}
