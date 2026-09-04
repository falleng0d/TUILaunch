package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.PromptBoxCopilotStarter
import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.prompt.PromptBoxPanel
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.prompt.promptBoxHoldsFocus
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.ui.Splitter
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TuiAppLaunchServicePromptBoxTest : BasePlatformTestCase() {

    private val services = mutableListOf<TuiAppLaunchService>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus
    private val focusedBoxes = mutableListOf<PromptBox>()
    private var thePromptBoxHasFocus = false

    override fun setUp() {
        super.setUp()
        resetPromptBoxSettings()
        promptBoxFocusRequest = { box -> focusedBoxes.add(box) }
        promptBoxHoldsFocus = { thePromptBoxHasFocus }
    }

    override fun tearDown() {
        try {
            services.forEach { closeSessionsLeftOpenByEarlierTests(it) }
            promptBoxFocusRequest = focusRequestOutsideThisTest
            promptBoxHoldsFocus = focusCheckOutsideThisTest
            resetPromptBoxSettings()
        } finally {
            super.tearDown()
        }
    }

    private fun resetPromptBoxSettings() {
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            restoreOpenTabs = false
            promptBoxVisible = false
            promptBoxPercent = 30
            rememberPromptBoxVisibilityPerApp = false
            rememberPromptBoxSizePerApp = false
        }
    }

    private fun state(): TuiLauncherSettings.State = TuiLauncherSettings.getInstance().state

    private fun configureApps(vararg names: String) {
        state().tuiApps = names.mapTo(mutableListOf()) { TuiAppConfig(name = it, command = it) }
    }

    private fun appConfig(name: String): TuiAppConfig = state().tuiApps.first { it.name == name }

    private fun newService(sessions: List<FakeSession>): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project)
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(sessions)
        services.add(service)
        return service to host
    }

    private fun launchTwoApps(): Triple<TuiAppLaunchService, FakeHost, List<Any>> {
        configureApps("claude", "codex")
        val (service, host) = newService(listOf(FakeSession(), FakeSession()))
        service.toggle("TUILauncher.claude", "claude", "claude")
        service.toggle("TUILauncher.codex", "codex", "codex")
        return Triple(service, host, host.tabs.toList())
    }

    private fun splitterOf(host: FakeHost, handle: Any): Splitter = host.componentOf(handle) as Splitter

    private fun promptBoxPanelOf(host: FakeHost, handle: Any): PromptBoxPanel =
        splitterOf(host, handle).secondComponent as PromptBoxPanel

    private fun promptBoxIsVisibleIn(host: FakeHost, handle: Any): Boolean =
        promptBoxPanelOf(host, handle).isVisible

    fun testATabWrapsTheTerminalAndAHiddenPromptBoxInASplitter() {
        val session = FakeSession()
        val (service, host) = newService(listOf(session))

        service.launchNew("claude", "claude")

        val handle = host.tabs.single()
        val splitter = splitterOf(host, handle)
        assertSame(session.component, splitter.firstComponent)
        assertSame(promptBoxPanelOf(host, handle), splitter.secondComponent)
        assertFalse(promptBoxIsVisibleIn(host, handle))
        assertNull(promptBoxPanelOf(host, handle).promptBox.installedEditor)
        assertEquals(false, service.isPromptBoxVisible())
    }

    fun testTheToggleShowsAndHidesTheBoxOfTheActiveTab() {
        val (service, host) = newService(listOf(FakeSession()))
        service.launchNew("claude", "claude")
        val handle = host.tabs.single()

        service.togglePromptBox()

        assertEquals(true, service.isPromptBoxVisible())
        assertTrue(promptBoxIsVisibleIn(host, handle))
        assertNotNull(promptBoxPanelOf(host, handle).promptBox.installedEditor)

        service.togglePromptBox()

        assertEquals(false, service.isPromptBoxVisible())
        assertFalse(promptBoxIsVisibleIn(host, handle))
    }

    fun testWithNoTabOpenThereIsNoPromptBoxToToggle() {
        val (service, host) = newService(emptyList())

        assertNull(service.isPromptBoxVisible())
        service.togglePromptBox()

        assertFalse(state().promptBoxVisible)
        assertEquals(0, host.showCount)
    }

    fun testShowingTheBoxIsRememberedGloballyAndReachesEveryOpenTab() {
        val (service, host, tabs) = launchTwoApps()

        service.setPromptBoxVisible(true)

        assertTrue(state().promptBoxVisible)
        assertTrue(promptBoxIsVisibleIn(host, tabs[0]))
        assertTrue(promptBoxIsVisibleIn(host, tabs[1]))
        assertNull(appConfig("claude").promptBoxVisible)
    }

    fun testInPerAppModeShowingTheBoxOnlyReachesTabsOfThatApp() {
        val (service, host, tabs) = launchTwoApps()
        state().rememberPromptBoxVisibilityPerApp = true

        service.setPromptBoxVisible(true)

        assertEquals(true, appConfig("codex").promptBoxVisible)
        assertNull(appConfig("claude").promptBoxVisible)
        assertFalse(state().promptBoxVisible)
        assertFalse(promptBoxIsVisibleIn(host, tabs[0]))
        assertTrue(promptBoxIsVisibleIn(host, tabs[1]))
    }

    fun testSelectingATabAppliesThePromptBoxStateOfItsApp() {
        val (_, host, tabs) = launchTwoApps()
        state().rememberPromptBoxVisibilityPerApp = true
        state().rememberPromptBoxSizePerApp = true
        appConfig("claude").promptBoxVisible = true
        appConfig("claude").promptBoxPercent = 60

        host.selectTab(tabs[0])

        assertTrue(promptBoxIsVisibleIn(host, tabs[0]))
        assertEquals(0.4f, splitterOf(host, tabs[0]).proportion, 0.001f)

        host.selectTab(tabs[1])

        assertFalse(promptBoxIsVisibleIn(host, tabs[1]))
    }

    fun testATabLaunchedWhileTheBoxIsOnOpensWithItAtTheSavedSize() {
        state().promptBoxVisible = true
        state().promptBoxPercent = 45
        val (service, host) = newService(listOf(FakeSession()))

        service.launchNew("claude", "claude")

        val handle = host.tabs.single()
        assertTrue(promptBoxIsVisibleIn(host, handle))
        assertNotNull(promptBoxPanelOf(host, handle).promptBox.installedEditor)
        assertEquals(0.55f, splitterOf(host, handle).proportion, 0.001f)
    }

    fun testASideDockedWindowStacksTheBoxAndMovingTheWindowFlipsEveryOpenTab() {
        val (_, host, tabs) = launchTwoApps()

        assertTrue(splitterOf(host, tabs[0]).isVertical)

        host.triggerAnchorChanged(true)

        assertFalse(splitterOf(host, tabs[0]).isVertical)
        assertFalse(splitterOf(host, tabs[1]).isVertical)
    }

    fun testATabLaunchedOnAHorizontalEdgePutsTheBoxBesideTheTerminal() {
        val (service, host) = newService(listOf(FakeSession()))
        host.dockedHorizontally = true

        service.launchNew("claude", "claude")

        assertFalse(splitterOf(host, host.tabs.single()).isVertical)
    }

    fun testDraggingTheDividerIsRememberedGloballyAndReachesEveryOpenTab() {
        val (_, host, tabs) = launchTwoApps()

        splitterOf(host, tabs[0]).proportion = 0.4f

        assertEquals(60, state().promptBoxPercent)
        assertEquals(0.4f, splitterOf(host, tabs[1]).proportion, 0.001f)
    }

    fun testInPerAppModeDraggingTheDividerLeavesTheOtherAppAlone() {
        val (_, host, tabs) = launchTwoApps()
        state().rememberPromptBoxSizePerApp = true

        splitterOf(host, tabs[0]).proportion = 0.4f

        assertEquals(60, appConfig("claude").promptBoxPercent)
        assertNull(appConfig("codex").promptBoxPercent)
        assertEquals(30, state().promptBoxPercent)
        assertEquals(0.7f, splitterOf(host, tabs[1]).proportion, 0.001f)
    }

    fun testShowingTheBoxFocusesItAndHidingItHandsTheKeyboardBackToTheTerminal() {
        val session = FakeSession()
        val (service, _) = newService(listOf(session))
        service.launchNew("claude", "claude")
        val sessionFocusCountBeforeTheToggle = session.focusCount

        service.setPromptBoxVisible(true)

        assertEquals(1, focusedBoxes.size)
        assertEquals(sessionFocusCountBeforeTheToggle, session.focusCount)

        thePromptBoxHasFocus = true
        service.setPromptBoxVisible(false)

        assertEquals(sessionFocusCountBeforeTheToggle + 1, session.focusCount)
    }

    fun testHidingTheBoxLeavesTheFocusAloneWhenItWasNotInTheBox() {
        val session = FakeSession()
        val (service, _) = newService(listOf(session))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        val sessionFocusCountBeforeHiding = session.focusCount

        service.setPromptBoxVisible(false)

        assertEquals(sessionFocusCountBeforeHiding, session.focusCount)
    }

    fun testShowingTheBoxStartsTheCopilotServerWhileCopilotIsTheCompletionSource() {
        val (service, _) = newService(listOf(FakeSession()))
        val backend = FakeCopilotCompletionBackend()
        service.promptBoxCompletions = PromptBoxCopilotStarter(FakePromptBoxCompletionSettings()) { backend }
        service.launchNew("claude", "claude")

        service.setPromptBoxVisible(true)

        assertEquals(1, backend.startRequests)
    }

    fun testShowingTheBoxLeavesCopilotAloneWhileJetBrainsAiIsTheCompletionSource() {
        val (service, _) = newService(listOf(FakeSession()))
        val backend = FakeCopilotCompletionBackend()
        val settings = FakePromptBoxCompletionSettings(PromptBoxCompletionSource.JETBRAINS_AI)
        service.promptBoxCompletions = PromptBoxCopilotStarter(settings) { backend }
        service.launchNew("claude", "claude")

        service.setPromptBoxVisible(true)

        assertEquals(0, backend.startRequests)
    }

    fun testClosingATabHidesThePromptBoxBeforeItsEditorIsReleased() {
        val (service, host) = newService(listOf(FakeSession()))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        val handle = host.tabs.single()
        val panel = promptBoxPanelOf(host, handle)
        val editor = panel.promptBox.editor

        service.closeActiveTui()

        assertFalse(panel.isVisible)
        assertTrue(editor.isDisposed)

        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(host.tabs.isEmpty())
    }
}
