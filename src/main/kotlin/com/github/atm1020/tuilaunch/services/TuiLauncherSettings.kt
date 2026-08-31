package com.github.atm1020.tuilaunch.services

import com.github.atm1020.tuilaunch.action.DynamicUserAction
import com.github.atm1020.tuilaunch.model.ACTION_ID_PREFIX
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.*

@Service
@State(
    name = "TuiLauncherSettings",
    storages = [Storage("tuiLauncherSettings.xml")]
)
class TuiLauncherSettings : PersistentStateComponent<TuiLauncherSettings.State> {
    private var settingsState = State()

    data class State(
        var tuiApps: MutableList<TuiAppConfig> = mutableListOf(),
        var tmuxKeybindingsEnabled: Boolean = true,
        var restoreOpenTabs: Boolean = true,
        var escapeModifier: String = "CTRL",
        var escapeKeyCode: Int? = null,
        var focusEditorKeyCode: Int? = null,
        var closeTuiKeyCode: Int? = null,
        var nextTuiKeyCode: Int? = null,
        var previousTuiKeyCode: Int? = null,
        var toggleToolWindowKeyCode: Int? = null,
        var nextTuiWithoutFocusKeyCode: Int? = null,
        var previousTuiWithoutFocusKeyCode: Int? = null,
    )

    override fun loadState(state: State) {
        settingsState = state
    }

    companion object {
        private const val TOOLS_MENU_ID = "ToolsMenu"
        private const val TUI_LAUNCH_GROUP_ID = "TUILauncher.DynamicActions"

        @JvmStatic
        fun getInstance(): TuiLauncherSettings = service()
    }

    override fun getState(): State = settingsState

    fun loadActions() {
        val actionManager = ActionManager.getInstance()
        val tuiLaunchGroup = getOrCreateTuiLaunchGroup(actionManager)
        settingsState.tuiApps.forEach { app ->
            val actionId = ACTION_ID_PREFIX + app.name
            val existingAction = actionManager.getAction(actionId)
            if (existingAction is DynamicUserAction) {
                existingAction.update(app.command, app.name)
            } else {
                if (existingAction != null) unregisterAction(actionId)
                val action = DynamicUserAction(actionId, app.command, app.name)
                actionManager.registerAction(actionId, action)
                tuiLaunchGroup.add(action, Constraints.LAST)
            }
        }
    }

    fun unregisterAction(actionId: String) {
        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction(actionId) ?: return
        getTuiLaunchGroup(actionManager)?.remove(action)
        (actionManager.getAction(TOOLS_MENU_ID) as? DefaultActionGroup)?.remove(action)
        actionManager.unregisterAction(actionId)
    }

    private fun getOrCreateTuiLaunchGroup(actionManager: ActionManager): DefaultActionGroup {
        getTuiLaunchGroup(actionManager)?.let { return it }

        val group = DefaultActionGroup("TUILaunch", true)
        actionManager.registerAction(TUI_LAUNCH_GROUP_ID, group)
        (actionManager.getAction(TOOLS_MENU_ID) as? DefaultActionGroup)?.add(group, Constraints.LAST)
        return group
    }

    private fun getTuiLaunchGroup(actionManager: ActionManager): DefaultActionGroup? =
        actionManager.getAction(TUI_LAUNCH_GROUP_ID) as? DefaultActionGroup
}
