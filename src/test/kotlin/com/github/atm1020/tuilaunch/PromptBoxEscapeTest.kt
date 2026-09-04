package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_DATA_KEY
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.PromptBoxEscapeAction
import com.github.atm1020.tuilaunch.prompt.PromptBoxPanel
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.prompt.promptBoxHoldsFocus
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.codeInsight.lookup.LookupArranger
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PromptBoxEscapeTest : BasePlatformTestCase() {

    private val boxDisposables = mutableListOf<Disposable>()
    private val services = mutableListOf<TuiAppLaunchService>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus
    private var sessionFocusCount = 0

    override fun setUp() {
        super.setUp()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            restoreOpenTabs = false
            promptBoxVisible = false
        }
        promptBoxFocusRequest = { }
        promptBoxHoldsFocus = { false }
    }

    override fun tearDown() {
        try {
            services.forEach { closeSessionsLeftOpenByEarlierTests(it) }
            boxDisposables.forEach { Disposer.dispose(it) }
            promptBoxFocusRequest = focusRequestOutsideThisTest
            promptBoxHoldsFocus = focusCheckOutsideThisTest
        } finally {
            super.tearDown()
        }
    }

    private fun standaloneBox(): PromptBox {
        val disposable = Disposer.newDisposable("PromptBoxEscapeTest")
        boxDisposables.add(disposable)
        val box = PromptBox(project, disposable, focusSession = { sessionFocusCount++ })
        box.installEditor()
        return box
    }

    private fun boxOfALaunchedTab(session: FakeSession): PromptBox {
        val service = TuiAppLaunchService(project)
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(session)
        services.add(service)
        service.launchNew("claude", "claude")
        val splitter = host.componentOf(host.tabs.single()) as Splitter
        return (splitter.secondComponent as PromptBoxPanel).promptBox.also { it.installEditor() }
    }

    private fun eventFor(action: AnAction, box: PromptBox?): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (box != null) context.add(PROMPT_BOX_DATA_KEY, box)
        return TestActionEvent.createTestEvent(action, context.build())
    }

    private fun updatedPresentation(box: PromptBox?): Presentation {
        val action = PromptBoxEscapeAction()
        val event = eventFor(action, box)
        action.update(event)
        return event.presentation
    }

    private fun pressEscapeIn(box: PromptBox) {
        val action = PromptBoxEscapeAction()
        action.actionPerformed(eventFor(action, box))
    }

    fun testEscapeIsEnabledWhileTheBoxOnlyHoldsText() {
        val box = standaloneBox()
        box.text = "a prompt"

        val presentation = updatedPresentation(box)

        assertTrue(presentation.isEnabled)
        assertTrue(presentation.isVisible)
    }

    fun testEscapeIsDisabledOutsideAPromptBox() {
        assertFalse(updatedPresentation(null).isEnabled)
    }

    fun testEscapeIsDisabledWhileACompletionPopupIsOpen() {
        val box = standaloneBox()
        box.text = "a"
        val lookupManager = LookupManager.getInstance(project)
        lookupManager.createLookup(
            box.editor,
            arrayOf(LookupElementBuilder.create("a prompt")),
            "a",
            LookupArranger.DefaultArranger(),
        )

        try {
            assertNotNull(LookupManager.getActiveLookup(box.editor))
            assertFalse(updatedPresentation(box).isEnabled)
        } finally {
            lookupManager.hideActiveLookup()
        }
    }

    fun testEscapeWithATextSelectionOnlyDropsTheSelection() {
        val box = standaloneBox()
        box.text = "a prompt"
        box.editor.selectionModel.setSelection(0, box.text.length)

        pressEscapeIn(box)

        assertFalse(box.editor.selectionModel.hasSelection())
        assertEquals(0, sessionFocusCount)
        assertEquals("a prompt", box.text)
    }

    fun testEscapeWithoutASelectionHandsTheKeyboardToTheSession() {
        val box = standaloneBox()
        box.text = "a prompt"

        pressEscapeIn(box)

        assertEquals(1, sessionFocusCount)
        assertEquals("a prompt", box.text)
    }

    fun testASecondEscapeAfterTheSelectionIsGoneReachesTheSession() {
        val box = standaloneBox()
        box.text = "a prompt"
        box.editor.selectionModel.setSelection(0, box.text.length)

        pressEscapeIn(box)
        pressEscapeIn(box)

        assertEquals(1, sessionFocusCount)
    }

    fun testEscapeInATabsBoxFocusesThatTabsTerminal() {
        val session = FakeSession()
        val box = boxOfALaunchedTab(session)
        val focusCountBeforeEscape = session.focusCount

        pressEscapeIn(box)

        assertEquals(focusCountBeforeEscape + 1, session.focusCount)
    }
}
