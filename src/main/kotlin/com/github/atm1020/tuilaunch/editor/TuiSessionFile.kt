package com.github.atm1020.tuilaunch.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.plugins.terminal.TerminalIcons
import javax.swing.Icon
import javax.swing.JComponent

class TuiSessionFile(
    val sessionId: String,
    title: String,
    val component: JComponent,
    private val focusTarget: () -> JComponent,
) : LightVirtualFile(title, TuiSessionFileType, "") {

    private var currentTitle: String? = title

    var newestEditor: FileEditor? = null

    init {
        putUserData(FileEditorManagerKeys.FORBID_TAB_SPLIT, true)
        isWritable = false
    }

    val preferredFocusedComponent: JComponent get() = focusTarget()

    override fun getName(): String = currentTitle ?: super.getName()

    fun retitle(title: String) {
        currentTitle = title
    }
}

object TuiSessionFileType : FakeFileType() {
    override fun getName(): String = "TUILaunch Session"

    override fun getDescription(): String = "TUILaunch session"

    override fun getIcon(): Icon = TerminalIcons.OpenTerminal_13x13

    override fun isMyFileType(file: VirtualFile): Boolean = file is TuiSessionFile
}
