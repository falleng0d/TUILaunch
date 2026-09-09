package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.SendPromptBoxAction
import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_DATA_KEY
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.PromptBoxPanel
import com.github.atm1020.tuilaunch.prompt.SEND_PROMPT_BOX_ACTION_ID
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.prompt.promptBoxHoldsFocus
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element

class SendPromptBoxActionTest : BasePlatformTestCase() {

    private val services = mutableListOf<TuiAppLaunchService>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus

    override fun setUp() {
        super.setUp()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            restoreOpenTabs = false
            promptBoxVisible = false
            submitPromptOnSend = false
            appendPromptSeparatorOnSend = false
            focusTuiAfterPromptBoxSend = false
            hidePromptBoxAfterSend = false
        }
        promptBoxFocusRequest = { }
        promptBoxHoldsFocus = { false }
    }

    override fun tearDown() {
        try {
            services.forEach { closeSessionsLeftOpenByEarlierTests(it) }
            promptBoxFocusRequest = focusRequestOutsideThisTest
            promptBoxHoldsFocus = focusCheckOutsideThisTest
        } finally {
            super.tearDown()
        }
    }

    private fun launchTabWithABox(session: FakeSession): PromptBox {
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(session)
        services.add(service)
        service.launchNew("claude", "claude")
        val splitter = host.componentOf(host.tabs.single()) as Splitter
        return (splitter.secondComponent as PromptBoxPanel).promptBox
    }

    private fun eventFor(action: AnAction, box: PromptBox?): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (box != null) context.add(PROMPT_BOX_DATA_KEY, box)
        return TestActionEvent.createTestEvent(action, context.build())
    }

    private fun updatedPresentation(box: PromptBox?): Presentation {
        val action = SendPromptBoxAction()
        val event = eventFor(action, box)
        action.update(event)
        return event.presentation
    }

    private fun declaredActions(): List<Element> =
        JDOMUtil.load(SendPromptBoxAction::class.java, "/META-INF/plugin.xml")
            .getChild("actions")
            .getChildren("action")

    private fun declaredSendAction(): Element =
        declaredActions().first { it.getAttributeValue("id") == SEND_PROMPT_BOX_ACTION_ID }

    fun testTheActionIsDisabledButStillVisibleWithoutAPromptBox() {
        val presentation = updatedPresentation(null)

        assertFalse(presentation.isEnabled)
        assertTrue(presentation.isVisible)
    }

    fun testTheActionIsEnabledWhenTheDataContextCarriesAPromptBox() {
        val box = launchTabWithABox(FakeSession())

        val presentation = updatedPresentation(box)

        assertTrue(presentation.isEnabled)
        assertTrue(presentation.isVisible)
    }

    fun testTheActionSendsTheTextTypedInThatBox() {
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.text = "Fix the bug"

        val action = SendPromptBoxAction()
        action.actionPerformed(eventFor(action, box))

        assertEquals(listOf("Fix the bug"), session.sentText)
        assertEquals("", box.text)
    }

    fun testTheActionSendsNothingWithoutAPromptBox() {
        val session = FakeSession()
        launchTabWithABox(session)

        val action = SendPromptBoxAction()
        action.actionPerformed(eventFor(action, null))

        assertEmpty(session.sentText)
    }

    fun testThePluginDescriptorRegistersTheSendActionInTheToolsMenu() {
        val declared = declaredSendAction()

        assertEquals(SendPromptBoxAction::class.java.name, declared.getAttributeValue("class"))
        assertEquals(
            listOf("ToolsMenu"),
            declared.getChildren("add-to-group").map { it.getAttributeValue("group-id") },
        )
    }

    fun testSendingIsBoundToControlEnterAndToCommandReturnOnMacOs() {
        val boundKeystrokes = declaredSendAction()
            .getChildren("keyboard-shortcut")
            .associate { it.getAttributeValue("keymap") to it.getAttributeValue("first-keystroke") }

        assertEquals(
            mapOf(
                "\$default" to "control ENTER",
                "Mac OS X 10.5+" to "meta ENTER",
                "Mac OS X" to "meta ENTER",
            ),
            boundKeystrokes,
        )
    }

    fun testSendingIsTheOnlyActionOfThisPluginThatShipsWithAKeystroke() {
        val actionsWithAKeystroke = declaredActions()
            .filter { it.getChildren("keyboard-shortcut").isNotEmpty() }
            .map { it.getAttributeValue("id") }

        assertEquals(listOf(SEND_PROMPT_BOX_ACTION_ID), actionsWithAKeystroke)
    }
}
