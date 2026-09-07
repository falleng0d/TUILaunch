package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.FOCUS_PROMPT_BOX_ACTION_ID
import com.github.atm1020.tuilaunch.action.FocusPromptBoxAction
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.PromptBoxPanel
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.prompt.promptBoxHoldsFocus
import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import java.awt.event.KeyEvent

class FocusPromptBoxActionTest : BasePlatformTestCase() {

    private val services = mutableListOf<TuiAppLaunchService>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus
    private val focusedBoxes = mutableListOf<PromptBox>()

    override fun setUp() {
        super.setUp()
        resetPromptBoxSettings()
        promptBoxFocusRequest = { box -> focusedBoxes.add(box) }
        promptBoxHoldsFocus = { false }
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
            focusPromptBoxKeyCode = null
        }
    }

    private fun state(): TuiLauncherSettings.State = TuiLauncherSettings.getInstance().state

    private fun newService(sessions: List<FakeSession>): Pair<TuiAppLaunchService, FakeHost> {
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(sessions)
        services.add(service)
        return service to host
    }

    private fun theProjectServiceWithoutTabs(): TuiAppLaunchService {
        val service = project.service<TuiAppLaunchService>()
        closeSessionsLeftOpenByEarlierTests(service)
        services.add(service)
        service.host = FakeHost()
        return service
    }

    private fun promptBoxIsVisibleIn(host: FakeHost, handle: Any): Boolean {
        val splitter = host.componentOf(handle) as Splitter
        return (splitter.secondComponent as PromptBoxPanel).isVisible
    }

    private fun eventFor(action: AnAction): AnActionEvent =
        TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

    private fun declaredFocusPromptBoxAction(): Element =
        JDOMUtil.load(FocusPromptBoxAction::class.java, "/META-INF/plugin.xml")
            .getChild("actions")
            .getChildren("action")
            .first { it.getAttributeValue("id") == FOCUS_PROMPT_BOX_ACTION_ID }

    fun testFocusingTheBoxRevealsTheTabAndPutsTheCursorInTheBox() {
        val session = FakeSession()
        val (service, host) = newService(listOf(session))
        service.launchNew("claude", "claude")
        val handle = host.tabs.single()
        host.showCount = 0
        focusedBoxes.clear()
        val sessionFocusCountBeforeTheCall = session.focusCount

        service.focusPromptBox()

        assertTrue(host.visible)
        assertEquals(1, host.showCount)
        assertSame(handle, host.activeTab())
        assertTrue(promptBoxIsVisibleIn(host, handle))
        assertTrue(state().promptBoxVisible)
        assertEquals(1, focusedBoxes.size)
        assertEquals(sessionFocusCountBeforeTheCall, session.focusCount)
    }

    fun testFocusingAnAlreadyVisibleBoxOnlyMovesTheCursorIntoIt() {
        val session = FakeSession()
        val (service, host) = newService(listOf(session))
        service.launchNew("claude", "claude")
        service.setPromptBoxVisible(true)
        focusedBoxes.clear()
        host.showCount = 0
        val sessionFocusCountBeforeTheCall = session.focusCount

        service.focusPromptBox()

        assertEquals(1, host.showCount)
        assertEquals(1, focusedBoxes.size)
        assertTrue(state().promptBoxVisible)
        assertEquals(sessionFocusCountBeforeTheCall, session.focusCount)
    }

    fun testFocusingTheBoxWithNoTabOpenDoesNothing() {
        val (service, host) = newService(emptyList())

        service.focusPromptBox()

        assertEquals(0, host.showCount)
        assertFalse(state().promptBoxVisible)
        assertEmpty(focusedBoxes)
    }

    fun testFocusingTheBoxFromTheTuiKeepsAClosedTabFromReturningToTheEditor() {
        val (service, _) = newService(listOf(FakeSession()))
        service.activeToolWindowIdProvider = { "Project" }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }
        service.launchNew("claude", "claude")

        service.activeToolWindowIdProvider = { TUI_TOOL_WINDOW_ID }
        service.focusPromptBox()
        service.closeActiveTui()

        assertEquals(0, editorFocusCount)
    }

    fun testFocusingTheBoxFromTheEditorSendsAClosedTabBackToTheEditor() {
        val (service, _) = newService(listOf(FakeSession()))
        service.activeToolWindowIdProvider = { TUI_TOOL_WINDOW_ID }
        var editorFocusCount = 0
        service.editorFocusRequest = { editorFocusCount++ }
        service.launchNew("claude", "claude")

        service.activeToolWindowIdProvider = { "Project" }
        service.focusPromptBox()
        service.closeActiveTui()

        assertEquals(1, editorFocusCount)
    }

    fun testThePrefixCommandForTheBoxShowsAndFocusesIt() {
        val (service, _) = newService(listOf(FakeSession()))
        state().focusPromptBoxKeyCode = KeyEvent.VK_B
        service.launchNew("claude", "claude")
        focusedBoxes.clear()

        service.prefixCommandActions().getValue(KeyEvent.VK_B).invoke()

        assertEquals(true, service.isPromptBoxVisible())
        assertEquals(1, focusedBoxes.size)
    }

    fun testTheActionIsDisabledWhileNoTabIsOpen() {
        theProjectServiceWithoutTabs()
        val action = FocusPromptBoxAction()
        val event = eventFor(action)

        action.update(event)

        assertFalse(event.presentation.isEnabled)
    }

    fun testTheActionShowsAndFocusesTheBoxOfTheOpenTab() {
        val service = theProjectServiceWithoutTabs()
        service.sessionFactory = FakeFactory(FakeSession())
        service.launchNew("claude", "claude")
        focusedBoxes.clear()
        val action = FocusPromptBoxAction()
        val event = eventFor(action)

        action.update(event)

        assertTrue(event.presentation.isEnabled)

        action.actionPerformed(event)

        assertEquals(true, service.isPromptBoxVisible())
        assertEquals(1, focusedBoxes.size)
    }

    fun testThePluginDescriptorRegistersTheFocusActionInTheToolsMenuWithNoKeystroke() {
        val declared = declaredFocusPromptBoxAction()

        assertEquals(FocusPromptBoxAction::class.java.name, declared.getAttributeValue("class"))
        assertEquals("TUILaunch Focus Prompt Box", declared.getAttributeValue("text"))
        assertEquals(
            listOf("ToolsMenu"),
            declared.getChildren("add-to-group").map { it.getAttributeValue("group-id") },
        )
        assertEmpty(declared.getChildren("keyboard-shortcut"))
    }
}
