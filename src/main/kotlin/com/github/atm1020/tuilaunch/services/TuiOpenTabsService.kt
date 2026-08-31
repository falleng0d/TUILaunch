package com.github.atm1020.tuilaunch.services

import com.github.atm1020.tuilaunch.model.TuiSessionRecord
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "TuiLaunchOpenTabs",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class TuiOpenTabsService : PersistentStateComponent<TuiOpenTabsService.State> {
    private var tabsState = State()

    data class State(
        var tabs: MutableList<TuiSessionRecord> = mutableListOf(),
    )

    override fun getState(): State = tabsState

    override fun loadState(state: State) {
        tabsState = state
    }

    fun replaceTabs(records: List<TuiSessionRecord>) {
        tabsState = State(records.toMutableList())
    }

    companion object {
        fun getInstance(project: Project): TuiOpenTabsService = project.service()
    }
}
