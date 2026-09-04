package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.PROMPT_HISTORY_NEXT_ACTION_ID
import com.github.atm1020.tuilaunch.action.PROMPT_HISTORY_PREVIOUS_ACTION_ID
import com.github.atm1020.tuilaunch.action.PromptHistoryNextAction
import com.github.atm1020.tuilaunch.action.PromptHistoryPreviousAction
import com.github.atm1020.tuilaunch.action.promptHistoryShortcutSet
import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_DATA_KEY
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.PromptBoxSender
import com.github.atm1020.tuilaunch.prompt.PromptHistoryDirection
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.prompt.promptBoxHoldsFocus
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jdom.Element
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

class PromptBoxHistoryNavigationTest : BasePlatformTestCase() {

    private val boxDisposables = mutableListOf<Disposable>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus
    private lateinit var settingsState: TuiLauncherSettings.State
    private var submitPromptOnSendBeforeTest = true
    private var appendPromptSeparatorBeforeTest = true
    private var focusTuiAfterSendBeforeTest = false

    override fun setUp() {
        super.setUp()
        settingsState = TuiLauncherSettings.getInstance().state
        submitPromptOnSendBeforeTest = settingsState.submitPromptOnSend
        appendPromptSeparatorBeforeTest = settingsState.appendPromptSeparatorOnSend
        focusTuiAfterSendBeforeTest = settingsState.focusTuiAfterPromptBoxSend
        settingsState.submitPromptOnSend = false
        settingsState.appendPromptSeparatorOnSend = false
        settingsState.focusTuiAfterPromptBoxSend = false
        promptBoxFocusRequest = { }
        promptBoxHoldsFocus = { false }
    }

    override fun tearDown() {
        try {
            boxDisposables.forEach { Disposer.dispose(it) }
            promptBoxFocusRequest = focusRequestOutsideThisTest
            promptBoxHoldsFocus = focusCheckOutsideThisTest
            settingsState.submitPromptOnSend = submitPromptOnSendBeforeTest
            settingsState.appendPromptSeparatorOnSend = appendPromptSeparatorBeforeTest
            settingsState.focusTuiAfterPromptBoxSend = focusTuiAfterSendBeforeTest
        } finally {
            super.tearDown()
        }
    }

    private fun promptFile(text: String): Document {
        myFixture.configureByText("PROMPT.md", text)
        return myFixture.editor.document
    }

    private fun boxOver(promptDocument: Document, session: FakeSession = FakeSession()): PromptBox {
        val terminal = session.asTerminalSession()
        val sender = PromptBoxSender(
            project = project,
            sendToSession = { text, _ -> terminal.sendText(text) },
            focusSession = { terminal.requestFocus() },
            promptDocument = { promptDocument },
        )
        val disposable = Disposer.newDisposable("PromptBoxHistoryNavigationTest")
        boxDisposables.add(disposable)
        val box = PromptBox(project, disposable, sender) { promptDocument }
        box.installEditor()
        return box
    }

    private fun appendToThePromptFile(promptDocument: Document, text: String) {
        WriteCommandAction.writeCommandAction(project).run<RuntimeException> {
            promptDocument.setText(promptDocument.text + text)
        }
    }

    private fun moveTheCaretToLine(box: PromptBox, line: Int) {
        box.editor.caretModel.moveToOffset(box.editor.document.getLineStartOffset(line))
    }

    private fun arrowKeyEvent(box: PromptBox, keyCode: Int): KeyEvent = KeyEvent(
        box.component,
        KeyEvent.KEY_PRESSED,
        System.currentTimeMillis(),
        0,
        keyCode,
        KeyEvent.CHAR_UNDEFINED,
    )

    private fun updatedPresentation(action: AnAction, box: PromptBox, inputEvent: InputEvent?): Presentation {
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PROMPT_BOX_DATA_KEY, box)
            .build()
        val event = AnActionEvent.createEvent(
            action,
            context,
            null,
            ActionPlaces.KEYBOARD_SHORTCUT,
            ActionUiKind.NONE,
            inputEvent,
        )
        action.update(event)
        return event.presentation
    }

    private fun declaredHistoryActions(): List<Element> =
        JDOMUtil.load(PromptHistoryPreviousAction::class.java, "/META-INF/plugin.xml")
            .getChild("actions")
            .getChildren("action")
            .filter {
                it.getAttributeValue("id") in setOf(
                    PROMPT_HISTORY_PREVIOUS_ACTION_ID,
                    PROMPT_HISTORY_NEXT_ACTION_ID,
                )
            }

    fun testUpShowsTheNewestRecordedPromptWithTheCaretAtItsStart() {
        val box = boxOver(promptFile("first prompt\n\n---\n\nsecond prompt\n"))

        box.historyPrevious()

        assertEquals("second prompt", box.text)
        assertEquals(0, box.editor.caretModel.offset)
    }

    fun testDownLeavesTheCaretAtTheEndOfThePromptItShows() {
        val box = boxOver(promptFile("first prompt\n\n---\n\nsecond prompt\n"))
        box.historyPrevious()
        box.historyPrevious()

        assertEquals("first prompt", box.text)

        box.historyNext()

        assertEquals("second prompt", box.text)
        assertEquals(box.text.length, box.editor.caretModel.offset)
    }

    fun testTheDraftComesBackWhenDownWalksPastTheNewestPrompt() {
        val box = boxOver(promptFile("first prompt\n"))
        box.text = "a draft being typed"

        box.historyPrevious()

        assertEquals("first prompt", box.text)

        box.historyNext()

        assertEquals("a draft being typed", box.text)
        assertEquals(box.text.length, box.editor.caretModel.offset)
    }

    fun testUpIsIgnoredWhenNoPromptHasBeenRecordedYet() {
        val box = boxOver(promptFile(""))
        box.text = "a draft being typed"

        box.historyPrevious()

        assertEquals("a draft being typed", box.text)
    }

    fun testOnlyTheFirstLineOfTheBoxIsTheEdgeForUp() {
        val box = boxOver(promptFile("first prompt\n"))
        box.text = "one\ntwo\nthree"

        moveTheCaretToLine(box, 0)

        assertTrue(box.caretAtHistoryEdge(PromptHistoryDirection.PREVIOUS))
        assertFalse(box.caretAtHistoryEdge(PromptHistoryDirection.NEXT))

        moveTheCaretToLine(box, 1)

        assertFalse(box.caretAtHistoryEdge(PromptHistoryDirection.PREVIOUS))
        assertFalse(box.caretAtHistoryEdge(PromptHistoryDirection.NEXT))

        moveTheCaretToLine(box, 2)

        assertFalse(box.caretAtHistoryEdge(PromptHistoryDirection.PREVIOUS))
        assertTrue(box.caretAtHistoryEdge(PromptHistoryDirection.NEXT))
    }

    fun testAnEmptyBoxIsAtBothEdgesAtOnce() {
        val box = boxOver(promptFile("first prompt\n"))

        assertTrue(box.caretAtHistoryEdge(PromptHistoryDirection.PREVIOUS))
        assertTrue(box.caretAtHistoryEdge(PromptHistoryDirection.NEXT))
    }

    fun testTheHistoryShortcutsBorrowTheCaretMovementKeys() {
        val upKeystrokes = promptHistoryShortcutSet(PromptHistoryDirection.PREVIOUS).shortcuts
            .filterIsInstance<KeyboardShortcut>()
            .map { it.firstKeyStroke }
        val downKeystrokes = promptHistoryShortcutSet(PromptHistoryDirection.NEXT).shortcuts
            .filterIsInstance<KeyboardShortcut>()
            .map { it.firstKeyStroke }

        assertTrue(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0) in upKeystrokes)
        assertTrue(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0) in downKeystrokes)
    }

    fun testAnArrowKeyInTheMiddleOfAPromptLeavesTheCaretMovementToTheEditor() {
        val box = boxOver(promptFile("first prompt\n"))
        box.text = "one\ntwo\nthree"
        moveTheCaretToLine(box, 1)

        val up = PromptHistoryPreviousAction()
        val down = PromptHistoryNextAction()

        assertFalse(updatedPresentation(up, box, arrowKeyEvent(box, KeyEvent.VK_UP)).isEnabled)
        assertFalse(updatedPresentation(down, box, arrowKeyEvent(box, KeyEvent.VK_DOWN)).isEnabled)
    }

    fun testAnArrowKeyOnAnEdgeLineBrowsesTheHistory() {
        val box = boxOver(promptFile("first prompt\n"))
        box.text = "one\ntwo\nthree"

        moveTheCaretToLine(box, 0)
        val up = PromptHistoryPreviousAction()

        assertTrue(updatedPresentation(up, box, arrowKeyEvent(box, KeyEvent.VK_UP)).isEnabled)

        moveTheCaretToLine(box, 2)
        val down = PromptHistoryNextAction()

        assertTrue(updatedPresentation(down, box, arrowKeyEvent(box, KeyEvent.VK_DOWN)).isEnabled)
    }

    fun testAnyOtherTriggerBrowsesTheHistoryWhereverTheCaretIs() {
        val box = boxOver(promptFile("first prompt\n"))
        box.text = "one\ntwo\nthree"
        moveTheCaretToLine(box, 1)

        val up = PromptHistoryPreviousAction()
        val down = PromptHistoryNextAction()

        assertTrue(updatedPresentation(up, box, null).isEnabled)
        assertTrue(updatedPresentation(down, box, null).isEnabled)
    }

    fun testBothActionsStepAsideWhileGhostTextIsShowing() {
        val box = boxOver(promptFile("first prompt\n"))
        val up = PromptHistoryPreviousAction()
        val down = PromptHistoryNextAction()

        showGhostTextIn(box, testRootDisposable)

        assertFalse(updatedPresentation(up, box, arrowKeyEvent(box, KeyEvent.VK_UP)).isEnabled)
        assertFalse(updatedPresentation(down, box, arrowKeyEvent(box, KeyEvent.VK_DOWN)).isEnabled)
        assertFalse(updatedPresentation(up, box, null).isEnabled)
        assertFalse(updatedPresentation(down, box, null).isEnabled)
    }

    fun testTheActionsAreDisabledWithoutAPromptBox() {
        val action = PromptHistoryPreviousAction()
        val event = AnActionEvent.createEvent(
            action,
            SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(),
            null,
            ActionPlaces.KEYBOARD_SHORTCUT,
            ActionUiKind.NONE,
            null,
        )

        action.update(event)

        assertFalse(event.presentation.isEnabled)
        assertTrue(event.presentation.isVisible)
    }

    fun testTheActionsBrowseTheBoxTheyAreGiven() {
        val box = boxOver(promptFile("first prompt\n\n---\n\nsecond prompt\n"))
        val up = PromptHistoryPreviousAction()
        val down = PromptHistoryNextAction()

        up.actionPerformed(eventWithoutAnInputEvent(up, box))

        assertEquals("second prompt", box.text)

        down.actionPerformed(eventWithoutAnInputEvent(down, box))

        assertEquals("", box.text)
    }

    fun testEachBrowsingSessionRereadsThePromptFile() {
        val promptDocument = promptFile("first prompt\n")
        val box = boxOver(promptDocument)

        box.historyPrevious()
        box.historyNext()

        assertEquals("", box.text)

        appendToThePromptFile(promptDocument, "\n---\n\nsecond prompt\n")
        box.historyPrevious()

        assertEquals("second prompt", box.text)
    }

    fun testASeparatorInsideAFencedBlockStaysPartOfThePrompt() {
        val box = boxOver(promptFile("first prompt\n\n---\n\n```\n---\n```\nafter the fence\n"))

        box.historyPrevious()

        assertEquals("```\n---\n```\nafter the fence", box.text)
    }

    fun testASendEmptiesTheBoxAndForgetsTheDraft() {
        val session = FakeSession()
        val promptDocument = promptFile("first prompt\n")
        val box = boxOver(promptDocument, session)
        box.text = "a draft being typed"
        box.historyPrevious()
        box.text = "a prompt to send"

        box.send()

        assertEquals(listOf("a prompt to send"), session.sentText)
        assertEquals("", box.text)

        box.historyPrevious()

        assertEquals("a prompt to send", box.text)

        box.historyNext()

        assertEquals("", box.text)
    }

    fun testThePluginDescriptorRegistersBothHistoryActionsWithNoKeystrokeAndNoGroup() {
        val declared = declaredHistoryActions().associateBy { it.getAttributeValue("id") }

        assertEquals(
            mapOf(
                PROMPT_HISTORY_PREVIOUS_ACTION_ID to PromptHistoryPreviousAction::class.java.name,
                PROMPT_HISTORY_NEXT_ACTION_ID to PromptHistoryNextAction::class.java.name,
            ),
            declared.mapValues { it.value.getAttributeValue("class") },
        )
        assertEquals(
            mapOf(
                PROMPT_HISTORY_PREVIOUS_ACTION_ID to "TUILaunch Previous Prompt in History",
                PROMPT_HISTORY_NEXT_ACTION_ID to "TUILaunch Next Prompt in History",
            ),
            declared.mapValues { it.value.getAttributeValue("text") },
        )
        declared.values.forEach {
            assertEmpty(it.getChildren("keyboard-shortcut"))
            assertEmpty(it.getChildren("add-to-group"))
        }
    }

    private fun eventWithoutAnInputEvent(action: AnAction, box: PromptBox): AnActionEvent =
        AnActionEvent.createEvent(
            action,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PROMPT_BOX_DATA_KEY, box)
                .build(),
            null,
            ActionPlaces.KEYBOARD_SHORTCUT,
            ActionUiKind.NONE,
            null,
        )
}
