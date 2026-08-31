package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PromptGutterInstaller
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PromptGutterInstallerTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        EditorFactory.getInstance().addEditorFactoryListener(PromptGutterInstaller(), testRootDisposable)
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
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
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

    fun testRenamingThePromptFileClearsItsGutterIcons() {
        myFixture.configureByText("PROMPT.md", "---\nfirst prompt\n---\n")
        assertEquals(listOf(1), gutterLines())

        val promptFile = myFixture.file.virtualFile
        ApplicationManager.getApplication().runWriteAction { promptFile.rename(this, "NOTES.md") }
        editDocument { it.insertString(it.textLength, "tail\n") }

        assertEmpty(gutterHighlighters())
    }
}
