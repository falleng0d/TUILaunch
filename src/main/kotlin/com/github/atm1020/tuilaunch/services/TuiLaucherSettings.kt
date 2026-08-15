package com.github.atm1020.tuilaunch.services

import com.github.atm1020.tuilaunch.action.DynamicUserAction
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunchmodel.TuiAppTableModel
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
    private var settingsState = TuiLauncherSettings.State()

    data class State(
        var tuiApps: MutableList<TuiAppConfig> = mutableListOf(),
        var tmuxKeybindingsEnabled: Boolean = true,
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

    override fun loadState(state: TuiLauncherSettings.State) {
        settingsState = state
    }

    companion object {
        private const val TOOLS_MENU_ID = "ToolsMenu"
        private const val TUI_LAUNCH_GROUP_ID = "TUILauncher.DynamicActions"

        @JvmStatic
        fun getInstance(): TuiLauncherSettings = service()
    }

    override fun getState(): TuiLauncherSettings.State {
        return settingsState
    }

    fun loadActions() {
        val tableModel = TuiAppTableModel(settingsState.tuiApps)
        tableModel.let { model ->
            val actionManger = ActionManager.getInstance()
            val tuiLaunchGroup = getOrCreateTuiLaunchGroup(actionManger)
            for (i in 0..<model.rowCount) {
                val name = model.getValueAt(i, 0)
                val command = model.getValueAt(i, 1)
                val actionId = model.getValueAt(i, 3).toString()
                val existingAction = actionManger.getAction(actionId)
                if (existingAction is DynamicUserAction) {
                    existingAction.update(command.toString(), name.toString())
                } else {
                    if (existingAction != null) unregisterAction(actionId)
                    val action = DynamicUserAction(actionId, command.toString(), name.toString())
                    actionManger.registerAction(actionId, action)
                    tuiLaunchGroup.add(action, Constraints.LAST)
                }
            }
        }
    }

    fun unregisterAction(actionId: String) {
        val actionManger = ActionManager.getInstance()
        val action = actionManger.getAction(actionId) ?: return
        getTuiLaunchGroup(actionManger)?.remove(action)
        (actionManger.getAction(TOOLS_MENU_ID) as? DefaultActionGroup)?.remove(action)
        actionManger.unregisterAction(actionId)
    }

    private fun getOrCreateTuiLaunchGroup(actionManger: ActionManager): DefaultActionGroup {
        getTuiLaunchGroup(actionManger)?.let { return it }

        val group = DefaultActionGroup("TUILaunch", true)
        actionManger.registerAction(TUI_LAUNCH_GROUP_ID, group)
        (actionManger.getAction(TOOLS_MENU_ID) as? DefaultActionGroup)?.add(group, Constraints.LAST)
        return group
    }

    private fun getTuiLaunchGroup(actionManger: ActionManager): DefaultActionGroup? {
        return actionManger.getAction(TUI_LAUNCH_GROUP_ID) as? DefaultActionGroup
    }

}
