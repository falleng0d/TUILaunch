package com.github.atm1020.tuilaunch.toolwindow

import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.github.atm1020.tuilaunch.services.TUI_PROJECT_IS_CLOSING
import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabFile
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabManager
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabPersistenceProvider
import com.intellij.ui.content.Content
import com.intellij.util.xmlb.SkipDefaultsSerializationFilter
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element

private const val TOOL_WINDOW_EDITOR_TAB_EDITOR_TYPE_ID = "ToolWindowEditorTabFileEditor"
private const val EDITOR_STATE_HOLDER = "state"

internal fun writeEditorTabRecord(record: TuiSessionRecord): Element =
    XmlSerializer.serialize(record.copy(selected = false, inEditor = false), SkipDefaultsSerializationFilter())

internal fun readEditorTabRecord(element: Element): TuiSessionRecord? =
    runCatching { XmlSerializer.deserialize(element, TuiSessionRecord::class.java) }
        .getOrNull()
        ?.takeIf { it.appName.isNotBlank() }
        ?.copy(selected = false, inEditor = true)

class TuiLaunchEditorTabPersistence : ToolWindowEditorTabPersistenceProvider {

    override fun canSerialize(content: Content): Boolean = content.tuiSessionRecord() != null

    override fun serialize(content: Content): Element = writeEditorTabRecord(requireNotNull(content.tuiSessionRecord()))

    override fun deserialize(project: Project, element: Element): Content? {
        val record = readEditorTabRecord(element) ?: return null
        val service = project.service<TuiAppLaunchService>()
        val handle = service.restoreEditorTab(record) ?: return null
        return service.host?.contentOf(handle)
    }
}

class ForgetTuiEditorTabsClosedBeforeTheyWereShown(private val project: Project) : FileEditorManagerListener.Before {

    override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
        if (file !is ToolWindowEditorTabFile || file.toolWindowId != TUI_TOOL_WINDOW_ID) return
        val theTabIsOnlyMoving = file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true
        val theProjectIsClosing = project.getUserData(TUI_PROJECT_IS_CLOSING) == true
        if (theTabIsOnlyMoving || theProjectIsClosing) return
        if (ToolWindowEditorTabManager.getInstance(project).getSession(file) != null) return
        val record = savedRecordOf(source, file) ?: return
        project.service<TuiAppLaunchService>().forgetEditorTabClosedBeforeItWasShown(record)
    }

    private fun savedRecordOf(source: FileEditorManager, file: VirtualFile): TuiSessionRecord? {
        val editor = source.getAllEditors(file).firstOrNull() ?: return null
        val provider = FileEditorProviderManager.getInstance().getProvider(TOOL_WINDOW_EDITOR_TAB_EDITOR_TYPE_ID) ?: return null
        val holder = Element(EDITOR_STATE_HOLDER)
        provider.writeState(editor.getState(FileEditorStateLevel.FULL), project, holder)
        return holder.children.singleOrNull()?.let(::readEditorTabRecord)
    }
}
