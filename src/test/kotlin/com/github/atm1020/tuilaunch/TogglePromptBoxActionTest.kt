package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.TogglePromptBoxAction
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TogglePromptBoxActionTest : BasePlatformTestCase() {

    private val focusRequestOutsideThisTest = promptBoxFocusRequest

    override fun setUp() {
        super.setUp()
        promptBoxFocusRequest = { }
        resetPromptBoxSettings()
    }

    override fun tearDown() {
        try {
            closeSessionsLeftOpenByEarlierTests(project.service<TuiAppLaunchService>())
            promptBoxFocusRequest = focusRequestOutsideThisTest
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

    private fun serviceWithoutTabs(): TuiAppLaunchService {
        val service = project.service<TuiAppLaunchService>()
        closeSessionsLeftOpenByEarlierTests(service)
        service.host = FakeHost()
        return service
    }

    private fun launchFakeSession(): TuiAppLaunchService = serviceWithoutTabs().apply {
        sessionFactory = FakeFactory(FakeSession())
        launchNew("claude", "claude")
    }

    private fun eventFor(action: AnAction): AnActionEvent =
        TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

    fun testTheToggleIsDisabledWhileNoTabIsOpen() {
        serviceWithoutTabs()
        val action = TogglePromptBoxAction()
        val event = eventFor(action)

        action.update(event)

        assertFalse(event.presentation.isEnabled)
    }

    fun testPressingTheToggleShowsAndHidesThePromptBoxOfTheActiveTab() {
        val service = launchFakeSession()
        val action = TogglePromptBoxAction()
        val event = eventFor(action)

        action.update(event)

        assertTrue(event.presentation.isEnabled)
        assertFalse(action.isSelected(event))

        action.setSelected(event, true)

        assertTrue(action.isSelected(event))
        assertEquals(true, service.isPromptBoxVisible())

        action.setSelected(event, false)

        assertFalse(action.isSelected(event))
        assertEquals(false, service.isPromptBoxVisible())
    }
}
