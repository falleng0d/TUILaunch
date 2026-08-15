package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer

class TuiNewSessionAction : DumbAwareAction("New Session", "Start a new TUI session", AllIcons.General.Add) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val location = JBPopupFactory.getInstance().guessBestPopupLocation(this, e)
        val apps = TuiLauncherSettings.getInstance().state.tuiApps

        if (apps.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("No TUI apps configured. Add one in Settings → Tools → TUI Launcher.")
                .show(location)
            return
        }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(apps)
            .setTitle("New TUI Session")
            .setRenderer(SimpleListCellRenderer.create("") { app: TuiAppConfig -> app.name })
            .setItemChosenCallback { app -> project.service<TuiAppLaunchService>().launchNew(app.name, app.command) }
            .createPopup()
            .show(location)
    }
}
