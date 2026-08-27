package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction

class FocusTuiAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.focusTui()
    }
}

class FocusEditorAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.focusEditor()
    }
}

class ToggleFocusAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.toggleFocus()
    }
}

class ToggleToolWindowAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.toggleToolWindow()
    }
}

class ToggleToolWindowAndFocusAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.toggleToolWindowAndFocus()
    }
}

class CloseActiveTuiAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.closeActiveTui()
    }
}

class NextTuiTabAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.nextTuiTab()
    }
}

class NextTuiTabWithoutFocusAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.nextTuiTabWithoutFocus()
    }
}

class PreviousTuiTabAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.previousTuiTab()
    }
}

class PreviousTuiTabWithoutFocusAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.previousTuiTabWithoutFocus()
    }
}
