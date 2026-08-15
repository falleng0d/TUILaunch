package com.github.atm1020.tuilaunch.action

import com.github.atm1020.tuilaunch.services.TuiAppLaunchService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class FocusTuiAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.focusTui()
    }
}

class FocusEditorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.focusEditor()
    }
}

class ToggleFocusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.toggleFocus()
    }
}

class ToggleToolWindowAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.toggleToolWindow()
    }
}

class ToggleToolWindowAndFocusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.toggleToolWindowAndFocus()
    }
}

class CloseActiveTuiAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.closeActiveTui()
    }
}

class NextTuiTabAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.nextTuiTab()
    }
}

class NextTuiTabWithoutFocusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.nextTuiTabWithoutFocus()
    }
}

class PreviousTuiTabAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.previousTuiTab()
    }
}

class PreviousTuiTabWithoutFocusAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.service<TuiAppLaunchService>()?.previousTuiTabWithoutFocus()
    }
}
