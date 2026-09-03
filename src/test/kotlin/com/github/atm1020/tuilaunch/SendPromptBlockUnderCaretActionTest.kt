package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.SendPromptBlockUnderCaretAction
import com.github.atm1020.tuilaunch.prompt.PromptGutterInstaller
import com.github.atm1020.tuilaunch.prompt.promptBlockTextUnder
import com.github.atm1020.tuilaunch.prompt.promptEditorFocusRequest
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.KeyEvent

private const val REFRESH_TIMEOUT_MILLIS = 30_000L

class SendPromptBlockUnderCaretActionTest : BasePlatformTestCase() {

    private lateinit var installer: PromptGutterInstaller
    private lateinit var settingsState: TuiLauncherSettings.State
    private var submitPromptOnSendBeforeTest = true
    private var appendPromptSeparatorBeforeTest = true
    private var focusPromptFileBeforeTest = true
    private val focusRequestOutsideThisTest = promptEditorFocusRequest

    override fun setUp() {
        super.setUp()
        installer = PromptGutterInstaller()
        EditorFactory.getInstance().addEditorFactoryListener(installer, testRootDisposable)
        settingsState = TuiLauncherSettings.getInstance().state
        submitPromptOnSendBeforeTest = settingsState.submitPromptOnSend
        appendPromptSeparatorBeforeTest = settingsState.appendPromptSeparatorOnSend
        focusPromptFileBeforeTest = settingsState.focusPromptFileAfterSend
        settingsState.submitPromptOnSend = false
        settingsState.appendPromptSeparatorOnSend = false
        settingsState.focusPromptFileAfterSend = false
        promptEditorFocusRequest = { _, _ -> }
    }

    override fun tearDown() {
        try {
            closeSessionsLeftOpenByEarlierTests(project.service<TuiAppLaunchService>())
            promptEditorFocusRequest = focusRequestOutsideThisTest
            settingsState.submitPromptOnSend = submitPromptOnSendBeforeTest
            settingsState.appendPromptSeparatorOnSend = appendPromptSeparatorBeforeTest
            settingsState.focusPromptFileAfterSend = focusPromptFileBeforeTest
        } finally {
            super.tearDown()
        }
    }

    private fun launchFakeSession(): FakeSession {
        val session = FakeSession()
        val service = project.service<TuiAppLaunchService>()
        closeSessionsLeftOpenByEarlierTests(service)
        service.host = FakeHost()
        service.sessionFactory = FakeFactory(session)
        service.launchNew("claude", "claude")
        return session
    }

    private fun eventFor(action: AnAction, editor: Editor = myFixture.editor): AnActionEvent =
        TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .build(),
        )

    private fun updatedPresentation(editor: Editor = myFixture.editor): Presentation {
        val action = SendPromptBlockUnderCaretAction()
        val event = eventFor(action, editor)
        action.update(event)
        return event.presentation
    }

    private fun presentationInEditorOfKind(kind: EditorKind): Presentation {
        val editorFactory = EditorFactory.getInstance()
        val editor = editorFactory.createEditor(
            myFixture.editor.document,
            project,
            myFixture.file.virtualFile,
            false,
            kind,
        )
        try {
            editor.caretModel.moveToOffset(myFixture.editor.caretModel.offset)
            return updatedPresentation(editor)
        } finally {
            editorFactory.releaseEditor(editor)
        }
    }

    private fun sendTheBlockUnderTheCaret() {
        val action = SendPromptBlockUnderCaretAction()
        action.actionPerformed(eventFor(action))
    }

    private fun gutterHighlighters(): List<RangeHighlighter> =
        myFixture.editor.markupModel.allHighlighters
            .filter { it.gutterIconRenderer != null }
            .sortedBy { it.startOffset }

    private fun waitForTheGutterToSettle() {
        val deadline = System.currentTimeMillis() + REFRESH_TIMEOUT_MILLIS
        do {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } while (!installer.hasNoPendingRefresh() && System.currentTimeMillis() < deadline)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(installer.hasNoPendingRefresh())
    }

    fun testTheActionIsDisabledButStillVisibleInAMarkdownFileWithAnotherName() {
        myFixture.configureByText("NOTES.md", "---\n\nfirst <caret>prompt\n")

        val presentation = updatedPresentation()

        assertFalse(presentation.isEnabled)
        assertTrue(presentation.isVisible)
    }

    fun testTheActionIsDisabledInDiffPreviewAndConsoleEditorsOfThePromptFile() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst <caret>prompt\n")
        assertTrue(updatedPresentation().isEnabled)

        assertFalse(presentationInEditorOfKind(EditorKind.DIFF).isEnabled)
        assertFalse(presentationInEditorOfKind(EditorKind.PREVIEW).isEnabled)
        assertFalse(presentationInEditorOfKind(EditorKind.CONSOLE).isEnabled)
    }

    fun testTheActionIsDisabledWhenTheCaretSitsOnADivider() {
        myFixture.configureByText("PROMPT.md", "first prompt\n-<caret>--\nsecond prompt\n")

        assertFalse(updatedPresentation().isEnabled)
    }

    fun testTheActionIsDisabledWhenTheCaretSitsOnABlankLineAboveAPrompt() {
        myFixture.configureByText("PROMPT.md", "---\n<caret>\nfirst prompt\n")

        assertFalse(updatedPresentation().isEnabled)
    }

    fun testTheActionIsEnabledWhenTheCaretIsInsideAPrompt() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst <caret>prompt\n")

        val presentation = updatedPresentation()

        assertTrue(presentation.isEnabled)
        assertTrue(presentation.isVisible)
    }

    fun testTheActionSendsThePromptTheCaretIsIn() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n\n---\n\nsecond <caret>prompt\n")
        val session = launchFakeSession()

        sendTheBlockUnderTheCaret()

        assertEquals(listOf("second prompt"), session.sentText)
        assertEmpty(session.sentKeys)
    }

    fun testTheActionSubmitsThePromptWhenSubmitIsOn() {
        settingsState.submitPromptOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst <caret>prompt\n")
        val session = launchFakeSession()

        sendTheBlockUnderTheCaret()

        assertEquals(listOf("first prompt"), session.sentText)
        assertEquals(listOf(SentKey(KeyEvent.VK_ENTER, 0, '\r')), session.sentKeys)
    }

    fun testTheActionSendsExactlyWhatTheGutterIconOnThatPromptWouldSend() {
        myFixture.configureByText(
            "PROMPT.md",
            "---\n\nfirst prompt\n\n---\n\nsecond prompt\nspanning two lines<caret>\n",
        )
        val session = launchFakeSession()
        val gutterIconOfTheSecondPrompt = gutterHighlighters().last()

        sendTheBlockUnderTheCaret()

        assertEquals(
            listOf(promptBlockTextUnder(myFixture.editor, gutterIconOfTheSecondPrompt)),
            session.sentText,
        )
    }

    fun testSendingFromTheCaretTwiceDoesNotResendTheFirstPrompt() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst <caret>prompt\n")
        val session = launchFakeSession()

        sendTheBlockUnderTheCaret()
        waitForTheGutterToSettle()

        assertEquals("---\n\nfirst prompt\n\n---\n\n", myFixture.editor.document.text)
        assertEquals(myFixture.editor.document.textLength, myFixture.editor.caretModel.offset)
        assertFalse(updatedPresentation().isEnabled)

        sendTheBlockUnderTheCaret()

        assertEquals(listOf("first prompt"), session.sentText)
    }

    fun testSendingTheLastPromptFromTheCaretAppendsAFreshSlotAndMovesTheCaretIntoIt() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n\n---\n\nsecond <caret>prompt\n")
        val session = launchFakeSession()

        sendTheBlockUnderTheCaret()
        waitForTheGutterToSettle()

        assertEquals(listOf("second prompt"), session.sentText)
        assertEquals(
            "---\n\nfirst prompt\n\n---\n\nsecond prompt\n\n---\n\n",
            myFixture.editor.document.text,
        )
        assertEquals(myFixture.editor.document.textLength, myFixture.editor.caretModel.offset)
    }

    fun testSendingAnEarlierPromptFromTheCaretLeavesTheFileAndTheCaretAlone() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst <caret>prompt\n\n---\n\nsecond prompt\n")
        val session = launchFakeSession()
        val caretOffsetBeforeTheSend = myFixture.editor.caretModel.offset

        sendTheBlockUnderTheCaret()
        waitForTheGutterToSettle()

        assertEquals(listOf("first prompt"), session.sentText)
        assertEquals("---\n\nfirst prompt\n\n---\n\nsecond prompt\n", myFixture.editor.document.text)
        assertEquals(caretOffsetBeforeTheSend, myFixture.editor.caretModel.offset)
        assertTrue(updatedPresentation().isEnabled)
    }

    fun testSendingWithTheCaretOutsideEveryPromptSendsNothing() {
        myFixture.configureByText("PROMPT.md", "first prompt\n-<caret>--\nsecond prompt\n")
        val session = launchFakeSession()

        sendTheBlockUnderTheCaret()

        assertEmpty(session.sentText)
    }
}
