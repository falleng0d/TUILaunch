package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PromptGutterInstaller
import com.github.atm1020.tuilaunch.prompt.PromptGutterRefreshes
import com.github.atm1020.tuilaunch.prompt.promptBlockTextUnder
import com.github.atm1020.tuilaunch.prompt.promptEditorFocusRequest
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.KeyEvent

private const val REFRESH_TIMEOUT_MILLIS = 30_000L

class PromptGutterInstallerTest : BasePlatformTestCase() {

    private lateinit var installer: PromptGutterInstaller
    private lateinit var settingsState: TuiLauncherSettings.State
    private var submitPromptOnSendBeforeTest = true
    private var appendPromptSeparatorBeforeTest = true
    private var focusPromptFileBeforeTest = true
    private val focusRequestOutsideThisTest = promptEditorFocusRequest
    private val caretOffsetsAtFocusRequest = mutableListOf<Int>()

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
        promptEditorFocusRequest = { _, editor -> caretOffsetsAtFocusRequest.add(editor.caretModel.offset) }
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

    private fun launchService(): TuiAppLaunchService {
        val service = project.service<TuiAppLaunchService>()
        closeSessionsLeftOpenByEarlierTests(service)
        service.host = FakeHost()
        return service
    }

    private fun launchFakeSession(): FakeSession {
        val session = FakeSession()
        launchService().apply {
            sessionFactory = FakeFactory(session)
            launchNew("claude", "claude")
        }
        return session
    }

    private fun clickGutterIcon(highlighter: RangeHighlighter) {
        val action = highlighter.gutterIconRenderer!!.clickAction!!
        action.actionPerformed(TestActionEvent.createTestEvent(action))
    }

    private fun gutterHighlightersOf(editor: Editor): List<RangeHighlighter> =
        editor.markupModel.allHighlighters
            .filter { it.gutterIconRenderer != null }
            .sortedBy { it.startOffset }

    private fun gutterHighlighters(): List<RangeHighlighter> = gutterHighlightersOf(myFixture.editor)

    private fun gutterLines(): List<Int> =
        gutterHighlighters().map { myFixture.editor.document.getLineNumber(it.startOffset) }

    private fun gutterHighlightersInEditorOfKind(kind: EditorKind): List<RangeHighlighter> {
        val editorFactory = EditorFactory.getInstance()
        val editor = editorFactory.createEditor(
            myFixture.editor.document,
            project,
            myFixture.file.virtualFile,
            false,
            kind,
        )
        try {
            return gutterHighlightersOf(editor)
        } finally {
            editorFactory.releaseEditor(editor)
        }
    }

    private fun editDocument(edit: (Document) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { edit(myFixture.editor.document) }
        waitForTheGutterToSettle()
    }

    private fun waitForTheGutterToSettle() {
        val deadline = System.currentTimeMillis() + REFRESH_TIMEOUT_MILLIS
        do {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } while (!installer.hasNoPendingRefresh() && System.currentTimeMillis() < deadline)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(installer.hasNoPendingRefresh())
    }

    private fun rewriteDocument(text: String) = editDocument { it.setText(text) }

    fun testEveryPromptBlockGetsAGutterIconOnItsOpeningLine() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n\n---\n\nsecond prompt\n\n---\n")

        assertEquals(listOf(2, 6), gutterLines())
    }

    fun testTheUnclosedPromptAndThePreambleBothGetAnIcon() {
        myFixture.configureByText("PROMPT.md", "preamble\n---\nmiddle\n---\nstill being typed\n")

        assertEquals(listOf(0, 2, 4), gutterLines())
    }

    fun testAMarkdownFileWithAnotherNameGetsNoGutterIcons() {
        myFixture.configureByText("NOTES.md", "---\nfirst prompt\n---\n")

        assertEmpty(gutterHighlighters())
    }

    fun testDiffAndPreviewEditorsOfThePromptFileGetNoGutterIcons() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n")
        assertEquals(listOf(1), gutterLines())

        assertEmpty(gutterHighlightersInEditorOfKind(EditorKind.DIFF))
        assertEmpty(gutterHighlightersInEditorOfKind(EditorKind.PREVIEW))
    }

    fun testEditingInsideAPromptLeavesTheInstalledHighlightersUntouched() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\nsecond prompt\n---\n")
        val installedBeforeEdit = gutterHighlighters()

        rewriteDocument("---\nfirst prompt with more words\n---\nsecond prompt\n---\n")

        val installedAfterEdit = gutterHighlighters()
        assertEquals(installedBeforeEdit.size, installedAfterEdit.size)
        installedBeforeEdit.indices.forEach { assertSame(installedBeforeEdit[it], installedAfterEdit[it]) }
    }

    fun testAddingADelimiterInstallsAnotherGutterIcon() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n")
        assertEquals(listOf(1), gutterLines())

        rewriteDocument("---\nfirst prompt\n---\nsecond prompt\n---\n")

        assertEquals(listOf(1, 3), gutterLines())
    }

    fun testDeletingThroughABlocksFirstCharacterBringsItsGutterIconBack() {
        myFixture.configureByText("PROMPT.md", "---\n    Fix the bug\n---\n")
        assertEquals(listOf(1), gutterLines())

        editDocument { it.deleteString(4, 12) }

        assertEquals("---\nthe bug\n---\n", myFixture.editor.document.text)
        assertEquals(listOf(1), gutterLines())
        assertTrue(gutterHighlighters().all { it.isValid })
    }

    fun testAnEditThatCannotChangeBlockStructureSkipsTheRefreshEntirely() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\nsecond prompt\n---\n")
        assertEquals(listOf(1, 3), gutterLines())
        val refreshesBefore = PromptGutterRefreshes.count()

        editDocument { it.insertString(it.getLineEndOffset(1), " with more words") }

        assertEquals(refreshesBefore, PromptGutterRefreshes.count())
        assertEquals(listOf(1, 3), gutterLines())
    }

    fun testTypingIntoADelimiterLineRefreshesAndMergesTheTwoBlocks() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\nsecond prompt\n---\n")
        assertEquals(listOf(1, 3), gutterLines())
        val refreshesBefore = PromptGutterRefreshes.count()

        editDocument { it.insertString(it.getLineStartOffset(2) + 1, "x") }

        assertTrue(PromptGutterRefreshes.count() > refreshesBefore)
        assertEquals(listOf(1), gutterLines())
    }

    fun testTypingOnABlankLineRefreshesAndMovesTheGutterIconUp() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n---\n")
        assertEquals(listOf(2), gutterLines())
        val refreshesBefore = PromptGutterRefreshes.count()

        editDocument { it.insertString(it.getLineStartOffset(1), "note") }

        assertTrue(PromptGutterRefreshes.count() > refreshesBefore)
        assertEquals(listOf(1), gutterLines())
    }

    fun testClosingAFenceRefreshesAndSwallowsTheDelimiterInsideIt() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n```\n---\nsecond prompt\n``\n---\n")
        assertEquals(listOf(1, 4), gutterLines())
        val refreshesBefore = PromptGutterRefreshes.count()

        editDocument { it.insertString(it.getLineStartOffset(5), "`") }

        assertTrue(PromptGutterRefreshes.count() > refreshesBefore)
        assertEquals(listOf(1), gutterLines())
    }

    fun testGutterIconsRideAnInsertionAboveThemInsteadOfBeingRebuilt() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\nsecond prompt\n---\n")
        val installedBeforeEdit = gutterHighlighters()
        assertEquals(listOf(1, 3), gutterLines())

        editDocument { it.insertString(0, "\n") }

        val installedAfterEdit = gutterHighlighters()
        assertEquals(installedBeforeEdit.size, installedAfterEdit.size)
        installedBeforeEdit.indices.forEach { assertSame(installedBeforeEdit[it], installedAfterEdit[it]) }
        assertEquals(listOf(2, 4), gutterLines())
    }

    fun testAGutterIconStillResolvesItsOwnPromptAfterTheTextAboveItMoves() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\nsecond prompt\n---\n")
        val secondIcon = gutterHighlighters().last()
        assertEquals("second prompt", promptBlockTextUnder(myFixture.editor, secondIcon))

        editDocument { it.insertString(0, "\n") }

        assertSame(secondIcon, gutterHighlighters().last())
        assertEquals(listOf(2, 4), gutterLines())
        assertEquals("second prompt", promptBlockTextUnder(myFixture.editor, secondIcon))
    }

    fun testJoiningTwoBlocksOntoOneLineLeavesASingleGutterIcon() {
        myFixture.configureByText("PROMPT.md", "a\n---\nb\n")
        assertEquals(listOf(0, 2), gutterLines())
        val firstIconBeforeEdit = gutterHighlighters().first()

        editDocument { it.deleteString(1, 6) }

        assertEquals("ab\n", myFixture.editor.document.text)
        assertEquals(listOf(0), gutterLines())
        assertSame(firstIconBeforeEdit, gutterHighlighters().single())
    }

    fun testAnInvalidatedHighlighterIsReplacedWhileTheOtherIconsRide() {
        myFixture.configureByText("PROMPT.md", "---\n    Fix the bug\n---\nsecond prompt\n---\n")
        val installedBeforeEdit = gutterHighlighters()
        assertEquals(listOf(1, 3), gutterLines())

        editDocument { it.deleteString(4, 12) }

        assertEquals("---\nthe bug\n---\nsecond prompt\n---\n", myFixture.editor.document.text)
        assertEquals(listOf(1, 3), gutterLines())
        val installedAfterEdit = gutterHighlighters()
        assertNotSame(installedBeforeEdit[0], installedAfterEdit[0])
        assertSame(installedBeforeEdit[1], installedAfterEdit[1])
        assertTrue(installedAfterEdit.all { it.isValid })
    }

    fun testAnIconWhoseHighlighterWasInvalidatedResolvesNoPrompt() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n  second prompt\n---\n")
        val secondIcon = gutterHighlighters().last()
        assertEquals("  second prompt", promptBlockTextUnder(myFixture.editor, secondIcon))

        editDocument { it.deleteString(21, 30) }

        assertEquals("---\nfirst prompt\n---\nprompt\n---\n", myFixture.editor.document.text)
        assertFalse(secondIcon.isValid)
        assertNull(promptBlockTextUnder(myFixture.editor, secondIcon))
        assertEquals(listOf(1, 3), gutterLines())
    }

    fun testRenamingThePromptFileClearsItsGutterIcons() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n")
        assertEquals(listOf(1), gutterLines())

        val promptFile = myFixture.file.virtualFile
        ApplicationManager.getApplication().runWriteAction { promptFile.rename(this, "NOTES.md") }
        editDocument { it.insertString(it.textLength, "tail\n") }

        assertEmpty(gutterHighlighters())
    }

    fun testClickingTheGutterIconTypesThePromptWithoutSubmittingItWhenSubmitIsOff() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n")
        val session = launchFakeSession()

        clickGutterIcon(gutterHighlighters().single())

        assertEquals(listOf("first prompt"), session.sentText)
        assertEmpty(session.sentKeys)
    }

    fun testClickingTheGutterIconSubmitsThePromptWhenSubmitIsOn() {
        settingsState.submitPromptOnSend = true
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n")
        val session = launchFakeSession()

        clickGutterIcon(gutterHighlighters().single())

        assertEquals(listOf("first prompt"), session.sentText)
        assertEquals(listOf(SentKey(KeyEvent.VK_ENTER, 0, '\r')), session.sentKeys)
    }

    fun testAFailedSendAppendsNoPromptSeparator() {
        settingsState.submitPromptOnSend = true
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n")
        launchService()

        clickGutterIcon(gutterHighlighters().single())

        assertEquals("---\nfirst prompt\n---\n", myFixture.editor.document.text)
    }

    fun testASuccessfulSendAppendsAFreshSlotAndPutsTheCaretInIt() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        val session = launchFakeSession()

        clickGutterIcon(gutterHighlighters().single())
        waitForTheGutterToSettle()

        assertEquals(listOf("first prompt"), session.sentText)
        assertEquals("---\n\nfirst prompt\n\n---\n\n", myFixture.editor.document.text)
        assertEquals(myFixture.editor.document.textLength, myFixture.editor.caretModel.offset)
    }

    fun testTheFreshSlotGetsNoGutterIconOfItsOwn() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        launchFakeSession()
        assertEquals(listOf(2), gutterLines())

        clickGutterIcon(gutterHighlighters().single())
        waitForTheGutterToSettle()

        assertEquals("---\n\nfirst prompt\n\n---\n\n", myFixture.editor.document.text)
        assertEquals(listOf(2), gutterLines())
    }

    fun testClickingTheLastPromptOfSeveralAppendsAFreshSlotAndMovesTheCaretIntoIt() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n\n---\n\nsecond prompt\n")
        val session = launchFakeSession()

        clickGutterIcon(gutterHighlighters().last())
        waitForTheGutterToSettle()

        assertEquals(listOf("second prompt"), session.sentText)
        assertEquals(
            "---\n\nfirst prompt\n\n---\n\nsecond prompt\n\n---\n\n",
            myFixture.editor.document.text,
        )
        assertEquals(myFixture.editor.document.textLength, myFixture.editor.caretModel.offset)
    }

    fun testClickingAnEarlierPromptSendsItAndLeavesTheFileAndTheCaretAlone() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n\n---\n\nsecond prompt\n")
        val session = launchFakeSession()
        val caretOffsetBeforeTheClick = myFixture.editor.caretModel.offset

        clickGutterIcon(gutterHighlighters().first())
        waitForTheGutterToSettle()

        assertEquals(listOf("first prompt"), session.sentText)
        assertEquals("---\n\nfirst prompt\n\n---\n\nsecond prompt\n", myFixture.editor.document.text)
        assertEquals(caretOffsetBeforeTheClick, myFixture.editor.caretModel.offset)
    }

    fun testClickingTwiceDoesNotStackASecondPromptSeparator() {
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        val session = launchFakeSession()

        clickGutterIcon(gutterHighlighters().single())
        waitForTheGutterToSettle()
        clickGutterIcon(gutterHighlighters().single())
        waitForTheGutterToSettle()

        assertEquals("---\n\nfirst prompt\n\n---\n\n", myFixture.editor.document.text)
        assertEquals(listOf("first prompt", "first prompt"), session.sentText)
    }

    fun testTheFileIsLeftAloneWhenAppendingSeparatorsIsOff() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        launchFakeSession()

        clickGutterIcon(gutterHighlighters().single())
        waitForTheGutterToSettle()

        assertEquals("---\n\nfirst prompt\n", myFixture.editor.document.text)
    }

    fun testASendKeepsFocusInThePromptFileAndOutOfTheSessionWhenTheSettingIsOn() {
        settingsState.focusPromptFileAfterSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        val session = launchFakeSession()
        val sessionFocusCountBeforeTheClick = session.focusCount

        clickGutterIcon(gutterHighlighters().single())

        assertEquals(1, caretOffsetsAtFocusRequest.size)
        assertEquals(sessionFocusCountBeforeTheClick, session.focusCount)
    }

    fun testASendFocusesTheSessionAndNotThePromptFileWhenTheSettingIsOff() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        val session = launchFakeSession()
        val sessionFocusCountBeforeTheClick = session.focusCount

        clickGutterIcon(gutterHighlighters().single())

        assertEmpty(caretOffsetsAtFocusRequest)
        assertEquals(sessionFocusCountBeforeTheClick + 1, session.focusCount)
    }

    fun testAFailedSendRequestsNoFocusAtAll() {
        settingsState.focusPromptFileAfterSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        launchService()

        clickGutterIcon(gutterHighlighters().single())

        assertEmpty(caretOffsetsAtFocusRequest)
    }

    fun testFocusIsRequestedOnlyAfterTheCaretHasMovedIntoTheFreshSlot() {
        settingsState.focusPromptFileAfterSend = true
        settingsState.appendPromptSeparatorOnSend = true
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        launchFakeSession()

        clickGutterIcon(gutterHighlighters().single())
        waitForTheGutterToSettle()

        assertEquals("---\n\nfirst prompt\n\n---\n\n", myFixture.editor.document.text)
        assertEquals(listOf(myFixture.editor.document.textLength), caretOffsetsAtFocusRequest)
    }
}
