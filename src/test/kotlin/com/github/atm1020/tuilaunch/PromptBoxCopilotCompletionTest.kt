package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.CopilotInlineCompletionProvider
import com.github.atm1020.tuilaunch.copilot.InlineCompletionItem
import com.github.atm1020.tuilaunch.copilot.InlineCompletionTriggerKind
import com.github.atm1020.tuilaunch.copilot.LspPosition
import com.github.atm1020.tuilaunch.copilot.LspRange
import com.github.atm1020.tuilaunch.copilot.PromptBoxCopilotEditorListener
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.InlineCompletionHandler
import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

private const val ACCEPT_COMMAND = "github.copilot.didAcceptCompletionItem"
private const val COMPLETION_UUID = "5f5e1a3a-0000-4000-8000-000000000001"
private const val DRAFT = "Fix the "

class PromptBoxCopilotCompletionTest : BasePlatformTestCase() {

    private var boxDisposable: Disposable? = null
    private lateinit var settings: FakePromptBoxCompletionSettings
    private var server = FakeCopilotCompletionServer()
    private var backend = FakeCopilotCompletionBackend()

    private val wholeLine = InlineCompletionItem(
        insertText = "Fix the crash in the parser",
        range = LspRange(LspPosition(0, 0), LspPosition(0, DRAFT.length)),
        acceptCommand = ACCEPT_COMMAND,
        acceptArguments = listOf(COMPLETION_UUID),
    )

    override fun setUp() {
        super.setUp()
        settings = FakePromptBoxCompletionSettings()
        answerWith(wholeLine)
    }

    override fun tearDown() {
        try {
            boxDisposable?.let { Disposer.dispose(it) }
            boxDisposable = null
        } finally {
            super.tearDown()
        }
    }

    private fun answerWith(vararg items: InlineCompletionItem) {
        server = FakeCopilotCompletionServer(items = items.toList())
        backend = FakeCopilotCompletionBackend(server = server)
    }

    private fun boxWithADraft(promptFileText: String? = null): PromptBox {
        val disposable = Disposer.newDisposable("PromptBoxCopilotCompletionTest")
        boxDisposable = disposable
        val promptDocument: Document? = promptFileText?.let { EditorFactory.getInstance().createDocument(it) }
        val box = PromptBox(project, disposable, promptDocument = { promptDocument })
        box.installEditor()
        box.text = DRAFT
        box.editor.caretModel.moveToOffset(DRAFT.length)
        return box
    }

    private fun showACompletionIn(box: PromptBox) {
        val provider = CopilotInlineCompletionProvider(settings, { backend }, announce = { })
        InlineCompletionHandler.registerTestHandler(provider, testRootDisposable)
        askForAnInlineCompletionIn(box.editor, provider.id)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    private fun openedDocument(): CopilotServerCall.Opened =
        server.calls.filterIsInstance<CopilotServerCall.Opened>().single()

    private fun handlerOf(box: PromptBox): InlineCompletionHandler =
        requireNotNull(InlineCompletion.getHandlerOrNull(box.editor))

    fun testTheGhostTextIsWhatTheServerAddsToTheTypedLine() {
        val box = boxWithADraft()

        showACompletionIn(box)

        assertEquals("crash in the parser", InlineCompletionContext.getOrNull(box.editor)?.textToInsert())
        assertTrue(box.aCompletionIsShowing())
    }

    fun testTheDraftReachesTheServerAsAVirtualMarkdownDocument() {
        val box = boxWithADraft()

        showACompletionIn(box)

        val opened = openedDocument()
        val asked = server.calls.filterIsInstance<CopilotServerCall.Asked>().single()
        assertEquals("markdown", opened.languageId)
        assertEquals(DRAFT, opened.text)
        assertTrue(opened.uri.startsWith("tuilaunch://prompt-box/"))
        assertEquals(opened.uri, asked.uri)
        assertEquals(opened.version, asked.version)
        assertEquals(LspPosition(0, DRAFT.length), asked.position)
        assertEquals(InlineCompletionTriggerKind.INVOKED, asked.triggerKind)
    }

    fun testTheServerIsToldWhichCompletionWasShown() {
        val box = boxWithADraft()

        showACompletionIn(box)

        assertEquals(
            listOf(CopilotServerCall.Shown(wholeLine)),
            server.calls.filterIsInstance<CopilotServerCall.Shown>(),
        )
    }

    fun testAcceptingTheGhostTextSendsTheAcceptCommandWithASingleArgument() {
        val box = boxWithADraft()
        showACompletionIn(box)

        val handler = handlerOf(box)
        WriteCommandAction.runWriteCommandAction(project) { handler.insert() }
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val executed = server.calls.filterIsInstance<CopilotServerCall.Executed>().single()
        assertEquals(ACCEPT_COMMAND, executed.command)
        assertEquals(listOf(COMPLETION_UUID), executed.arguments)
        assertEquals("Fix the crash in the parser", box.text)
    }

    fun testNothingIsShownWhenTheServerReplacesTheLineWithSomethingElse() {
        answerWith(wholeLine.copy(insertText = "Repair the crash in the parser"))
        val box = boxWithADraft()

        showACompletionIn(box)

        assertNull(InlineCompletionContext.getOrNull(box.editor)?.textToInsert())
    }

    fun testThePromptFileHistoryTravelsWithTheDraft() {
        settings.includePromptHistory = true
        val box = boxWithADraft("first prompt\n\n---\n\nsecond prompt\n\n---\n\n")

        showACompletionIn(box)

        val asked = server.calls.filterIsInstance<CopilotServerCall.Asked>().single()
        assertEquals(
            "first prompt\n\n---\n\nsecond prompt\n\n---\n\n$DRAFT",
            openedDocument().text,
        )
        assertEquals(LspPosition(8, DRAFT.length), asked.position)
    }

    fun testReleasingTheBoxEditorClosesTheVirtualDocument() {
        val box = boxWithADraft()
        EditorFactory.getInstance()
            .addEditorFactoryListener(PromptBoxCopilotEditorListener { backend }, testRootDisposable)
        showACompletionIn(box)
        val uri = openedDocument().uri

        Disposer.dispose(boxDisposable!!)
        boxDisposable = null

        assertEquals(
            listOf(CopilotServerCall.Closed(uri)),
            server.calls.filterIsInstance<CopilotServerCall.Closed>(),
        )
    }
}
