package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.findOrCreatePromptFile
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.PromptBoxPanel
import com.github.atm1020.tuilaunch.prompt.PromptBoxSender
import com.github.atm1020.tuilaunch.prompt.promptBoxFocusRequest
import com.github.atm1020.tuilaunch.prompt.promptBoxHoldsFocus
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.event.KeyEvent

class PromptBoxSenderTest : BasePlatformTestCase() {

    private val services = mutableListOf<TuiAppLaunchService>()
    private val boxDisposables = mutableListOf<Disposable>()
    private val focusRequestOutsideThisTest = promptBoxFocusRequest
    private val focusCheckOutsideThisTest = promptBoxHoldsFocus
    private val focusedBoxes = mutableListOf<PromptBox>()
    private lateinit var settingsState: TuiLauncherSettings.State
    private lateinit var editorSettings: EditorSettingsExternalizable
    private var submitPromptOnSendBeforeTest = true
    private var appendPromptSeparatorBeforeTest = true
    private var focusTuiAfterSendBeforeTest = false
    private var stripTrailingSpacesBeforeTest = EditorSettingsExternalizable.STRIP_TRAILING_SPACES_CHANGED
    private var removeTrailingBlankLinesBeforeTest = false
    private var ensureNewLineAtEofBeforeTest = false

    override fun setUp() {
        super.setUp()
        editorSettings = EditorSettingsExternalizable.getInstance()
        stripTrailingSpacesBeforeTest = editorSettings.stripTrailingSpaces
        removeTrailingBlankLinesBeforeTest = editorSettings.isRemoveTrailingBlankLines
        ensureNewLineAtEofBeforeTest = editorSettings.isEnsureNewLineAtEOF
        settingsState = TuiLauncherSettings.getInstance().state
        submitPromptOnSendBeforeTest = settingsState.submitPromptOnSend
        appendPromptSeparatorBeforeTest = settingsState.appendPromptSeparatorOnSend
        focusTuiAfterSendBeforeTest = settingsState.focusTuiAfterPromptBoxSend
        settingsState.apply {
            tuiApps.clear()
            restoreOpenTabs = false
            promptBoxVisible = false
            promptBoxPercent = 30
            rememberPromptBoxVisibilityPerApp = false
            rememberPromptBoxSizePerApp = false
            submitPromptOnSend = false
            appendPromptSeparatorOnSend = false
            focusTuiAfterPromptBoxSend = false
        }
        promptBoxFocusRequest = { box -> focusedBoxes.add(box) }
        promptBoxHoldsFocus = { false }
    }

    override fun tearDown() {
        try {
            services.forEach { closeSessionsLeftOpenByEarlierTests(it) }
            boxDisposables.forEach { Disposer.dispose(it) }
            promptBoxFocusRequest = focusRequestOutsideThisTest
            promptBoxHoldsFocus = focusCheckOutsideThisTest
            settingsState.submitPromptOnSend = submitPromptOnSendBeforeTest
            settingsState.appendPromptSeparatorOnSend = appendPromptSeparatorBeforeTest
            settingsState.focusTuiAfterPromptBoxSend = focusTuiAfterSendBeforeTest
            editorSettings.stripTrailingSpaces = stripTrailingSpacesBeforeTest
            editorSettings.isRemoveTrailingBlankLines = removeTrailingBlankLinesBeforeTest
            editorSettings.isEnsureNewLineAtEOF = ensureNewLineAtEofBeforeTest
        } finally {
            super.tearDown()
        }
    }

    private fun launchTabWithABox(session: FakeSession): PromptBox {
        val service = TuiAppLaunchService(project)
        val host = FakeHost()
        service.host = host
        service.sessionFactory = FakeFactory(session)
        services.add(service)
        service.launchNew("claude", "claude")
        val splitter = host.componentOf(host.tabs.single()) as Splitter
        return (splitter.secondComponent as PromptBoxPanel).promptBox
    }

    private fun boxSending(session: FakeSession, promptDocument: () -> Document?): PromptBox {
        val terminal = session.asTerminalSession()
        val sender = PromptBoxSender(
            project = project,
            sendToSession = { text, submit ->
                terminal.sendText(text).also { if (it && submit) terminal.sendKey(KeyEvent.VK_ENTER, 0, '\r') }
            },
            focusSession = { terminal.requestFocus() },
            promptDocument = promptDocument,
        )
        val disposable = Disposer.newDisposable("PromptBoxSenderTest")
        boxDisposables.add(disposable)
        return PromptBox(project, disposable, sender)
    }

    private fun writePromptFile(text: String) {
        val promptFile = findOrCreatePromptFile(project)!!
        runWriteAction { VfsUtil.saveText(promptFile, text) }
    }

    private fun promptFileDocument(): Document {
        val promptFile = findOrCreatePromptFile(project)!!
        return FileDocumentManager.getInstance().getDocument(promptFile)!!
    }

    private fun promptFileText(): String = promptFileDocument().text

    private fun promptFileTextOnDisk(): String = VfsUtil.loadText(findOrCreatePromptFile(project)!!)

    fun testSendingTypesThePromptIntoItsOwnSessionAndClearsTheBox() {
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.text = "Fix the bug"

        box.send()

        assertEquals(listOf("Fix the bug"), session.sentText)
        assertEmpty(session.sentKeys)
        assertEquals("", box.text)
    }

    fun testSendingSubmitsThePromptWhenSubmitIsOn() {
        settingsState.submitPromptOnSend = true
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.text = "Fix the bug"

        box.send()

        assertEquals(listOf("Fix the bug"), session.sentText)
        assertEquals(listOf(SentKey(KeyEvent.VK_ENTER, 0, '\r')), session.sentKeys)
    }

    fun testOnlyTheTrailingNewlinesOfTheBoxAreTrimmedAway() {
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.text = "  Fix the bug  \n\n\n"

        box.send()

        assertEquals(listOf("  Fix the bug  "), session.sentText)
    }

    fun testASentPromptIsAppendedToThePromptFileAndSavedToDisk() {
        writePromptFile("---\n\nfirst prompt\n")
        val box = launchTabWithABox(FakeSession())
        box.text = "second prompt"

        box.send()

        assertEquals("---\n\nfirst prompt\n\n---\n\nsecond prompt", promptFileText())
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(promptFileDocument()))
        assertEquals("---\n\nfirst prompt\n\n---\n\nsecond prompt", promptFileTextOnDisk())
    }

    fun testASentPromptLeavesAFreshSlotBehindWhenTheSettingIsOn() {
        settingsState.appendPromptSeparatorOnSend = true
        writePromptFile("---\n\nfirst prompt\n")
        val box = launchTabWithABox(FakeSession())
        box.text = "second prompt"

        box.send()

        assertEquals("---\n\nfirst prompt\n\n---\n\nsecond prompt\n\n---\n\n", promptFileText())
    }

    fun testTheFreshSlotSurvivesASaveThatStripsTrailingBlankLines() {
        settingsState.appendPromptSeparatorOnSend = true
        editorSettings.stripTrailingSpaces = EditorSettingsExternalizable.STRIP_TRAILING_SPACES_WHOLE
        editorSettings.isRemoveTrailingBlankLines = true
        editorSettings.isEnsureNewLineAtEOF = true
        writePromptFile("---\n\nfirst prompt\n")
        val box = launchTabWithABox(FakeSession())
        box.text = "second prompt"

        box.send()

        val recorded = "---\n\nfirst prompt\n\n---\n\nsecond prompt\n\n---\n\n"
        assertEquals(recorded, promptFileText())
        assertFalse(FileDocumentManager.getInstance().isDocumentUnsaved(promptFileDocument()))
        assertEquals(recorded, promptFileTextOnDisk())
    }

    fun testTheFirstPromptOfAnEmptyProjectStartsThePromptFile() {
        val box = launchTabWithABox(FakeSession())
        box.text = "Fix the bug"

        box.send()

        assertEquals("Fix the bug", promptFileText())
    }

    fun testARefusedSendLeavesThePromptFileAndTheBoxUntouched() {
        writePromptFile("---\n\nfirst prompt\n")
        val session = FakeSession(terminalAcceptsText = false)
        val box = launchTabWithABox(session)
        box.text = "second prompt"
        val focusCountBeforeTheSend = session.focusCount

        box.send()

        assertEquals(listOf("second prompt"), session.sentText)
        assertEquals("second prompt", box.text)
        assertEquals("---\n\nfirst prompt\n", promptFileText())
        assertEmpty(focusedBoxes)
        assertEquals(focusCountBeforeTheSend, session.focusCount)
    }

    fun testABlankBoxSendsNothingAndRecordsNothing() {
        writePromptFile("---\n\nfirst prompt\n")
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.text = "\n  \n"

        box.send()

        assertEmpty(session.sentText)
        assertEquals("---\n\nfirst prompt\n", promptFileText())
        assertEmpty(focusedBoxes)
        assertEquals("\n  \n", box.text)
    }

    fun testAnEmptyBoxSendsNothing() {
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.installEditor()

        box.send()

        assertEmpty(session.sentText)
        assertEmpty(focusedBoxes)
    }

    fun testAfterASendTheBoxKeepsTheKeyboard() {
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.text = "Fix the bug"
        val focusCountBeforeTheSend = session.focusCount

        box.send()

        assertEquals(listOf(box), focusedBoxes)
        assertEquals(focusCountBeforeTheSend, session.focusCount)
    }

    fun testAfterASendTheSessionTakesTheKeyboardWhenTheSettingIsOn() {
        settingsState.focusTuiAfterPromptBoxSend = true
        val session = FakeSession()
        val box = launchTabWithABox(session)
        box.text = "Fix the bug"
        val focusCountBeforeTheSend = session.focusCount

        box.send()

        assertEmpty(focusedBoxes)
        assertEquals(focusCountBeforeTheSend + 1, session.focusCount)
    }

    fun testTheInjectedPromptDocumentIsTheOneTheSendAppendsTo() {
        myFixture.configureByText("PROMPT.md", "---\n\nfirst prompt\n")
        val session = FakeSession()
        val box = boxSending(session) { myFixture.editor.document }
        box.text = "second prompt"

        box.send()

        assertEquals("---\n\nfirst prompt\n\n---\n\nsecond prompt", myFixture.editor.document.text)
        assertEquals(listOf("second prompt"), session.sentText)
        assertEquals("", box.text)
    }

    fun testAPromptWithNowhereToBeRecordedIsStillSentAndClearsTheBox() {
        val session = FakeSession()
        val box = boxSending(session) { null }
        box.text = "Fix the bug"

        box.send()

        assertEquals(listOf("Fix the bug"), session.sentText)
        assertEquals("", box.text)
        assertEquals(listOf(box), focusedBoxes)
    }
}
