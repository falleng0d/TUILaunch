package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.PromptBoxCopilotStarter
import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.prompt.PromptBoxPanel
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.prompt.promptBoxHoldsFocus
import com.github.atm1020.tuilaunch.services.ReleasePromptBoxEditorsOnProjectClose
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private const val PROJECT_CLOSE_TOPIC = "com.intellij.openapi.project.ProjectCloseListener"

class TuiAppLaunchServicePromptBoxTest : BasePlatformTestCase() {

    private val services = mutableListOf<TuiAppLaunchService>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus
    private val focusedBoxes = mutableListOf<PromptBox>()
    private var theBoxHoldingFocus: PromptBox? = null

    override fun setUp() {
        super.setUp()
        resetPromptBoxSettings()
        promptBoxFocusRequest = { box -> focusedBoxes.add(box) }
        promptBoxHoldsFocus = { box -> box === theBoxHoldingFocus }
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
            hidePromptBoxAfterSend = false
        }
    }

    private fun state(): TuiLauncherSettings.State = TuiLauncherSettings.getInstance().state

    private fun configureApps(vararg names: String) {
        state().tuiApps = names.mapTo(mutableListOf()) { TuiAppConfig(name = it, command = it) }
    }

    private fun appConfig(name: String): TuiAppConfig = state().tuiApps.first { it.name == name }

    private fun newService(sessions: List<FakeSession>): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(sessions)
        services.add(service)
        return service to host
    }

    private class TwoOpenTabs(
        val service: TuiAppLaunchService,
        val host: FakeHost,
        val sessions: List<FakeSession>,
    ) {
        val tabs: List<Any> get() = host.tabs.toList()
    }

    private fun launchTwoAppsWithTheirSessions(): TwoOpenTabs {
        configureApps("claude", "codex")
        val sessions = listOf(FakeSession(), FakeSession())
        val (service, host) = newService(sessions)
        service.toggle("TUILauncher.claude", "claude", "claude")
        service.toggle("TUILauncher.codex", "codex", "codex")
        return TwoOpenTabs(service, host, sessions)
    }

    private fun launchTwoApps(): Triple<TuiAppLaunchService, FakeHost, List<Any>> {
        val open = launchTwoAppsWithTheirSessions()
        return Triple(open.service, open.host, open.tabs)
    }

    private fun splitterOf(host: FakeHost, handle: Any): Splitter = host.componentOf(handle) as Splitter

    private fun promptBoxPanelOf(host: FakeHost, handle: Any): PromptBoxPanel =
        splitterOf(host, handle).secondComponent as PromptBoxPanel

    private fun promptBoxIsVisibleIn(host: FakeHost, handle: Any): Boolean =
        promptBoxPanelOf(host, handle).isVisible

    private fun promptBoxOf(host: FakeHost, handle: Any): PromptBox = promptBoxPanelOf(host, handle).promptBox

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
        val (service, host) = newService(listOf(session))
        service.launchNew("claude", "claude")
        val sessionFocusCountBeforeTheToggle = session.focusCount

        service.setPromptBoxVisible(true)

        assertEquals(1, focusedBoxes.size)
        assertEquals(sessionFocusCountBeforeTheToggle, session.focusCount)

        theBoxHoldingFocus = promptBoxOf(host, host.tabs.single())
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

    fun testSwitchingToTheNextTabWithTheCursorInTheBoxPutsItInThatTabsBox() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        host.selectTab(open.tabs[0])
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[0])
        focusedBoxes.clear()
        val sessionFocusCountsBeforeSwitching = open.sessions.map { it.focusCount }

        open.service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[1], host.activeTab())
        assertEquals(listOf(promptBoxOf(host, open.tabs[1])), focusedBoxes)
        assertEquals(sessionFocusCountsBeforeSwitching, open.sessions.map { it.focusCount })
    }

    fun testSwitchingToTheNextTabFromTheSessionKeepsTheKeyboardInThatTabsSession() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        host.selectTab(open.tabs[0])
        focusedBoxes.clear()
        val codexSessionFocusCountBeforeSwitching = open.sessions[1].focusCount

        open.service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[1], host.activeTab())
        assertEmpty(focusedBoxes)
        assertEquals(codexSessionFocusCountBeforeSwitching + 1, open.sessions[1].focusCount)
    }

    fun testSwitchingToATabWhoseBoxIsHiddenFocusesItsSessionAndLeavesTheBoxHidden() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        state().rememberPromptBoxVisibilityPerApp = true
        appConfig("claude").promptBoxVisible = true
        appConfig("codex").promptBoxVisible = false
        host.selectTab(open.tabs[0])
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[0])
        focusedBoxes.clear()
        val codexSessionFocusCountBeforeSwitching = open.sessions[1].focusCount

        open.service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[1], host.activeTab())
        assertEmpty(focusedBoxes)
        assertEquals(codexSessionFocusCountBeforeSwitching + 1, open.sessions[1].focusCount)
        assertFalse(promptBoxIsVisibleIn(host, open.tabs[1]))
        assertEquals(false, appConfig("codex").promptBoxVisible)
    }

    fun testSwitchingAwayFromATabWhoseBoxIsHiddenLandsInTheNextTabsSession() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        state().rememberPromptBoxVisibilityPerApp = true
        appConfig("claude").promptBoxVisible = false
        appConfig("codex").promptBoxVisible = true
        host.selectTab(open.tabs[0])
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[0])
        focusedBoxes.clear()
        val codexSessionFocusCountBeforeSwitching = open.sessions[1].focusCount

        open.service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[1], host.activeTab())
        assertEmpty(focusedBoxes)
        assertEquals(codexSessionFocusCountBeforeSwitching + 1, open.sessions[1].focusCount)
        assertTrue(promptBoxIsVisibleIn(host, open.tabs[1]))
    }

    fun testTheCursorStaysInTheBoxWhileSwitchingWrapsAroundBothEndsOfTheStrip() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        host.selectTab(open.tabs[1])
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[1])
        focusedBoxes.clear()
        val sessionFocusCountsBeforeSwitching = open.sessions.map { it.focusCount }

        open.service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[0], host.activeTab())

        theBoxHoldingFocus = promptBoxOf(host, open.tabs[0])
        open.service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[1], host.activeTab())
        assertEquals(
            listOf(promptBoxOf(host, open.tabs[0]), promptBoxOf(host, open.tabs[1])),
            focusedBoxes,
        )
        assertEquals(sessionFocusCountsBeforeSwitching, open.sessions.map { it.focusCount })
    }

    fun testSwitchingToThePreviousTabWithTheCursorInTheBoxPutsItInThatTabsBox() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[1])
        focusedBoxes.clear()
        val sessionFocusCountsBeforeSwitching = open.sessions.map { it.focusCount }

        open.service.previousTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[0], host.activeTab())
        assertEquals(listOf(promptBoxOf(host, open.tabs[0])), focusedBoxes)
        assertEquals(sessionFocusCountsBeforeSwitching, open.sessions.map { it.focusCount })
    }

    fun testSwitchingTabsWithASingleTabOpenLeavesTheCursorWhereItIs() {
        val session = FakeSession()
        val (service, host) = newService(listOf(session))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        theBoxHoldingFocus = promptBoxOf(host, host.tabs.single())
        focusedBoxes.clear()
        val sessionFocusCountBeforeSwitching = session.focusCount

        service.nextTuiTab()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEmpty(focusedBoxes)
        assertEquals(sessionFocusCountBeforeSwitching, session.focusCount)
    }

    fun testSwitchingTabsWithoutFocusMovesNeitherTheCursorNorTheKeyboard() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        host.selectTab(open.tabs[0])
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[0])
        focusedBoxes.clear()
        val sessionFocusCountsBeforeSwitching = open.sessions.map { it.focusCount }

        open.service.nextTuiTabWithoutFocus()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[1], host.activeTab())

        open.service.previousTuiTabWithoutFocus()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertSame(open.tabs[0], host.activeTab())
        assertEmpty(focusedBoxes)
        assertEquals(sessionFocusCountsBeforeSwitching, open.sessions.map { it.focusCount })
    }

    fun testASendWithTheHideSettingOnHidesTheBoxAndLeavesTheKeyboardInThatTabsSession() {
        val session = FakeSession()
        val (service, host) = newService(listOf(session))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        state().hidePromptBoxAfterSend = true
        val handle = host.tabs.single()
        val box = promptBoxOf(host, handle)
        box.text = "Fix the bug"
        focusedBoxes.clear()
        val sessionFocusCountBeforeTheSend = session.focusCount

        box.send()

        assertEquals(listOf("Fix the bug"), session.sentText)
        assertFalse(promptBoxIsVisibleIn(host, handle))
        assertFalse(state().promptBoxVisible)
        assertEmpty(focusedBoxes)
        assertEquals(sessionFocusCountBeforeTheSend + 1, session.focusCount)
    }

    fun testASendFromABoxHoldingTheKeyboardAsksThatTabsSessionForItOnce() {
        val session = FakeSession()
        val (service, host) = newService(listOf(session))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        state().hidePromptBoxAfterSend = true
        val handle = host.tabs.single()
        val box = promptBoxOf(host, handle)
        box.text = "Fix the bug"
        theBoxHoldingFocus = box
        focusedBoxes.clear()
        val sessionFocusCountBeforeTheSend = session.focusCount

        box.send()

        assertFalse(promptBoxIsVisibleIn(host, handle))
        assertEmpty(focusedBoxes)
        assertEquals(sessionFocusCountBeforeTheSend + 1, session.focusCount)
    }

    fun testATabLaunchedAfterASendThatHidTheBoxOpensWithItHidden() {
        val (service, host) = newService(listOf(FakeSession(), FakeSession()))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        state().hidePromptBoxAfterSend = true
        val box = promptBoxOf(host, host.tabs.single())
        box.text = "Fix the bug"

        box.send()
        service.launchNew("claude", "claude")

        assertEquals(2, host.tabs.size)
        assertFalse(promptBoxIsVisibleIn(host, host.tabs.last()))
        assertNull(promptBoxPanelOf(host, host.tabs.last()).promptBox.installedEditor)
    }

    fun testInPerAppModeASendThatHidesTheBoxOnlyReachesTabsOfThatApp() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        state().rememberPromptBoxVisibilityPerApp = true
        state().hidePromptBoxAfterSend = true
        val claudeBox = promptBoxOf(host, open.tabs[0])
        claudeBox.text = "Fix the bug"
        focusedBoxes.clear()
        val sessionFocusCountsBeforeTheSend = open.sessions.map { it.focusCount }

        claudeBox.send()

        assertEquals(listOf("Fix the bug"), open.sessions[0].sentText)
        assertFalse(promptBoxIsVisibleIn(host, open.tabs[0]))
        assertTrue(promptBoxIsVisibleIn(host, open.tabs[1]))
        assertEquals(false, appConfig("claude").promptBoxVisible)
        assertNull(appConfig("codex").promptBoxVisible)
        assertTrue(state().promptBoxVisible)
        assertEmpty(focusedBoxes)
        assertEquals(sessionFocusCountsBeforeTheSend[0] + 1, open.sessions[0].focusCount)
        assertEquals(sessionFocusCountsBeforeTheSend[1], open.sessions[1].focusCount)
    }

    fun testFocusingABoxThatHoldsTheKeyboardHidesItWhileAVisibleBoxWithoutItOnlyTakesTheCursor() {
        val session = FakeSession()
        val (service, host) = newService(listOf(session))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        val handle = host.tabs.single()
        focusedBoxes.clear()
        val sessionFocusCountBeforeTheCalls = session.focusCount

        service.focusPromptBox()

        assertEquals(true, service.isPromptBoxVisible())
        assertEquals(1, focusedBoxes.size)
        assertEquals(sessionFocusCountBeforeTheCalls, session.focusCount)

        theBoxHoldingFocus = promptBoxOf(host, handle)
        service.focusPromptBox()

        assertEquals(false, service.isPromptBoxVisible())
        assertEquals(1, focusedBoxes.size)
        assertEquals(sessionFocusCountBeforeTheCalls + 1, session.focusCount)
    }

    fun testInPerAppModeFocusingABoxThatHoldsTheKeyboardOnlyHidesTabsOfThatApp() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        state().rememberPromptBoxVisibilityPerApp = true
        host.selectTab(open.tabs[0])
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[0])
        focusedBoxes.clear()
        val sessionFocusCountsBeforeTheCall = open.sessions.map { it.focusCount }

        open.service.focusPromptBox()

        assertFalse(promptBoxIsVisibleIn(host, open.tabs[0]))
        assertTrue(promptBoxIsVisibleIn(host, open.tabs[1]))
        assertEquals(false, appConfig("claude").promptBoxVisible)
        assertTrue(state().promptBoxVisible)
        assertEmpty(focusedBoxes)
        assertEquals(sessionFocusCountsBeforeTheCall[0] + 1, open.sessions[0].focusCount)
        assertEquals(sessionFocusCountsBeforeTheCall[1], open.sessions[1].focusCount)
    }

    fun testABoxHoldingTheKeyboardInAnotherTabDoesNotStopTheActiveTabsBoxFromTakingTheCursor() {
        val open = launchTwoAppsWithTheirSessions()
        val host = open.host
        open.service.setPromptBoxVisible(true)
        host.selectTab(open.tabs[0])
        theBoxHoldingFocus = promptBoxOf(host, open.tabs[1])
        focusedBoxes.clear()
        val sessionFocusCountsBeforeTheCall = open.sessions.map { it.focusCount }

        open.service.focusPromptBox()

        assertEquals(true, open.service.isPromptBoxVisible())
        assertTrue(promptBoxIsVisibleIn(host, open.tabs[0]))
        assertTrue(promptBoxIsVisibleIn(host, open.tabs[1]))
        assertEquals(listOf(promptBoxOf(host, open.tabs[0])), focusedBoxes)
        assertEquals(sessionFocusCountsBeforeTheCall, open.sessions.map { it.focusCount })
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

    fun testTheProjectCloseReleasesEveryOpenPromptBoxEditorWhileTheTabsStayOpen() {
        val (service, host, tabs) = launchTwoApps()
        service.setPromptBoxVisible(true)
        val editors = tabs.map { promptBoxPanelOf(host, it).promptBox.editor }

        service.releasePromptBoxEditors()

        assertTrue(editors.all { it.isDisposed })
        assertTrue(tabs.all { promptBoxPanelOf(host, it).promptBox.installedEditor == null })
        assertEquals(2, host.tabs.size)
    }

    fun testReleasingThePromptBoxEditorsTwiceIsHarmless() {
        val (service, host) = newService(listOf(FakeSession()))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        val editor = promptBoxPanelOf(host, host.tabs.single()).promptBox.editor

        service.releasePromptBoxEditors()
        service.releasePromptBoxEditors()

        assertTrue(editor.isDisposed)
    }

    fun testTheDescriptorReleasesThePromptBoxEditorsWhenTheProjectCloses() {
        val listener = JDOMUtil.load(PromptBox::class.java, "/META-INF/plugin.xml")
            .getChild("applicationListeners")
            .getChildren("listener")
            .single { it.getAttributeValue("topic") == PROJECT_CLOSE_TOPIC }

        assertEquals(ReleasePromptBoxEditorsOnProjectClose::class.java.name, listener.getAttributeValue("class"))
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
