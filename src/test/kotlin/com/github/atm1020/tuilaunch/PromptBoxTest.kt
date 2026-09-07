package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_DATA_KEY
import com.github.atm1020.tuilaunch.prompt.PROMPT_BOX_FILE_NAME
import com.github.atm1020.tuilaunch.prompt.PromptBox
import com.github.atm1020.tuilaunch.prompt.PromptBoxSendButton
import com.github.atm1020.tuilaunch.prompt.SEND_PROMPT_BOX_ACTION_ID
import com.github.atm1020.tuilaunch.prompt.editorShowsThePromptFile
import com.github.atm1020.tuilaunch.prompt.promptBoxPlaceholder
import com.github.atm1020.tuilaunch.prompt.promptBoxSendButtonQuietMillis
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataMap
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DataSnapshotProvider
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle
import javax.swing.JLayeredPane

private const val TEST_QUIET_MILLIS = 100
private const val REAPPEAR_TIMEOUT_MILLIS = 30_000L

private class RecordingDataSink : DataSink {

    private val published = mutableMapOf<DataKey<*>, Any?>()
    private val nothingElseIsPublished = object : DataMap {
        override fun <T : Any> get(key: DataKey<T>): T? = null
    }

    operator fun <T : Any> get(key: DataKey<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return published[key] as T?
    }

    override fun <T : Any> set(key: DataKey<T>, data: T?) {
        published[key] = data
    }

    override fun <T : Any> setNull(key: DataKey<T>) {
        published[key] = null
    }

    override fun <T : Any> lazyValue(key: DataKey<T>, data: (DataMap) -> T?) {
        published[key] = data(nothingElseIsPublished)
    }

    override fun <T : Any> lazyNull(key: DataKey<T>) {
        published[key] = null
    }

    override fun uiDataSnapshot(provider: UiDataProvider) {
        provider.uiDataSnapshot(this)
    }

    override fun dataSnapshot(provider: DataSnapshotProvider) {
        provider.dataSnapshot(this)
    }

    override fun uiDataSnapshot(provider: DataProvider) {
        DataSink.uiDataSnapshot(this, provider)
    }
}

class PromptBoxTest : BasePlatformTestCase() {

    private var boxDisposable: Disposable? = null
    private val quietMillisOutsideThisTest = promptBoxSendButtonQuietMillis

    override fun setUp() {
        super.setUp()
        promptBoxSendButtonQuietMillis = TEST_QUIET_MILLIS
        registerTheSendPromptBoxAction(testRootDisposable)
    }

    override fun tearDown() {
        try {
            boxDisposable?.let { Disposer.dispose(it) }
            boxDisposable = null
            promptBoxSendButtonQuietMillis = quietMillisOutsideThisTest
        } finally {
            super.tearDown()
        }
    }

    private fun newPromptBox(): PromptBox {
        val disposable = Disposer.newDisposable("PromptBoxTest")
        boxDisposable = disposable
        return PromptBox(project, disposable)
    }

    private fun editorCount(): Int = EditorFactory.getInstance().allEditors.size

    private fun waitForTheSendButtonToComeBack(sendButton: PromptBoxSendButton) {
        val deadline = System.currentTimeMillis() + REAPPEAR_TIMEOUT_MILLIS
        do {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } while (sendButton.isWaitingToReappear() && System.currentTimeMillis() < deadline)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse(sendButton.isWaitingToReappear())
    }

    fun testTheBoxEditsItsOwnLightFileWithAnEditableTextType() {
        val box = newPromptBox()

        val file = FileDocumentManager.getInstance().getFile(box.editor.document)!!

        assertEquals(PROMPT_BOX_FILE_NAME, file.name)
        assertFalse(file.fileType.isBinary)
        assertFalse(editorShowsThePromptFile(box.editor))
    }

    fun testTheEditorIsBuiltOnlyWhenTheBoxIsUsed() {
        val editorsBeforeTheBox = editorCount()

        val box = newPromptBox()

        assertNull(box.installedEditor)
        assertEquals(editorsBeforeTheBox, editorCount())

        box.installEditor()

        assertNotNull(box.installedEditor)
        assertEquals(editorsBeforeTheBox + 1, editorCount())
    }

    fun testInstallingTheEditorTwiceKeepsTheSameEditor() {
        val box = newPromptBox()

        assertSame(box.installEditor(), box.installEditor())
    }

    fun testThePanelPublishesTheBoxTheEditorAndAFileEditor() {
        val box = newPromptBox()
        box.installEditor()

        val published = RecordingDataSink()
        (box.component as UiDataProvider).uiDataSnapshot(published)

        assertSame(box, published[PROMPT_BOX_DATA_KEY])
        assertSame(box.editor, published[CommonDataKeys.EDITOR])
        assertNotNull(published[PlatformCoreDataKeys.FILE_EDITOR])
    }

    fun testAPanelWithoutAnEditorPublishesNothing() {
        val box = newPromptBox()

        val published = RecordingDataSink()
        (box.component as UiDataProvider).uiDataSnapshot(published)

        assertNull(published[PROMPT_BOX_DATA_KEY])
        assertNull(published[CommonDataKeys.EDITOR])
    }

    fun testTheTextIsWrittenAndClearedThroughTheDocument() {
        val box = newPromptBox()

        box.text = "a prompt"

        assertEquals("a prompt", box.text)
        assertEquals("a prompt", box.editor.document.text)

        box.clear()

        assertEquals("", box.text)
        assertEquals("", box.editor.document.text)
    }

    fun testTheEditorShowsProseWithoutAGutterAndKeepsThoseSettingsAfterLoading() {
        val box = newPromptBox()
        box.installEditor()
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        val settings = box.editor.settings

        assertTrue(settings.isUseSoftWraps)
        assertFalse(settings.isLineNumbersShown)
        assertFalse(settings.isLineMarkerAreaShown)
        assertFalse(settings.areGutterIconsShown())
        assertTrue(settings.isFoldingOutlineShown)
        assertFalse(settings.isRightMarginShown)
        assertFalse(settings.isIndentGuidesShown)
        assertFalse(settings.isCaretRowShown)
        assertEquals(0, settings.additionalLinesCount)
        assertFalse((box.editor.markupModel as EditorMarkupModel).isErrorStripeVisible)
    }

    fun testTheBoxCarriesAFloatingSendButtonAsSoonAsItHasAnEditor() {
        val box = newPromptBox()
        assertNull(box.installedSendButton)

        box.installEditor()

        val sendButton = box.installedSendButton!!
        assertTrue(sendButton.component.isVisible)
        assertSame(box.component, sendButton.component.parent)
    }

    fun testTheSendButtonHidesWhileTheUserTypesAndComesBackAfterwards() {
        val box = newPromptBox()
        box.installEditor()
        val sendButton = box.installedSendButton!!

        box.text = "a prompt"

        assertFalse(sendButton.component.isVisible)
        assertTrue(sendButton.isWaitingToReappear())

        waitForTheSendButtonToComeBack(sendButton)

        assertTrue(sendButton.component.isVisible)
    }

    fun testTheSendButtonAlsoHidesWhileTheCaretMoves() {
        val box = newPromptBox()
        box.installEditor()
        val sendButton = box.installedSendButton!!
        box.text = "a prompt"
        waitForTheSendButtonToComeBack(sendButton)

        box.editor.caretModel.moveToOffset(2)

        assertFalse(sendButton.component.isVisible)

        waitForTheSendButtonToComeBack(sendButton)

        assertTrue(sendButton.component.isVisible)
    }

    fun testTheSendButtonSitsInTheBottomRightCornerOfTheBox() {
        val box = newPromptBox()
        box.installEditor()
        val panel = box.component
        val sendButton = box.installedSendButton!!.component

        panel.setBounds(0, 0, 400, 300)
        panel.doLayout()

        assertEquals(Rectangle(0, 0, 400, 300), box.editor.component.bounds)
        assertTrue(sendButton.width > 0)
        assertTrue(sendButton.height > 0)
        assertTrue(sendButton.x + sendButton.width <= 400)
        assertTrue(sendButton.y + sendButton.height <= 300)
        assertTrue(sendButton.x > 200)
        assertTrue(sendButton.y > 150)
    }

    fun testTheSendButtonSitsInThePaletteLayerAndPaintsOverTheEditor() {
        val box = newPromptBox()
        box.installEditor()
        val panel = box.component as JLayeredPane
        val sendButton = box.installedSendButton!!.component
        val editorComponent = box.editor.component

        assertEquals(JLayeredPane.PALETTE_LAYER, panel.getLayer(sendButton))
        assertEquals(JLayeredPane.DEFAULT_LAYER, panel.getLayer(editorComponent))
        assertTrue(panel.getComponentZOrder(sendButton) < panel.getComponentZOrder(editorComponent))

        panel.setBounds(0, 0, 400, 300)
        panel.validate()
        panel.doLayout()

        assertTrue(sendButton.width > 0)
        assertTrue(sendButton.height > 0)
        assertTrue(Rectangle(0, 0, 400, 300).contains(sendButton.bounds))
        assertTrue(sendButton.x > 200)
        assertTrue(sendButton.y > 150)
    }

    fun testThePlaceholderNamesTheShortcutThatSends() {
        val sendAction = ActionManager.getInstance().getAction(SEND_PROMPT_BOX_ACTION_ID)!!
        val shortcut = KeymapUtil.getFirstKeyboardShortcutText(sendAction)

        assertTrue(shortcut.isNotEmpty())
        assertEquals("Type a prompt, $shortcut to send", promptBoxPlaceholder())
    }

    fun testThePlaceholderIsPlainWhenNothingIsBoundToSending() {
        unbindTheSendPromptBoxShortcut()

        assertEquals("Type a prompt", promptBoxPlaceholder())
    }

    fun testDisposingTheParentReleasesTheEditor() {
        val box = newPromptBox()
        val editor = box.editor
        val disposable = boxDisposable!!

        Disposer.dispose(disposable)
        boxDisposable = null

        assertTrue(editor.isDisposed)
        assertNull(box.installedEditor)
    }
}
