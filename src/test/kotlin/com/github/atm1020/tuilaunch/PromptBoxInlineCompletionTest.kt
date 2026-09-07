package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_FILE_NAME
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.intellij.codeInsight.inline.completion.InlineCompletion
import com.intellij.codeInsight.inline.completion.session.InlineCompletionContext
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PromptBoxInlineCompletionTest : BasePlatformTestCase() {

    private var boxDisposable: Disposable? = null

    override fun tearDown() {
        try {
            boxDisposable?.let { Disposer.dispose(it) }
            boxDisposable = null
        } finally {
            super.tearDown()
        }
    }

    private fun newInstalledBox(): PromptBox {
        val disposable = Disposer.newDisposable("PromptBoxInlineCompletionTest")
        boxDisposable = disposable
        return PromptBox(project, disposable).also { it.installEditor() }
    }

    fun testTheBoxEditorIsAMainEditorTheFrameworkTakesForARealFileEditor() {
        val box = newInstalledBox()

        assertEquals(EditorKind.MAIN_EDITOR, box.editor.editorKind)
        assertSame(box.installedFileEditor, TextEditorProvider.getInstance().getTextEditor(box.editor))
        assertTrue(EditorUtil.isRealFileEditor(box.editor))
        assertEquals(PROMPT_BOX_FILE_NAME, box.editor.virtualFile?.name)
    }

    fun testTheBoxDocumentAlreadyHasAPsiFileTheFrameworkCanFindInItsCache() {
        val box = newInstalledBox()

        val cachedPsiFile = PsiDocumentManager.getInstance(project).getCachedPsiFile(box.document)

        assertNotNull(cachedPsiFile)
        assertEquals(PROMPT_BOX_FILE_NAME, cachedPsiFile!!.name)
    }

    fun testTheInlineCompletionHandlerIsInstalledOnTheBoxEditor() {
        val box = newInstalledBox()

        assertNotNull(InlineCompletion.getHandlerOrNull(box.editor))
    }

    fun testAnEmptyBoxHasNoCompletionShowing() {
        val box = newInstalledBox()

        assertNull(InlineCompletionContext.getOrNull(box.editor))
        assertFalse(box.aCompletionIsShowing())
    }

    fun testGhostTextCountsAsACompletionShowing() {
        val box = newInstalledBox()

        showGhostTextIn(box, testRootDisposable)

        assertTrue(box.aCompletionIsShowing())
    }

    fun testDisposingTheBoxReleasesTheEditorItsFileEditorOwns() {
        val box = newInstalledBox()
        val editor = box.editor
        assertNotNull(box.installedFileEditor)

        Disposer.dispose(boxDisposable!!)
        boxDisposable = null

        assertTrue(editor.isDisposed)
        assertNull(box.installedFileEditor)
    }
}
