package com.github.atm1020.tuilaunch.toolwindow

import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabPresentation
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabSupport
import com.intellij.ui.content.Content
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.beans.PropertyChangeListener

private val PRESENTATION_PROPERTIES = setOf(Content.PROP_DISPLAY_NAME, Content.PROP_ICON)

class TuiLaunchEditorTabSupport : ToolWindowEditorTabSupport {

    override fun canBeMovedToEditor(content: Content): Boolean = content.belongsToALiveTuiSession()

    override fun getTabPresentationFlow(project: Project, content: Content): Flow<ToolWindowEditorTabPresentation> {
        val service = project.service<TuiAppLaunchService>()
        service.host?.let { host -> service.tabMovedToEditor(host.handleFor(content)) }
        val toolWindowIcon = ToolWindowManager.getInstance(project).getToolWindow(TUI_TOOL_WINDOW_ID)?.icon
        return content.presentationChanges()
            .map {
                ToolWindowEditorTabPresentation(
                    title = content.displayName.orEmpty(),
                    icon = content.icon ?: toolWindowIcon,
                )
            }
            .distinctUntilChanged()
    }
}

private fun Content.presentationChanges(): Flow<Unit> = callbackFlow {
    val listener = PropertyChangeListener { event ->
        if (event.propertyName in PRESENTATION_PROPERTIES) trySend(Unit)
    }
    addPropertyChangeListener(listener)
    trySend(Unit)
    awaitClose { removePropertyChangeListener(listener) }
}
