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
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

class PromptBoxEscapeTest : BasePlatformTestCase() {

    private val boxDisposables = mutableListOf<Disposable>()
    private val services = mutableListOf<TuiAppLaunchService>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus
    private var sessionFocusCount = 0
    private val whatTheSessionWasAskedToDo = mutableListOf<String>()

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
        val box = PromptBox(
            project,
            disposable,
            focusSession = {
                sessionFocusCount++
                whatTheSessionWasAskedToDo += "take the keyboard"
            },
            spendKeyPress = { keyEvent ->
                whatTheSessionWasAskedToDo += "spend the ${keyEvent.keyCode} press"
            },
        )
        box.installEditor()
        return box
    }

    private fun boxOfALaunchedTab(session: FakeSession): PromptBox {
        val service = TuiAppLaunchService(project, testCoroutineScope(testRootDisposable))
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(session)
        services.add(service)
        service.launchNew("claude", "claude")
        val splitter = host.componentOf(host.tabs.single()) as Splitter
        return (splitter.secondComponent as PromptBoxPanel).promptBox.also { it.installEditor() }
    }

    private fun eventFor(action: AnAction, box: PromptBox?, inputEvent: InputEvent? = null): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
        if (box != null) context.add(PROMPT_BOX_DATA_KEY, box)
        return TestActionEvent.createTestEvent(action, context.build(), inputEvent)
    }

    private fun escapeKeyEvent(box: PromptBox): KeyEvent = KeyEvent(
        box.component,
        KeyEvent.KEY_PRESSED,
        System.currentTimeMillis(),
        0,
        KeyEvent.VK_ESCAPE,
        KeyEvent.CHAR_UNDEFINED,
    )

    private fun updatedPresentation(box: PromptBox?): Presentation {
        val action = PromptBoxEscapeAction()
        val event = eventFor(action, box)
        action.update(event)
        return event.presentation
    }

    private fun pressEscapeIn(box: PromptBox): KeyEvent {
        val action = PromptBoxEscapeAction()
        val keyEvent = escapeKeyEvent(box)
        action.actionPerformed(eventFor(action, box, keyEvent))
        return keyEvent
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

    fun testEscapeIsDisabledWhileGhostTextIsShowing() {
        val box = standaloneBox()

        showGhostTextIn(box, testRootDisposable)

        assertFalse(updatedPresentation(box).isEnabled)
    }

    fun testEscapeWithATextSelectionOnlyDropsTheSelection() {
        val box = standaloneBox()
        box.text = "a prompt"
        box.editor.selectionModel.setSelection(0, box.text.length)

        pressEscapeIn(box)

        assertFalse(box.editor.selectionModel.hasSelection())
        assertEquals(0, sessionFocusCount)
        assertEquals("a prompt", box.text)
        assertEmpty(whatTheSessionWasAskedToDo)
    }

    fun testEscapeTellsTheSessionThePressIsSpentBeforeHandingItTheKeyboard() {
        val box = standaloneBox()

        pressEscapeIn(box)

        assertEquals(
            listOf("spend the ${KeyEvent.VK_ESCAPE} press", "take the keyboard"),
            whatTheSessionWasAskedToDo,
        )
    }

    fun testEscapeWithoutAKeyPressBehindItOnlyHandsOverTheKeyboard() {
        val box = standaloneBox()
        val action = PromptBoxEscapeAction()

        action.actionPerformed(eventFor(action, box))

        assertEquals(listOf("take the keyboard"), whatTheSessionWasAskedToDo)
    }

    fun testEscapeTriggeredBySomethingOtherThanAKeyOnlyHandsOverTheKeyboard() {
        val box = standaloneBox()
        val action = PromptBoxEscapeAction()
        val click = MouseEvent(
            box.component,
            MouseEvent.MOUSE_CLICKED,
            System.currentTimeMillis(),
            0,
            0,
            0,
            1,
            false,
        )

        action.actionPerformed(eventFor(action, box, click))

        assertEquals(listOf("take the keyboard"), whatTheSessionWasAskedToDo)
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

    fun testEscapeInATabsBoxSpendsThePressOnThatTabsSession() {
        val session = FakeSession()
        val box = boxOfALaunchedTab(session)

        val keyEvent = pressEscapeIn(box)

        assertEquals(listOf(keyEvent), session.spentKeyPresses)
    }
}
