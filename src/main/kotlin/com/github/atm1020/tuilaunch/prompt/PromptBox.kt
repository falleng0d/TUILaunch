package com.github.atm1020.tuilaunch.prompt

import com.github.atm1020.tuilaunch.action.PromptHistoryNextAction
import com.github.atm1020.tuilaunch.action.PromptHistoryPreviousAction
import com.github.atm1020.tuilaunch.action.promptHistoryShortcutSet
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLayeredPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

internal const val PROMPT_BOX_FILE_NAME = "prompt-box.md"
internal const val SEND_PROMPT_BOX_ACTION_ID = "TUILauncher.SendPromptBox"

private const val PROMPT_BOX_PLACEHOLDER = "Type a prompt"
private const val PROMPT_BOX_EDIT_NAME = "Prompt Box"
private const val SEND_BUTTON_TEXT = "Send"
private const val SEND_BUTTON_MARGIN = 8
private const val SEND_BUTTON_QUIET_MILLIS = 1000

internal val PROMPT_BOX_DATA_KEY: DataKey<PromptBox> = DataKey.create("TUILaunch.PromptBox")
internal val PROMPT_BOX_KEY: Key<PromptBox> = Key.create("TUILaunch.PromptBox")
internal val ESCAPE_SHORTCUT_SET: ShortcutSet =
    CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))

internal var promptBoxFocusRequest: (PromptBox) -> Unit = { box -> box.requestEditorFocus() }
internal var promptBoxHoldsFocus: (PromptBox) -> Boolean = { box -> box.editorOwnsFocus() }
internal var promptBoxSendButtonQuietMillis = SEND_BUTTON_QUIET_MILLIS

internal fun promptBoxPlaceholder(): String {
    val sendAction = sendPromptBoxAction() ?: return PROMPT_BOX_PLACEHOLDER
    val shortcut = KeymapUtil.getFirstKeyboardShortcutText(sendAction)
    return if (shortcut.isEmpty()) PROMPT_BOX_PLACEHOLDER else "$PROMPT_BOX_PLACEHOLDER, $shortcut to send"
}

private fun sendPromptBoxAction(): AnAction? = ActionManager.getInstance().getAction(SEND_PROMPT_BOX_ACTION_ID)

internal class PromptBox(
    private val project: Project,
    private val parentDisposable: Disposable,
    private val sender: PromptBoxSender? = null,
    private val focusSession: () -> Unit = {},
    private val promptDocument: () -> Document? = promptFileDocumentSupplier(project),
) {

    private var editorIfInstalled: EditorEx? = null
    private var sendButtonIfInstalled: PromptBoxSendButton? = null
    private var browsedHistory: PromptBoxHistory? = null
    private val panel = PromptBoxPanel(this)

    val component: JComponent get() = panel

    val installedEditor: EditorEx? get() = editorIfInstalled

    val installedSendButton: PromptBoxSendButton? get() = sendButtonIfInstalled

    val editor: EditorEx get() = installEditor()

    val document: Document get() = installEditor().document

    var text: String
        get() = editorIfInstalled?.document?.text ?: ""
        set(value) {
            val editor = installEditor()
            WriteCommandAction.writeCommandAction(project).withName(PROMPT_BOX_EDIT_NAME).run<RuntimeException> {
                editor.document.setText(value)
            }
        }

    fun installEditor(): EditorEx {
        editorIfInstalled?.let { return it }
        val editor = createEditor()
        editorIfInstalled = editor
        panel.add(editor.component, JLayeredPane.DEFAULT_LAYER)
        installSendButton(editor)
        registerTheSendShortcutOnThePanel()
        registerTheHistoryShortcutsOnThePanel()
        registerTheEscapeShortcutOnThePanel()
        panel.revalidate()
        Disposer.register(parentDisposable) { uninstallEditor(editor) }
        return editor
    }

    fun send() {
        sender?.send(this)
    }

    fun historyPrevious() {
        val history = browsedHistory ?: PromptBoxHistory(recordedPrompts()).also { browsedHistory = it }
        showHistoryEntry(history.previous(text), PromptHistoryDirection.PREVIOUS)
        if (!history.isBrowsing) browsedHistory = null
    }

    fun historyNext() {
        val history = browsedHistory ?: return
        showHistoryEntry(history.next(text), PromptHistoryDirection.NEXT)
        if (!history.isBrowsing) browsedHistory = null
    }

    fun historyReset() {
        browsedHistory = null
    }

    fun escapeToTheSession() {
        val selection = editorIfInstalled?.selectionModel
        if (selection != null && selection.hasSelection()) {
            selection.removeSelection()
            return
        }
        focusSession()
    }

    fun aCompletionPopupIsOpen(): Boolean {
        val editor = editorIfInstalled ?: return false
        return LookupManager.getActiveLookup(editor) != null
    }

    fun caretAtHistoryEdge(direction: PromptHistoryDirection): Boolean {
        val editor = editorIfInstalled ?: return false
        if (aCompletionPopupIsOpen()) return false
        val document = editor.document
        if (document.lineCount == 0) return true
        val caretLine = document.getLineNumber(editor.caretModel.offset)
        return when (direction) {
            PromptHistoryDirection.PREVIOUS -> caretLine == 0
            PromptHistoryDirection.NEXT -> caretLine == document.lineCount - 1
        }
    }

    fun clear() {
        if (editorIfInstalled != null) text = ""
    }

    fun requestFocus() {
        installEditor()
        promptBoxFocusRequest(this)
    }

    fun hasFocus(): Boolean = promptBoxHoldsFocus(this)

    fun requestEditorFocus() {
        IdeFocusManager.getInstance(project).requestFocus(installEditor().contentComponent, true)
    }

    fun editorOwnsFocus(): Boolean {
        val editorComponent = editorIfInstalled?.contentComponent ?: return false
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return false
        return focusOwner === editorComponent || SwingUtilities.isDescendingFrom(focusOwner, editorComponent)
    }

    private fun createEditor(): EditorEx {
        val file = LightVirtualFile(PROMPT_BOX_FILE_NAME, promptBoxFileType(), "")
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file)) {
            "No document backing $PROMPT_BOX_FILE_NAME"
        }
        val editor = EditorFactory.getInstance()
            .createEditor(document, project, file, false, EditorKind.UNTYPED) as EditorEx
        editor.settings.apply {
            setUseSoftWraps(true)
            setLineMarkerAreaShown(false)
            setGutterIconsShown(false)
            setLineNumbersShown(false)
            setFoldingOutlineShown(false)
            setRightMarginShown(false)
            setIndentGuidesShown(false)
            setCaretRowShown(false)
            setAdditionalLinesCount(0)
            setAdditionalPageAtBottom(false)
        }
        editor.setPlaceholder(promptBoxPlaceholder())
        editor.setShowPlaceholderWhenFocused(true)
        editor.putUserData(PROMPT_BOX_KEY, this)
        return editor
    }

    private fun promptBoxFileType(): FileType {
        val promptFileType = FileTypeManager.getInstance().getFileTypeByFileName(PROMPT_FILE_NAME)
        return if (promptFileType.isBinary) PlainTextFileType.INSTANCE else promptFileType
    }

    private fun installSendButton(editor: EditorEx) {
        val sendAction = sendPromptBoxAction() ?: return
        val presentation = sendAction.templatePresentation.clone()
        presentation.text = SEND_BUTTON_TEXT
        presentation.icon = AllIcons.Actions.Execute
        val button = ActionButtonWithText(
            sendAction,
            presentation,
            ActionPlaces.TOOLWINDOW_CONTENT,
            ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE,
        )
        sendButtonIfInstalled = PromptBoxSendButton(button, editor, parentDisposable, promptBoxSendButtonQuietMillis)
        panel.add(button, JLayeredPane.PALETTE_LAYER)
    }

    private fun registerTheSendShortcutOnThePanel() {
        val sendAction = sendPromptBoxAction() ?: return
        sendAction.registerCustomShortcutSet(sendAction.shortcutSet, panel, parentDisposable)
    }

    private fun registerTheHistoryShortcutsOnThePanel() {
        PromptHistoryPreviousAction().registerCustomShortcutSet(
            promptHistoryShortcutSet(PromptHistoryDirection.PREVIOUS),
            panel,
            parentDisposable,
        )
        PromptHistoryNextAction().registerCustomShortcutSet(
            promptHistoryShortcutSet(PromptHistoryDirection.NEXT),
            panel,
            parentDisposable,
        )
    }

    private fun registerTheEscapeShortcutOnThePanel() {
        PromptBoxEscapeAction().registerCustomShortcutSet(ESCAPE_SHORTCUT_SET, panel, parentDisposable)
    }

    private fun recordedPrompts(): List<String> {
        val document = promptDocument() ?: return emptyList()
        return parsePromptBlocks(document.text).map { it.text }
    }

    private fun showHistoryEntry(entry: String?, direction: PromptHistoryDirection) {
        if (entry == null) return
        text = entry
        val editor = installEditor()
        val caretOffset = when (direction) {
            PromptHistoryDirection.PREVIOUS -> 0
            PromptHistoryDirection.NEXT -> editor.document.textLength
        }
        editor.caretModel.moveToOffset(caretOffset)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    private fun uninstallEditor(editor: EditorEx) {
        if (editorIfInstalled === editor) editorIfInstalled = null
        sendButtonIfInstalled?.let { panel.remove(it.component) }
        sendButtonIfInstalled = null
        panel.remove(editor.component)
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
    }
}

internal class PromptBoxEscapeAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val box = e.getData(PROMPT_BOX_DATA_KEY)
        e.presentation.isEnabled = box != null && !box.aCompletionPopupIsOpen()
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(PROMPT_BOX_DATA_KEY)?.escapeToTheSession()
    }
}

internal class PromptBoxSendButton(
    val component: JComponent,
    editor: EditorEx,
    parentDisposable: Disposable,
    quietMillis: Int,
) {

    private val reappearance = MergingUpdateQueue(
        "TUILaunch prompt box send button",
        quietMillis,
        true,
        MergingUpdateQueue.ANY_COMPONENT,
        parentDisposable,
    ).setRestartTimerOnAdd(true)

    init {
        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = hideWhileTheUserIsBusy()
            },
            parentDisposable,
        )
        editor.caretModel.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = hideWhileTheUserIsBusy()
            },
            parentDisposable,
        )
    }

    fun isWaitingToReappear(): Boolean = !reappearance.isEmpty

    private fun hideWhileTheUserIsBusy() {
        component.isVisible = false
        reappearance.queue(Update.create(this) { component.isVisible = true })
    }
}

internal class PromptBoxPanel(val promptBox: PromptBox) : JBLayeredPane(), UiDataProvider {

    override fun uiDataSnapshot(sink: DataSink) {
        val editor = promptBox.installedEditor ?: return
        sink[PROMPT_BOX_DATA_KEY] = promptBox
        sink[CommonDataKeys.EDITOR] = editor
        sink.lazy(PlatformCoreDataKeys.FILE_EDITOR) {
            TextEditorProvider.getInstance().getTextEditor(editor)
        }
    }

    override fun doLayout() {
        val editor = promptBox.installedEditor ?: return
        editor.component.setBounds(0, 0, width, height)
        val button = promptBox.installedSendButton?.component ?: return
        val buttonSize = button.preferredSize
        val margin = JBUI.scale(SEND_BUTTON_MARGIN)
        val scrollBarWidth = editor.scrollPane.verticalScrollBar?.takeIf { it.isVisible }?.width ?: 0
        button.setBounds(
            width - buttonSize.width - margin - scrollBarWidth,
            height - buttonSize.height - margin,
            buttonSize.width,
            buttonSize.height,
        )
    }
}
