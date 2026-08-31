package com.github.atm1020.tuilaunch.prompt

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.codeInsight.hint.HintManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import org.jetbrains.annotations.TestOnly

private const val PROMPT_FILE_NAME = "PROMPT.md"
private val IGNORED_EDITOR_KINDS = setOf(EditorKind.DIFF, EditorKind.PREVIEW, EditorKind.CONSOLE)
private const val SEND_TOOLTIP = "Type this prompt into the active TUI session"
private const val NO_SESSION_MESSAGE = "No TUI session is open"
private const val REFRESH_MERGE_MILLIS = 100

internal object PromptGutterRefreshes {

    private val refreshes = AtomicInteger()

    fun record() {
        refreshes.incrementAndGet()
    }

    @TestOnly
    fun count(): Int = refreshes.get()
}

class PromptGutterInstaller : EditorFactoryListener {

    private val guttersByEditor = mutableMapOf<Editor, PromptBlockGutter>()

    @TestOnly
    fun hasNoPendingRefresh(): Boolean = guttersByEditor.values.all { it.hasNoPendingRefresh() }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.editorKind in IGNORED_EDITOR_KINDS) return
        if (fileNameOf(editor) != PROMPT_FILE_NAME) return
        guttersByEditor.remove(editor)?.release()
        guttersByEditor[editor] = PromptBlockGutter(editor).also { it.install() }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        guttersByEditor.remove(event.editor)?.release()
    }
}

private class PromptBlockGutter(private val editor: Editor) {

    private val installedHighlighters = mutableListOf<RangeHighlighter>()
    private val listenerLifetime = Disposer.newDisposable("TUILaunch prompt gutter")

    private val refreshQueue = MergingUpdateQueue(
        "TUILaunch prompt gutter",
        REFRESH_MERGE_MILLIS,
        true,
        MergingUpdateQueue.ANY_COMPONENT,
        listenerLifetime,
    )

    fun hasNoPendingRefresh(): Boolean = refreshQueue.isEmpty

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (refreshIsNeededFor(event)) scheduleRefresh()
        }
    }

    fun install() {
        EditorUtil.disposeWithEditor(editor, listenerLifetime)
        editor.document.addDocumentListener(documentListener, listenerLifetime)
        refresh()
    }

    fun release() {
        Disposer.dispose(listenerLifetime)
        removeInstalledHighlighters()
    }

    private fun refreshIsNeededFor(event: DocumentEvent): Boolean =
        fileNameOf(editor) != PROMPT_FILE_NAME ||
            editCanChangeBlockStructure(event) ||
            anInstalledHighlighterWasInvalidated(event)

    private fun anInstalledHighlighterWasInvalidated(event: DocumentEvent): Boolean =
        event.oldLength > 0 && installedHighlighters.any { !it.isValid }

    private fun scheduleRefresh() {
        refreshQueue.queue(Update.create(this) { if (!editor.isDisposed) refresh() })
    }

    private fun refresh() {
        PromptGutterRefreshes.record()
        if (fileNameOf(editor) != PROMPT_FILE_NAME) {
            removeInstalledHighlighters()
            return
        }
        reconcile(promptMarkerLines(editor.document.charsSequence))
    }

    private fun reconcile(desiredMarkerLines: List<Int>) {
        val wantedLines = desiredMarkerLines.toHashSet()
        val reusableByLine = HashMap<Int, RangeHighlighter>(desiredMarkerLines.size)
        installedHighlighters.forEach { highlighter ->
            val line = lineOf(highlighter)
            if (line == null || line !in wantedLines || line in reusableByLine) {
                editor.markupModel.removeHighlighter(highlighter)
            } else {
                reusableByLine[line] = highlighter
            }
        }
        installedHighlighters.clear()
        desiredMarkerLines.forEach { line ->
            installedHighlighters.add(reusableByLine[line] ?: addGutterHighlighter(line))
        }
    }

    private fun lineOf(highlighter: RangeHighlighter): Int? =
        if (highlighter.isValid) editor.document.getLineNumber(highlighter.startOffset) else null

    private fun addGutterHighlighter(line: Int): RangeHighlighter {
        val highlighter = editor.markupModel.addLineHighlighter(
            null,
            line,
            HighlighterLayer.ADDITIONAL_SYNTAX,
        )
        highlighter.gutterIconRenderer = PromptBlockGutterIconRenderer(editor, highlighter)
        return highlighter
    }

    private fun removeInstalledHighlighters() {
        installedHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        installedHighlighters.clear()
    }
}

private fun fileNameOf(editor: Editor): String? =
    FileDocumentManager.getInstance().getFile(editor.document)?.name ?: editor.virtualFile?.name

internal fun editCanChangeBlockStructure(event: DocumentEvent): Boolean {
    if (spansLines(event.oldFragment) || spansLines(event.newFragment)) return true
    val document = event.document
    val editedLine = document.getLineNumber(event.offset)
    val lineStart = document.getLineStartOffset(editedLine)
    val lineEnd = document.getLineEndOffset(editedLine)
    if (event.offset < lineStart || event.offset + event.newLength > lineEnd) return true
    val text = document.charsSequence
    return canChangeBlockStructure(text.subSequence(lineStart, lineEnd)) ||
        canChangeBlockStructure(lineAsItWasBeforeTheEdit(text, lineStart, lineEnd, event))
}

private fun spansLines(fragment: CharSequence): Boolean = fragment.any { it == '\n' || it == '\r' }

private fun lineAsItWasBeforeTheEdit(
    text: CharSequence,
    lineStart: Int,
    lineEnd: Int,
    event: DocumentEvent,
): CharSequence =
    StringBuilder(lineEnd - lineStart + event.oldLength)
        .append(text, lineStart, event.offset)
        .append(event.oldFragment)
        .append(text, event.offset + event.newLength, lineEnd)

private data class PromptBlockGutterIconRenderer(
    private val editor: Editor,
    private val highlighter: RangeHighlighter,
) : GutterIconRenderer(), DumbAware {

    override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run

    override fun getTooltipText(): String = SEND_TOOLTIP

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.LEFT

    override fun getClickAction(): AnAction = SendPromptBlockAction(editor, highlighter)
}

private class SendPromptBlockAction(
    private val editor: Editor,
    private val highlighter: RangeHighlighter,
) : DumbAwareAction(SEND_TOOLTIP) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = editor.project ?: e.project ?: return
        val blockText = promptBlockTextUnder(editor, highlighter) ?: return
        if (!project.service<TuiAppLaunchService>().sendTextToActiveSession(blockText)) {
            HintManager.getInstance().showErrorHint(editor, NO_SESSION_MESSAGE)
        }
    }
}

internal fun promptBlockTextUnder(editor: Editor, highlighter: RangeHighlighter): String? {
    if (!highlighter.isValid) return null
    val document = editor.document
    return promptBlockTextAt(document.charsSequence, document.getLineNumber(highlighter.startOffset))
}
