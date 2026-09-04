package com.github.atm1020.tuilaunch.ui

import com.github.atm1020.tuilaunch.copilot.CopilotServerControl
import com.github.atm1020.tuilaunch.copilot.CopilotServerLocation
import com.github.atm1020.tuilaunch.copilot.CopilotServerSource
import com.github.atm1020.tuilaunch.copilot.CopilotServerState
import com.github.atm1020.tuilaunch.copilot.InstalledCopilotServerControl
import com.github.atm1020.tuilaunch.model.ACTION_ID_PREFIX
import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiAppTableModel
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.TableModelEvent
import javax.swing.table.AbstractTableModel
import kotlin.reflect.KMutableProperty1

private const val COPILOT_SERVER_PATH_FIELD_WIDTH = 320

internal const val RESTORE_OPEN_TABS_LABEL = "Reopen TUI tabs when the project is opened"
internal const val SUBMIT_PROMPT_ON_SEND_LABEL = "Send the prompt immediately instead of only typing it"
internal const val APPEND_PROMPT_SEPARATOR_LABEL = "Add a new prompt separator to PROMPT.md after sending"
internal const val FOCUS_PROMPT_FILE_LABEL = "Focus PROMPT.md again after sending"
internal const val PROMPT_BOX_VISIBILITY_PER_APP_LABEL = "Remember the prompt box visibility per TUI app"
internal const val PROMPT_BOX_SIZE_PER_APP_LABEL = "Remember the prompt box size per TUI app"
internal const val FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL =
    "Move focus to the TUI after sending from the prompt box"
internal const val PROMPT_BOX_COMPLETIONS_TITLE = "Prompt box completions"
internal const val COMPLETION_SOURCE_LABEL = "Completion source"
internal const val JETBRAINS_AI_COMPLETION_SOURCE_ITEM = "JetBrains AI Assistant"
internal const val COPILOT_COMPLETION_SOURCE_ITEM = "GitHub Copilot"
internal const val COPILOT_SERVER_PATH_LABEL = "Copilot Language Server"
internal const val COPILOT_SERVER_PATH_PLACEHOLDER = "Auto-detect"
internal const val COPILOT_PROMPT_HISTORY_CONTEXT_LABEL = "Include the PROMPT.md history as completion context"
internal const val CHECK_COPILOT_STATUS_LABEL = "Check Copilot status"
internal const val CHECKING_COPILOT_STATUS_TEXT = "Checking…"
internal const val COPILOT_SERVER_HINT_NAME = "copilotServerHint"
internal const val COPILOT_STATUS_NAME = "copilotStatus"

internal fun copilotServerHintText(location: CopilotServerLocation): String = when (location) {
    is CopilotServerLocation.Found -> when (location.source) {
        CopilotServerSource.EXPLICIT_PATH -> "Found at the configured path: ${location.path}"
        CopilotServerSource.COPILOT_PLUGIN -> "Found in the GitHub Copilot plugin: ${location.path}"
        CopilotServerSource.SYSTEM_PATH -> "Found on PATH: ${location.path}"
    }
    is CopilotServerLocation.NotFound -> "Not found: ${location.reason}"
}

internal fun copilotStatusText(state: CopilotServerState): String = when (state) {
    is CopilotServerState.Ready ->
        if (state.user.isNullOrBlank()) "Signed in" else "Signed in as ${state.user}"
    is CopilotServerState.NotSignedIn -> "Not signed in: ${state.serverStatus}"
    is CopilotServerState.NotConfigured -> "Not configured: ${state.reason}"
    is CopilotServerState.Failed -> "Failed: ${state.reason}"
    CopilotServerState.Starting, CopilotServerState.Stopped -> CHECKING_COPILOT_STATUS_TEXT
}

class TuiLauncherConfiguration internal constructor(
    private val copilotServer: CopilotServerControl,
) : Configurable {
    constructor() : this(InstalledCopilotServerControl)

    private var tuiLauncherPanel: JPanel? = null
    private var tableModel: TuiAppTableModel? = null
    private var appsTable: JBTable? = null
    private val settings = TuiLauncherSettings.getInstance()

    private var tmuxKeybindingsEnabledCheckBox: JBCheckBox? = null
    private var restoreOpenTabsCheckBox: JBCheckBox? = null
    private var submitPromptOnSendCheckBox: JBCheckBox? = null
    private var appendPromptSeparatorCheckBox: JBCheckBox? = null
    private var focusPromptFileCheckBox: JBCheckBox? = null
    private var promptBoxVisibilityPerAppCheckBox: JBCheckBox? = null
    private var promptBoxSizePerAppCheckBox: JBCheckBox? = null
    private var focusTuiAfterPromptBoxSendCheckBox: JBCheckBox? = null
    private var completionSourceCombo: JComboBox<String>? = null
    private var copilotServerPathField: TextFieldWithBrowseButton? = null
    private var copilotServerHintLabel: JBLabel? = null
    private var copilotPromptHistoryContextCheckBox: JBCheckBox? = null
    private var copilotStatusLabel: JBLabel? = null
    private val copilotComponents = mutableListOf<JComponent>()
    private var copilotStatusRequests = 0
    private var modifierCombo: JComboBox<String>? = null
    private val tmuxShortcutComponents = mutableListOf<JComponent>()
    private var shortcutsTable: JBTable? = null
    private var shortcutsModel: ShortcutTableModel? = null
    private var clearShortcutButton: JButton? = null

    internal val builtInShortcuts = listOf(
        builtInShortcut("Prefix", true, TuiLauncherSettings.State::escapeKeyCode),
        builtInShortcut("Focus editor", false, TuiLauncherSettings.State::focusEditorKeyCode),
        builtInShortcut("Close active TUI", false, TuiLauncherSettings.State::closeTuiKeyCode),
        builtInShortcut("Next TUI tab", false, TuiLauncherSettings.State::nextTuiKeyCode),
        builtInShortcut("Previous TUI tab", false, TuiLauncherSettings.State::previousTuiKeyCode),
        builtInShortcut("Toggle tool window", false, TuiLauncherSettings.State::toggleToolWindowKeyCode),
        builtInShortcut("Next TUI tab without focus", false, TuiLauncherSettings.State::nextTuiWithoutFocusKeyCode),
        builtInShortcut(
            "Previous TUI tab without focus",
            false,
            TuiLauncherSettings.State::previousTuiWithoutFocusKeyCode,
        ),
        builtInShortcut("Focus prompt box", false, TuiLauncherSettings.State::focusPromptBoxKeyCode),
    )

    private val builtInShortcutBindings = builtInShortcuts.map { shortcut ->
        ShortcutBinding(
            shortcut.actionName,
            shortcut.includeModifier,
            { shortcut.keyCode },
            { shortcut.keyCode = it },
        )
    }

    private fun builtInShortcut(
        actionName: String,
        includeModifier: Boolean,
        stateProperty: KMutableProperty1<TuiLauncherSettings.State, Int?>,
    ): BuiltInShortcut = BuiltInShortcut(actionName, includeModifier, stateProperty, stateProperty.get(settings.state))

    override fun getDisplayName(): String = "TUI Launcher"

    private val autoDetectedServer: CopilotServerLocation by lazy { copilotServer.locate("") }

    override fun createComponent(): JComponent {
        tmuxShortcutComponents.clear()
        copilotComponents.clear()

        val panel = JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(10)
        }

        tableModel = TuiAppTableModel(settings.state.tuiApps.map { it.copy() }.toMutableList())
        val table = createAppsTable()
        val tablePanel = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                tableModel?.addRow(TuiAppConfig())
                refreshShortcutBindings()
                selectLastShortcutRow()
            }
            .setRemoveAction {
                val selectedRow = table.selectedRow
                if (selectedRow >= 0) {
                    val modelRow = table.convertRowIndexToModel(selectedRow)
                    tableModel?.removeRow(modelRow)
                    refreshShortcutBindings()
                }
            }
            .disableUpDownActions()
            .createPanel()
            .apply {
                preferredSize = Dimension(640, 250)
            }

        panel.add(tablePanel, BorderLayout.CENTER)
        panel.add(createSessionAndKeybindingsPanel(), BorderLayout.SOUTH)

        tuiLauncherPanel = panel
        return panel
    }

    private fun createAppsTable(): JBTable = JBTable(tableModel).apply {
        appsTable = this
        fillsViewportHeight = true
        preferredScrollableViewportSize = Dimension(620, 210)
        rowHeight = JBUI.scale(28)
        setShowGrid(false)
        intercellSpacing = Dimension(0, 0)
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting && tmuxKeybindingsEnabledCheckBox?.isSelected == true) {
                selectShortcutRowForSelectedApp(requestFocus = false)
            }
        }
        tableHeader.reorderingAllowed = false
        emptyText.text = "Add TUI applications to create IDE actions"
        model.addTableModelListener { event ->
            if (event.type != TableModelEvent.UPDATE || event.column == 0 || event.column == 1) {
                refreshShortcutBindings()
            }
        }
    }

    private fun createSessionAndKeybindingsPanel(): JComponent =
        JPanel(BorderLayout(0, 6)).apply {
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(createSessionOptionsPanel())
                    add(createPromptBoxCompletionsPanel())
                },
                BorderLayout.NORTH,
            )
            add(createTmuxKeybindingsPanel(), BorderLayout.CENTER)
        }

    private fun createSessionOptionsPanel(): JComponent {
        val restoreCheckBox = JBCheckBox(RESTORE_OPEN_TABS_LABEL, settings.state.restoreOpenTabs)
        val submitCheckBox = JBCheckBox(SUBMIT_PROMPT_ON_SEND_LABEL, settings.state.submitPromptOnSend)
        val separatorCheckBox =
            JBCheckBox(APPEND_PROMPT_SEPARATOR_LABEL, settings.state.appendPromptSeparatorOnSend)
        val focusPromptCheckBox = JBCheckBox(FOCUS_PROMPT_FILE_LABEL, settings.state.focusPromptFileAfterSend)
        val promptBoxVisibilityCheckBox =
            JBCheckBox(PROMPT_BOX_VISIBILITY_PER_APP_LABEL, settings.state.rememberPromptBoxVisibilityPerApp)
        val promptBoxSizeCheckBox =
            JBCheckBox(PROMPT_BOX_SIZE_PER_APP_LABEL, settings.state.rememberPromptBoxSizePerApp)
        val focusTuiAfterSendCheckBox =
            JBCheckBox(FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL, settings.state.focusTuiAfterPromptBoxSend)
        restoreOpenTabsCheckBox = restoreCheckBox
        submitPromptOnSendCheckBox = submitCheckBox
        appendPromptSeparatorCheckBox = separatorCheckBox
        focusPromptFileCheckBox = focusPromptCheckBox
        promptBoxVisibilityPerAppCheckBox = promptBoxVisibilityCheckBox
        promptBoxSizePerAppCheckBox = promptBoxSizeCheckBox
        focusTuiAfterPromptBoxSendCheckBox = focusTuiAfterSendCheckBox
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(restoreCheckBox)
            add(submitCheckBox)
            add(separatorCheckBox)
            add(focusPromptCheckBox)
            add(promptBoxVisibilityCheckBox)
            add(promptBoxSizeCheckBox)
            add(focusTuiAfterSendCheckBox)
        }
    }

    private fun createPromptBoxCompletionsPanel(): JComponent {
        val sourceCombo = JComboBox(arrayOf(JETBRAINS_AI_COMPLETION_SOURCE_ITEM, COPILOT_COMPLETION_SOURCE_ITEM)).apply {
            selectedItem = completionSourceItem()
        }
        completionSourceCombo = sourceCombo

        val pathField = createCopilotServerPathField()
        copilotServerPathField = pathField

        val hintLabel = JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER).apply {
            name = COPILOT_SERVER_HINT_NAME
        }
        copilotServerHintLabel = hintLabel

        val historyContextCheckBox =
            JBCheckBox(COPILOT_PROMPT_HISTORY_CONTEXT_LABEL, settings.state.copilotPromptHistoryContext)
        copilotPromptHistoryContextCheckBox = historyContextCheckBox

        val statusLabel = JBLabel("").apply { name = COPILOT_STATUS_NAME }
        copilotStatusLabel = statusLabel
        val statusButton = JButton(CHECK_COPILOT_STATUS_LABEL).apply {
            addActionListener { checkCopilotStatus() }
        }

        copilotComponents.addAll(listOf(pathField, hintLabel, historyContextCheckBox, statusButton, statusLabel))
        sourceCombo.addActionListener { updateCopilotComponentsEnabled() }
        updateCopilotServerHint()
        updateCopilotComponentsEnabled()

        return JPanel().apply {
            border = BorderFactory.createTitledBorder(PROMPT_BOX_COMPLETIONS_TITLE)
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(formRow(JBLabel("$COMPLETION_SOURCE_LABEL:"), sourceCombo))
            add(formRow(JBLabel("$COPILOT_SERVER_PATH_LABEL:"), pathField))
            add(formRow(hintLabel))
            add(formRow(historyContextCheckBox))
            add(formRow(statusButton, statusLabel))
        }
    }

    private fun createCopilotServerPathField(): TextFieldWithBrowseButton {
        val textField = JBTextField().apply { emptyText.text = COPILOT_SERVER_PATH_PLACEHOLDER }
        return TextFieldWithBrowseButton(textField).apply {
            text = settings.state.copilotLanguageServerPath
            preferredSize = Dimension(JBUI.scale(COPILOT_SERVER_PATH_FIELD_WIDTH), preferredSize.height)
            addBrowseFolderListener(
                null,
                FileChooserDescriptorFactory.singleFile().withTitle("Select the GitHub Copilot Language Server"),
            )
            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = updateCopilotServerHint()
            })
        }
    }

    private fun completionSourceItem(): String =
        if (settings.state.promptBoxCompletionSource == PromptBoxCompletionSource.COPILOT) {
            COPILOT_COMPLETION_SOURCE_ITEM
        } else {
            JETBRAINS_AI_COMPLETION_SOURCE_ITEM
        }

    private fun selectedCompletionSource(): PromptBoxCompletionSource =
        if (completionSourceCombo?.selectedItem == COPILOT_COMPLETION_SOURCE_ITEM) {
            PromptBoxCompletionSource.COPILOT
        } else {
            PromptBoxCompletionSource.JETBRAINS_AI
        }

    private fun typedCopilotServerPath(): String = copilotServerPathField?.text?.trim() ?: ""

    private fun updateCopilotComponentsEnabled() {
        val copilotSelected = selectedCompletionSource() == PromptBoxCompletionSource.COPILOT
        copilotComponents.forEach { it.isEnabled = copilotSelected }
    }

    private fun updateCopilotServerHint() {
        val hintLabel = copilotServerHintLabel ?: return
        val typedPath = typedCopilotServerPath()
        val location = if (typedPath.isEmpty()) autoDetectedServer else copilotServer.locate(typedPath)
        hintLabel.text = copilotServerHintText(location)
    }

    private fun checkCopilotStatus() {
        val statusLabel = copilotStatusLabel ?: return
        val request = ++copilotStatusRequests
        statusLabel.text = CHECKING_COPILOT_STATUS_TEXT
        copilotServer.checkStatus(typedCopilotServerPath()) { state ->
            if (request == copilotStatusRequests) statusLabel.text = copilotStatusText(state)
        }
    }

    private fun createTmuxKeybindingsPanel(): JComponent {
        val combo = JComboBox(arrayOf("Ctrl", "Alt")).apply {
            selectedItem = modifierComboItem()
        }
        modifierCombo = combo

        val enabledCheckBox = JBCheckBox("Enable tmux-like prefix keybindings", settings.state.tmuxKeybindingsEnabled)
        tmuxKeybindingsEnabledCheckBox = enabledCheckBox

        val model = ShortcutTableModel(createShortcutBindings())
        shortcutsModel = model

        val table = JBTable(model).apply {
            shortcutsTable = this
            rowHeight = JBUI.scale(26)
            fillsViewportHeight = true
            tableHeader.reorderingAllowed = false
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectionModel.addListSelectionListener { updateClearShortcutButtonEnabled() }
            preferredScrollableViewportSize = Dimension(620, 170)
            emptyText.text = "Select an action and press a key to assign a shortcut"
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (isModifierKey(e.keyCode)) return
                    if (e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE) {
                        clearSelectedShortcut()
                    } else {
                        assignSelectedShortcut(e.keyCode)
                    }
                    e.consume()
                }
            })
        }

        val recordButton = JButton("Record Shortcut").apply {
            addActionListener {
                if (shortcutsTable?.selectedRow == -1) {
                    selectShortcutRowForSelectedApp(requestFocus = false)
                }
                shortcutsTable?.requestFocusInWindow()
            }
        }
        val clearButton = JButton("Remove Shortcut").apply {
            addActionListener { clearSelectedShortcut() }
        }
        clearShortcutButton = clearButton
        combo.addActionListener { shortcutsModel?.fireTableDataChanged() }

        val shortcutPanel = JPanel(BorderLayout(8, 6)).apply {
            border = BorderFactory.createTitledBorder("Tmux-like keybindings")
            add(formRow(JBLabel("Prefix modifier:"), combo, JBLabel("Select a row, then press a key. Delete/Backspace clears.")), BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(formRow(recordButton, clearButton), BorderLayout.SOUTH)
        }

        tmuxShortcutComponents.addAll(listOf(combo, table, recordButton, clearButton))
        enabledCheckBox.addActionListener { updateTmuxShortcutComponentsEnabled() }
        updateTmuxShortcutComponentsEnabled()

        return JPanel(BorderLayout(0, 6)).apply {
            add(enabledCheckBox, BorderLayout.NORTH)
            add(shortcutPanel, BorderLayout.CENTER)
        }
    }

    private fun selectedShortcutModelRow(): Int? {
        val table = shortcutsTable ?: return null
        val selectedViewRow = table.selectedRow
        if (selectedViewRow < 0) return null
        return table.convertRowIndexToModel(selectedViewRow)
    }

    private fun assignSelectedShortcut(keyCode: Int) {
        val modelRow = selectedShortcutModelRow() ?: return
        val model = shortcutsModel ?: return
        val conflictRow = model.findConflict(modelRow, keyCode)
        if (conflictRow != null) {
            Messages.showErrorDialog(
                "Shortcut '${model.shortcutText(modelRow, keyCode)}' is already assigned to '${model.actionName(conflictRow)}'. Remove that shortcut first.",
                "Shortcut Already Assigned",
            )
            return
        }
        model.setShortcut(modelRow, keyCode)
    }

    private fun clearSelectedShortcut() {
        val modelRow = selectedShortcutModelRow() ?: return
        shortcutsModel?.setShortcut(modelRow, null)
    }

    private fun updateTmuxShortcutComponentsEnabled() {
        val enabled = tmuxKeybindingsEnabledCheckBox?.isSelected == true
        tmuxShortcutComponents.forEach { it.isEnabled = enabled }
        updateClearShortcutButtonEnabled()
    }

    private fun updateClearShortcutButtonEnabled() {
        val tmuxEnabled = tmuxKeybindingsEnabledCheckBox?.isSelected == true
        clearShortcutButton?.isEnabled = tmuxEnabled && selectedShortcutModelRow() != null
    }

    private fun formRow(vararg components: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)).apply {
        components.forEach { add(it) }
    }

    private fun createShortcutBindings(): List<ShortcutBinding> {
        val model = tableModel ?: return builtInShortcutBindings
        return builtInShortcutBindings + (0 until model.rowCount).map { index ->
            val app = model.appAt(index)
            ShortcutBinding(launchShortcutLabel(app, index), false, { app.shortcutKeyCode }, { app.shortcutKeyCode = it })
        }
    }

    private fun launchShortcutLabel(app: TuiAppConfig, index: Int): String = when {
        app.name.isNotBlank() -> "Launch ${app.name}"
        app.command.isNotBlank() -> "Launch ${app.command}"
        else -> "Launch app row ${index + 1}"
    }

    private fun refreshShortcutBindings() {
        shortcutsModel?.setBindings(createShortcutBindings())
    }

    private fun selectLastShortcutRow() {
        val table = shortcutsTable ?: return
        if (tmuxKeybindingsEnabledCheckBox?.isSelected != true || table.rowCount == 0) return
        selectShortcutRow(table.rowCount - 1, requestFocus = true)
    }

    private fun selectShortcutRowForSelectedApp(requestFocus: Boolean) {
        val appRow = appsTable?.selectedRow ?: return
        if (appRow < 0) return
        val modelRow = appsTable?.convertRowIndexToModel(appRow) ?: return
        selectShortcutRow(builtInShortcuts.size + modelRow, requestFocus)
    }

    private fun selectShortcutRow(row: Int, requestFocus: Boolean) {
        val table = shortcutsTable ?: return
        if (row !in 0 until table.rowCount) return
        table.selectionModel.setSelectionInterval(row, row)
        table.scrollRectToVisible(table.getCellRect(row, 0, true))
        if (requestFocus) table.requestFocusInWindow()
    }

    internal class BuiltInShortcut(
        val actionName: String,
        val includeModifier: Boolean,
        val stateProperty: KMutableProperty1<TuiLauncherSettings.State, Int?>,
        var keyCode: Int?,
    )

    private data class ShortcutBinding(
        val actionName: String,
        val includeModifier: Boolean,
        val getKeyCode: () -> Int?,
        val setKeyCode: (Int?) -> Unit,
    )

    private inner class ShortcutTableModel(private var bindings: List<ShortcutBinding>) : AbstractTableModel() {
        override fun getRowCount(): Int = bindings.size
        override fun getColumnCount(): Int = 2
        override fun getColumnName(column: Int): String = if (column == 0) "Action" else "Shortcut"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val binding = bindings[rowIndex]
            if (columnIndex == 0) return binding.actionName
            val keyCode = binding.getKeyCode() ?: return "Not set"
            return shortcutText(rowIndex, keyCode)
        }

        fun setShortcut(rowIndex: Int, keyCode: Int?) {
            bindings[rowIndex].setKeyCode(keyCode)
            fireTableRowsUpdated(rowIndex, rowIndex)
        }

        fun setBindings(newBindings: List<ShortcutBinding>) {
            bindings = newBindings
            fireTableDataChanged()
        }

        fun findConflict(rowIndex: Int, keyCode: Int): Int? {
            val binding = bindings[rowIndex]
            val conflictRow = bindings.withIndex().indexOfFirst { (index, other) ->
                index != rowIndex &&
                    other.includeModifier == binding.includeModifier &&
                    other.getKeyCode() == keyCode
            }
            return conflictRow.takeIf { it >= 0 }
        }

        fun actionName(rowIndex: Int): String = bindings[rowIndex].actionName

        fun shortcutText(rowIndex: Int, keyCode: Int): String {
            val keyText = KeyEvent.getKeyText(keyCode)
            return if (bindings[rowIndex].includeModifier) "${modifierCombo?.selectedItem ?: "Ctrl"}+$keyText" else keyText
        }

        fun duplicateShortcutMessage(): String? {
            bindings.forEachIndexed { index, binding ->
                val keyCode = binding.getKeyCode() ?: return@forEachIndexed
                val conflictRow = findConflict(index, keyCode) ?: return@forEachIndexed
                return "Shortcut '${shortcutText(index, keyCode)}' is already assigned to '${actionName(index)}' and '${actionName(conflictRow)}'."
            }
            return null
        }
    }

    private fun isModifierKey(keyCode: Int): Boolean = keyCode == KeyEvent.VK_CONTROL ||
        keyCode == KeyEvent.VK_ALT ||
        keyCode == KeyEvent.VK_SHIFT ||
        keyCode == KeyEvent.VK_META

    override fun isModified(): Boolean = tuiLauncherPanel != null && editedState() != settings.state

    override fun reset() {
        if (tuiLauncherPanel == null) return

        appsTable?.cellEditor?.cancelCellEditing()
        appsTable?.clearSelection()
        shortcutsTable?.clearSelection()

        tableModel?.setRows(settings.state.tuiApps.map { it.copy() })
        tmuxKeybindingsEnabledCheckBox?.isSelected = settings.state.tmuxKeybindingsEnabled
        restoreOpenTabsCheckBox?.isSelected = settings.state.restoreOpenTabs
        submitPromptOnSendCheckBox?.isSelected = settings.state.submitPromptOnSend
        appendPromptSeparatorCheckBox?.isSelected = settings.state.appendPromptSeparatorOnSend
        focusPromptFileCheckBox?.isSelected = settings.state.focusPromptFileAfterSend
        promptBoxVisibilityPerAppCheckBox?.isSelected = settings.state.rememberPromptBoxVisibilityPerApp
        promptBoxSizePerAppCheckBox?.isSelected = settings.state.rememberPromptBoxSizePerApp
        focusTuiAfterPromptBoxSendCheckBox?.isSelected = settings.state.focusTuiAfterPromptBoxSend
        completionSourceCombo?.selectedItem = completionSourceItem()
        copilotServerPathField?.text = settings.state.copilotLanguageServerPath
        copilotPromptHistoryContextCheckBox?.isSelected = settings.state.copilotPromptHistoryContext
        copilotStatusLabel?.text = ""
        copilotStatusRequests++
        modifierCombo?.selectedItem = modifierComboItem()
        builtInShortcuts.forEach { it.keyCode = it.stateProperty.get(settings.state) }

        refreshShortcutBindings()
        updateCopilotServerHint()
        updateCopilotComponentsEnabled()
        updateTmuxShortcutComponentsEnabled()
    }

    override fun disposeUIResources() {
        copilotStatusRequests++
    }

    private fun modifierComboItem(): String = if (settings.state.escapeModifier == "ALT") "Alt" else "Ctrl"

    private fun editedState(): TuiLauncherSettings.State = settings.state.copy(
        tuiApps = tableModel?.snapshot() ?: settings.state.tuiApps,
        tmuxKeybindingsEnabled = tmuxKeybindingsEnabledCheckBox?.isSelected == true,
        restoreOpenTabs = restoreOpenTabsCheckBox?.isSelected == true,
        submitPromptOnSend = submitPromptOnSendCheckBox?.isSelected == true,
        appendPromptSeparatorOnSend = appendPromptSeparatorCheckBox?.isSelected == true,
        focusPromptFileAfterSend = focusPromptFileCheckBox?.isSelected == true,
        rememberPromptBoxVisibilityPerApp = promptBoxVisibilityPerAppCheckBox?.isSelected == true,
        rememberPromptBoxSizePerApp = promptBoxSizePerAppCheckBox?.isSelected == true,
        focusTuiAfterPromptBoxSend = focusTuiAfterPromptBoxSendCheckBox?.isSelected == true,
        promptBoxCompletionSource = selectedCompletionSource(),
        copilotLanguageServerPath = typedCopilotServerPath(),
        copilotPromptHistoryContext = copilotPromptHistoryContextCheckBox?.isSelected == true,
        escapeModifier = selectedEscapeModifier(),
    ).also { edited ->
        builtInShortcuts.forEach { it.stateProperty.set(edited, it.keyCode) }
    }

    private fun selectedEscapeModifier(): String = if (modifierCombo?.selectedItem == "Alt") "ALT" else "CTRL"

    override fun apply() {
        appsTable?.cellEditor?.stopCellEditing()

        val newApps = tableModel?.snapshot() ?: mutableListOf()
        validateTuiApps(newApps)
        validateShortcuts()
        unregisterRemovedActions(newApps)
        settings.state.tuiApps = newApps
        settings.state.tmuxKeybindingsEnabled = tmuxKeybindingsEnabledCheckBox?.isSelected == true
        settings.state.restoreOpenTabs = restoreOpenTabsCheckBox?.isSelected == true
        settings.state.submitPromptOnSend = submitPromptOnSendCheckBox?.isSelected == true
        settings.state.appendPromptSeparatorOnSend = appendPromptSeparatorCheckBox?.isSelected == true
        settings.state.focusPromptFileAfterSend = focusPromptFileCheckBox?.isSelected == true
        settings.state.rememberPromptBoxVisibilityPerApp = promptBoxVisibilityPerAppCheckBox?.isSelected == true
        settings.state.rememberPromptBoxSizePerApp = promptBoxSizePerAppCheckBox?.isSelected == true
        settings.state.focusTuiAfterPromptBoxSend = focusTuiAfterPromptBoxSendCheckBox?.isSelected == true
        applyCompletionSource()
        settings.state.escapeModifier = selectedEscapeModifier()
        builtInShortcuts.forEach { it.stateProperty.set(settings.state, it.keyCode) }
        settings.loadActions()
    }

    private fun applyCompletionSource() {
        val previousSource = settings.state.promptBoxCompletionSource
        val previousPath = settings.state.copilotLanguageServerPath
        val source = selectedCompletionSource()
        val path = typedCopilotServerPath()
        settings.state.promptBoxCompletionSource = source
        settings.state.copilotLanguageServerPath = path
        settings.state.copilotPromptHistoryContext = copilotPromptHistoryContextCheckBox?.isSelected == true

        val stayedOnCopilot = previousSource == PromptBoxCompletionSource.COPILOT &&
            source == PromptBoxCompletionSource.COPILOT
        val leftCopilot = previousSource == PromptBoxCompletionSource.COPILOT &&
            source != PromptBoxCompletionSource.COPILOT
        when {
            stayedOnCopilot && path != previousPath -> copilotServer.restart()
            leftCopilot -> copilotServer.stop()
        }
    }

    private fun unregisterRemovedActions(newApps: List<TuiAppConfig>) {
        val newActionIds = newApps.mapTo(mutableSetOf()) { ACTION_ID_PREFIX + it.name }
        settings.state.tuiApps
            .map { ACTION_ID_PREFIX + it.name }
            .filterNot { it in newActionIds }
            .forEach { settings.unregisterAction(it) }
    }

    private fun validateShortcuts() {
        shortcutsModel?.duplicateShortcutMessage()?.let { throw ConfigurationException(it) }
    }

    private fun validateTuiApps(apps: List<TuiAppConfig>) {
        apps.forEachIndexed { index, app ->
            val rowNumber = index + 1
            if (app.name.isBlank()) {
                throw ConfigurationException("TUI app name cannot be empty (row $rowNumber).")
            }
            if (app.command.isBlank()) {
                throw ConfigurationException("TUI app command cannot be empty (row $rowNumber).")
            }
        }
    }
}
