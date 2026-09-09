package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.copilot.CopilotServerControl
import com.github.atm1020.tuilaunch.copilot.CopilotServerLocation
import com.github.atm1020.tuilaunch.copilot.CopilotServerSource
import com.github.atm1020.tuilaunch.copilot.CopilotServerState
import com.github.atm1020.tuilaunch.model.PromptBoxCompletionSource
import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiAppTableModel
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.ui.APPEND_PROMPT_SEPARATOR_LABEL
import com.github.atm1020.tuilaunch.ui.CHECKING_COPILOT_STATUS_TEXT
import com.github.atm1020.tuilaunch.ui.CHECK_COPILOT_STATUS_LABEL
import com.github.atm1020.tuilaunch.ui.COPILOT_COMPLETION_SOURCE_ITEM
import com.github.atm1020.tuilaunch.ui.COPILOT_PROMPT_HISTORY_CONTEXT_LABEL
import com.github.atm1020.tuilaunch.ui.COPILOT_SERVER_HINT_NAME
import com.github.atm1020.tuilaunch.ui.COPILOT_SERVER_PATH_PLACEHOLDER
import com.github.atm1020.tuilaunch.ui.COPILOT_STATUS_NAME
import com.github.atm1020.tuilaunch.ui.DETECTING_COPILOT_SERVER_TEXT
import com.github.atm1020.tuilaunch.ui.FOCUS_PROMPT_FILE_LABEL
import com.github.atm1020.tuilaunch.ui.FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL
import com.github.atm1020.tuilaunch.ui.HIDE_PROMPT_BOX_AFTER_SEND_LABEL
import com.github.atm1020.tuilaunch.ui.JETBRAINS_AI_COMPLETION_SOURCE_ITEM
import com.github.atm1020.tuilaunch.ui.PROMPT_BOX_COMPLETIONS_TITLE
import com.github.atm1020.tuilaunch.ui.PROMPT_BOX_SIZE_PER_APP_LABEL
import com.github.atm1020.tuilaunch.ui.PROMPT_BOX_VISIBILITY_PER_APP_LABEL
import com.github.atm1020.tuilaunch.ui.RESTORE_AGENT_SESSIONS_LABEL
import com.github.atm1020.tuilaunch.ui.RESTORE_OPEN_TABS_LABEL
import com.github.atm1020.tuilaunch.ui.SUBMIT_PROMPT_ON_SEND_LABEL
import com.github.atm1020.tuilaunch.ui.TuiLauncherConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextField
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.border.TitledBorder

class TuiLauncherConfigurationTest : BasePlatformTestCase() {

    private lateinit var copilotServer: FakeCopilotServerControl

    override fun setUp() {
        super.setUp()
        copilotServer = FakeCopilotServerControl()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            tmuxKeybindingsEnabled = true
            restoreOpenTabs = false
            restoreAgentSessions = true
            submitPromptOnSend = true
            appendPromptSeparatorOnSend = true
            focusPromptFileAfterSend = true
            promptBoxVisible = false
            promptBoxPercent = 30
            rememberPromptBoxVisibilityPerApp = false
            rememberPromptBoxSizePerApp = false
            focusTuiAfterPromptBoxSend = false
            hidePromptBoxAfterSend = false
            promptBoxCompletionSource = PromptBoxCompletionSource.JETBRAINS_AI
            copilotLanguageServerPath = ""
            copilotPromptHistoryContext = true
            escapeModifier = "CTRL"
            escapeKeyCode = null
            focusEditorKeyCode = null
            closeTuiKeyCode = null
            nextTuiKeyCode = null
            previousTuiKeyCode = null
            toggleToolWindowKeyCode = null
            nextTuiWithoutFocusKeyCode = null
            previousTuiWithoutFocusKeyCode = null
            focusPromptBoxKeyCode = null
        }
    }

    override fun tearDown() {
        try {
            TuiLauncherSettings.getInstance().state.promptBoxCompletionSource =
                PromptBoxCompletionSource.JETBRAINS_AI
        } finally {
            super.tearDown()
        }
    }

    fun testKeySelectionPanelUsesKeymapLikeShortcutTable() {
        val component = configuration().createComponent() as JPanel
        val table = findShortcutTable(component)!!

        assertEquals("Action", table.columnModel.getColumn(0).headerValue)
        assertEquals("Shortcut", table.columnModel.getColumn(1).headerValue)
        assertEquals("Not set", table.model.getValueAt(0, 1))
    }

    fun testExistingAppsHaveLaunchShortcutRows() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "htop", command = "htop"))

        val component = configuration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(10, shortcutTable.rowCount)
        assertEquals("Launch htop", shortcutTable.model.getValueAt(9, 0))
    }

    fun testAddingAppAddsLaunchShortcutRow() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val appsTable = findAppsTable(component)!!
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(9, shortcutTable.rowCount)
        (appsTable.model as TuiAppTableModel).addRow(TuiAppConfig(name = "lazygit", command = "lazygit"))

        assertEquals(10, shortcutTable.rowCount)
        assertEquals("Launch lazygit", shortcutTable.model.getValueAt(9, 0))
    }

    fun testTmuxKeybindingsCheckboxPersistsEnabledFlag() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tmuxKeybindingsEnabled = true
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = "lazygit"))

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, "Enable tmux-like prefix keybindings")!!

        checkbox.isSelected = false
        configurable.apply()

        assertFalse(settings.state.tmuxKeybindingsEnabled)
    }

    fun testApplyRejectsAppShortcutDuplicate() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.closeTuiKeyCode = KeyEvent.VK_G
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = "lazygit", shortcutKeyCode = KeyEvent.VK_G))

        val configurable = configuration()
        configurable.createComponent()

        assertApplyRejects(configurable, "already assigned")
    }

    fun testApplyRejectsDuplicateShortcuts() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.focusEditorKeyCode = KeyEvent.VK_X
        settings.state.closeTuiKeyCode = KeyEvent.VK_X

        val configurable = configuration()
        configurable.createComponent()

        assertApplyRejects(configurable, "already assigned")
    }

    fun testApplyRejectsEmptyName() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "", command = "lazygit"))

        val configurable = configuration()
        configurable.createComponent()

        assertApplyRejects(configurable, "name cannot be empty")
    }

    fun testRejectedInvalidNewAppDoesNotMutatePersistedSettings() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = "lazygit"))

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val appsTable = findAppsTable(component)!!
        (appsTable.model as TuiAppTableModel).addRow(TuiAppConfig(name = "broken", command = ""))

        try {
            configurable.apply()
            fail("Expected ConfigurationException")
        } catch (_: ConfigurationException) {
        }

        assertEquals(1, settings.state.tuiApps.size)
        assertEquals("lazygit", settings.state.tuiApps.single().name)
    }

    fun testApplyRejectsEmptyCommand() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = ""))

        val configurable = configuration()
        configurable.createComponent()

        assertApplyRejects(configurable, "command cannot be empty")
    }

    fun testClearingShortcutWithoutSelectionLeavesShortcutsUntouched() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.escapeKeyCode = KeyEvent.VK_B

        val component = configuration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(-1, shortcutTable.selectedRow)
        pressKeyOn(shortcutTable, KeyEvent.VK_DELETE)

        assertEquals("Ctrl+B", shortcutTable.model.getValueAt(0, 1))
    }

    fun testAssigningShortcutWithoutSelectionLeavesShortcutsUntouched() {
        val component = configuration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(-1, shortcutTable.selectedRow)
        pressKeyOn(shortcutTable, KeyEvent.VK_A)

        repeat(shortcutTable.rowCount) { row ->
            assertEquals("Not set", shortcutTable.model.getValueAt(row, 1))
        }
    }

    fun testRemoveShortcutButtonIsDisabledWithoutSelection() {
        val component = configuration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!
        val removeButton = findButton(component, "Remove Shortcut")!!

        assertFalse(removeButton.isEnabled)

        shortcutTable.selectionModel.setSelectionInterval(1, 1)
        assertTrue(removeButton.isEnabled)

        shortcutTable.selectionModel.clearSelection()
        assertFalse(removeButton.isEnabled)
    }

    fun testConfigurableWithoutComponentIsNotModified() {
        assertFalse(configuration().isModified())
    }

    fun testUneditedPanelIsNotModified() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.escapeKeyCode = KeyEvent.VK_B
        settings.state.closeTuiKeyCode = KeyEvent.VK_X
        settings.state.escapeModifier = "ALT"
        settings.state.tuiApps.add(
            TuiAppConfig(
                name = "lazygit",
                command = "lazygit",
                options = "--all",
                windowWidth = 400,
                windowHeight = 300,
                shortcutKeyCode = KeyEvent.VK_G,
            )
        )

        val configurable = configuration()
        configurable.createComponent()
        configurable.reset()

        assertFalse(configurable.isModified())
    }

    fun testRenamedAppIsModified() {
        val (configurable, component) = configurableWithApp("lazygit")

        appsModelOf(component).setValueAt("renamed", 0, 0)

        assertTrue(configurable.isModified())
    }

    fun testChangedWindowSizeIsModified() {
        val (configurable, component) = configurableWithApp("lazygit")

        appsModelOf(component).appAt(0).windowWidth = 640

        assertTrue(configurable.isModified())
    }

    fun testReorderedAppsAreModified() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "alpha", command = "alpha"))
        settings.state.tuiApps.add(TuiAppConfig(name = "beta", command = "beta"))

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val appsModel = findAppsTable(component)!!.model as TuiAppTableModel

        val movedApp = appsModel.appAt(0).copy()
        appsModel.removeRow(0)
        appsModel.addRow(movedApp)

        assertEquals(listOf("beta", "alpha"), appsModel.snapshot().map { it.name })
        assertTrue(configurable.isModified())
    }

    fun testAssignedShortcutIsModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        shortcutTable.selectionModel.setSelectionInterval(1, 1)
        pressKeyOn(shortcutTable, KeyEvent.VK_F)

        assertEquals("F", shortcutTable.model.getValueAt(1, 1))
        assertTrue(configurable.isModified())
    }

    fun testAssignedAppShortcutIsModified() {
        val (configurable, component) = configurableWithApp("lazygit")
        val shortcutTable = findShortcutTable(component)!!

        shortcutTable.selectionModel.setSelectionInterval(9, 9)
        pressKeyOn(shortcutTable, KeyEvent.VK_G)

        assertEquals("G", shortcutTable.model.getValueAt(9, 1))
        assertTrue(configurable.isModified())
    }

    fun testToggledTmuxCheckBoxIsModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, "Enable tmux-like prefix keybindings")!!.isSelected = false

        assertTrue(configurable.isModified())
    }

    fun testChangedPrefixModifierIsModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findModifierCombo(component)!!.selectedItem = "Alt"

        assertTrue(configurable.isModified())
    }

    fun testAppliedChangesAreNoLongerModified() {
        val (configurable, component) = configurableWithApp("lazygit")
        val shortcutTable = findShortcutTable(component)!!

        appsModelOf(component).setValueAt("lazydocker", 0, 0)
        shortcutTable.selectionModel.setSelectionInterval(1, 1)
        pressKeyOn(shortcutTable, KeyEvent.VK_F)
        assertTrue(configurable.isModified())

        configurable.apply()

        assertFalse(configurable.isModified())
        assertEquals("lazydocker", TuiLauncherSettings.getInstance().state.tuiApps.single().name)
        assertEquals(KeyEvent.VK_F, TuiLauncherSettings.getInstance().state.focusEditorKeyCode)
    }

    fun testResetRestoresEditedAppRows() {
        val (configurable, component) = configurableWithApp("lazygit")
        val appsModel = appsModelOf(component)

        appsModel.setValueAt("renamed", 0, 0)
        appsModel.addRow(TuiAppConfig(name = "extra", command = "extra"))
        configurable.reset()

        assertEquals(listOf("lazygit"), appsModel.snapshot().map { it.name })
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresRemovedAppRows() {
        val (configurable, component) = configurableWithApp("lazygit")
        val appsModel = appsModelOf(component)

        appsModel.removeRow(0)
        assertTrue(configurable.isModified())
        configurable.reset()

        assertEquals(listOf("lazygit"), appsModel.snapshot().map { it.name })
        assertEquals(10, findShortcutTable(component)!!.rowCount)
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresTmuxKeybindingsCheckBox() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, "Enable tmux-like prefix keybindings")!!

        checkbox.doClick()
        assertFalse(findButton(component, "Record Shortcut")!!.isEnabled)

        configurable.reset()

        assertTrue(checkbox.isSelected)
        assertTrue(findButton(component, "Record Shortcut")!!.isEnabled)
        assertFalse(configurable.isModified())
    }

    fun testResetReenablesShortcutControlsDisabledByEdit() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tmuxKeybindingsEnabled = false

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, "Enable tmux-like prefix keybindings")!!
        val recordButton = findButton(component, "Record Shortcut")!!

        assertFalse(recordButton.isEnabled)
        checkbox.doClick()
        assertTrue(recordButton.isEnabled)

        configurable.reset()

        assertFalse(checkbox.isSelected)
        assertFalse(recordButton.isEnabled)
        assertFalse(findButton(component, "Remove Shortcut")!!.isEnabled)
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresPrefixModifier() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val combo = findModifierCombo(component)!!

        combo.selectedItem = "Alt"
        configurable.reset()

        assertEquals("Ctrl", combo.selectedItem)
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresPrefixCommandKeyCodes() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.escapeKeyCode = KeyEvent.VK_B

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        for (row in 0 until shortcutTable.rowCount) {
            shortcutTable.selectionModel.setSelectionInterval(row, row)
            pressKeyOn(shortcutTable, KeyEvent.VK_1 + row)
        }
        assertTrue(configurable.isModified())

        configurable.reset()

        assertEquals("Ctrl+B", shortcutTable.model.getValueAt(0, 1))
        for (row in 1 until shortcutTable.rowCount) {
            assertEquals("Not set", shortcutTable.model.getValueAt(row, 1))
        }
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresAppShortcutKeyCode() {
        val (configurable, component) = configurableWithApp("lazygit")
        val shortcutTable = findShortcutTable(component)!!

        shortcutTable.selectionModel.setSelectionInterval(9, 9)
        pressKeyOn(shortcutTable, KeyEvent.VK_G)
        assertTrue(configurable.isModified())

        configurable.reset()

        assertEquals("Not set", shortcutTable.model.getValueAt(9, 1))
        assertFalse(configurable.isModified())
    }

    fun testResetClearsStaleTableSelections() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "alpha", command = "alpha"))

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val appsTable = findAppsTable(component)!!
        val shortcutTable = findShortcutTable(component)!!

        (appsTable.model as TuiAppTableModel).addRow(TuiAppConfig(name = "beta", command = "beta"))
        appsTable.selectionModel.setSelectionInterval(1, 1)
        shortcutTable.selectionModel.setSelectionInterval(10, 10)

        configurable.reset()

        assertEquals(1, appsTable.rowCount)
        assertEquals(10, shortcutTable.rowCount)
        assertEquals(-1, appsTable.selectedRow)
        assertEquals(-1, shortcutTable.selectedRow)
        assertFalse(configurable.isModified())
    }

    fun testResetBeforeAnyEditKeepsPersistedValues() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.escapeModifier = "ALT"
        settings.state.focusEditorKeyCode = KeyEvent.VK_X
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = "lazygit", shortcutKeyCode = KeyEvent.VK_G))

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        configurable.reset()

        val shortcutTable = findShortcutTable(component)!!
        assertEquals("X", shortcutTable.model.getValueAt(1, 1))
        assertEquals("G", shortcutTable.model.getValueAt(9, 1))
        assertEquals("Alt", findModifierCombo(component)!!.selectedItem)
        assertFalse(configurable.isModified())
    }

    fun testResetWithoutComponentIsSafe() {
        val configurable = configuration()

        configurable.reset()

        assertFalse(configurable.isModified())
    }

    fun testRestoreOpenTabsCheckBoxIsPresentAndOffByDefault() {
        val component = configuration().createComponent() as JPanel

        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)

        assertNotNull(checkbox)
        assertFalse(checkbox!!.isSelected)
    }

    fun testRestoreOpenTabsCheckBoxPersistsTheEnabledFlag() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!

        checkbox.isSelected = true
        configurable.apply()

        assertTrue(settings.state.restoreOpenTabs)
    }

    fun testRestoreOpenTabsCheckBoxPersistsBeingTurnedOff() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.restoreOpenTabs = true

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!
        assertTrue(checkbox.isSelected)

        checkbox.isSelected = false
        configurable.apply()

        assertFalse(settings.state.restoreOpenTabs)
    }

    fun testTogglingRestoreOpenTabsMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheRestoreOpenTabsCheckBox() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!

        checkbox.doClick()
        configurable.reset()

        assertFalse(checkbox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testTheAgentSessionCheckBoxSitsUnderTheReopenTabsCheckBoxAndFollowsIt() {
        val component = configuration().createComponent() as JPanel
        val reopenTabs = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!
        val resumeSessions = findCheckBox(component, RESTORE_AGENT_SESSIONS_LABEL)!!

        assertSame(reopenTabs.parent, resumeSessions.parent)
        assertEquals(positionInItsPanel(reopenTabs) + 1, positionInItsPanel(resumeSessions))
        assertTrue(resumeSessions.isSelected)
        assertFalse(resumeSessions.isEnabled)

        reopenTabs.doClick()

        assertTrue(resumeSessions.isEnabled)
    }

    fun testTheSessionOptionCheckBoxesLineUpUnderTheAppTableAtAnyPageWidth() {
        val page = configuration().createComponent() as JPanel
        layOutTheTree(page, PAGE_WIDTH, PAGE_HEIGHT)
        val justWideEnoughForTheWidestOption =
            2 * leftEdgeIn(page, appsTablePanel(page)) + widestSessionOptionWidth(page)

        listOf(PAGE_WIDTH, justWideEnoughForTheWidestOption).forEach { pageWidth ->
            layOutTheTree(page, pageWidth, PAGE_HEIGHT)
            val tableLeft = leftEdgeIn(page, appsTablePanel(page))

            assertTrue(tableLeft > 0)
            SESSION_OPTION_LABELS.forEach { label ->
                val checkBox = findCheckBox(page, label)!!
                val left = leftEdgeIn(page, checkBox)
                val message = "$label on a $pageWidth px page"

                assertEquals(message, tableLeft, left)
                assertTrue(message, left + checkBox.preferredSize.width <= pageWidth)
            }
        }
    }

    fun testTheAgentSessionCheckBoxIsTheOnlyIndentedSessionOption() {
        val page = configuration().createComponent() as JPanel
        layOutTheTree(page, PAGE_WIDTH, PAGE_HEIGHT)
        val plainIndents = SESSION_OPTION_LABELS
            .filterNot { it == RESTORE_AGENT_SESSIONS_LABEL }
            .map { findCheckBox(page, it)!!.insets.left }
        val resumeSessionsIndent = findCheckBox(page, RESTORE_AGENT_SESSIONS_LABEL)!!.insets.left

        assertEquals(listOf(plainIndents.first()), plainIndents.distinct())
        assertTrue(resumeSessionsIndent > plainIndents.first())
    }

    fun testThePromptBoxCompletionsGroupSitsAtTheLeftUnderTheSessionOptions() {
        val page = configuration().createComponent() as JPanel
        layOutTheTree(page, PAGE_WIDTH, PAGE_HEIGHT)
        val completions = promptBoxCompletionsPanel(page)
        val lastOption = findCheckBox(page, HIDE_PROMPT_BOX_AFTER_SEND_LABEL)!!

        assertEquals(leftEdgeIn(page, appsTablePanel(page)), leftEdgeIn(page, completions))
        assertTrue(topEdgeIn(page, completions) >= topEdgeIn(page, lastOption) + lastOption.height)
    }

    fun testTurningTheAgentSessionResumeOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, RESTORE_AGENT_SESSIONS_LABEL)!!.isSelected = false
        configurable.apply()

        assertFalse(settings.state.restoreAgentSessions)
        assertFalse(settings.state.restoreOpenTabs)
    }

    fun testTurningTheAgentSessionResumeBackOnIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.restoreAgentSessions = false

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, RESTORE_AGENT_SESSIONS_LABEL)!!
        assertFalse(checkbox.isSelected)

        checkbox.isSelected = true
        configurable.apply()

        assertTrue(settings.state.restoreAgentSessions)
    }

    fun testTogglingTheAgentSessionResumeMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, RESTORE_AGENT_SESSIONS_LABEL)!!.isSelected = false

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheAgentSessionCheckBoxAndItsEnabledState() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val reopenTabs = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!
        val resumeSessions = findCheckBox(component, RESTORE_AGENT_SESSIONS_LABEL)!!

        reopenTabs.doClick()
        resumeSessions.isSelected = false
        configurable.reset()

        assertTrue(resumeSessions.isSelected)
        assertFalse(resumeSessions.isEnabled)
        assertFalse(configurable.isModified())
    }

    fun testThePromptSendingCheckBoxesArePresentAndOnByDefault() {
        val component = configuration().createComponent() as JPanel

        assertTrue(findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.isSelected)
        assertTrue(findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!.isSelected)
        assertTrue(findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!.isSelected)
    }

    fun testTurningTheFocusPromptFileFlagOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!.isSelected = false
        configurable.apply()

        assertFalse(settings.state.focusPromptFileAfterSend)
        assertTrue(settings.state.submitPromptOnSend)
        assertTrue(settings.state.appendPromptSeparatorOnSend)
    }

    fun testTurningTheFocusPromptFileFlagBackOnIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.focusPromptFileAfterSend = false

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!
        assertFalse(checkbox.isSelected)

        checkbox.isSelected = true
        configurable.apply()

        assertTrue(settings.state.focusPromptFileAfterSend)
    }

    fun testTogglingTheFocusPromptFileFlagMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheFocusPromptFileCheckBox() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!

        checkbox.doClick()
        configurable.reset()

        assertTrue(checkbox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithTheFocusPromptFileFlagOff() {
        TuiLauncherSettings.getInstance().state.focusPromptFileAfterSend = false

        val configurable = configuration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testTurningSubmitPromptOnSendOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.isSelected = false
        configurable.apply()

        assertFalse(settings.state.submitPromptOnSend)
        assertTrue(settings.state.appendPromptSeparatorOnSend)
    }

    fun testTurningTheAppendPromptSeparatorOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!.isSelected = false
        configurable.apply()

        assertFalse(settings.state.appendPromptSeparatorOnSend)
        assertTrue(settings.state.submitPromptOnSend)
    }

    fun testTurningThePromptSendingFlagsBackOnIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.submitPromptOnSend = false
        settings.state.appendPromptSeparatorOnSend = false

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        assertFalse(findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.isSelected)
        assertFalse(findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!.isSelected)

        findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.isSelected = true
        findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!.isSelected = true
        configurable.apply()

        assertTrue(settings.state.submitPromptOnSend)
        assertTrue(settings.state.appendPromptSeparatorOnSend)
    }

    fun testTogglingSubmitPromptOnSendMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testTogglingTheAppendPromptSeparatorMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresBothPromptSendingCheckBoxes() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val submitCheckBox = findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!
        val separatorCheckBox = findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!

        submitCheckBox.doClick()
        separatorCheckBox.doClick()
        configurable.reset()

        assertTrue(submitCheckBox.isSelected)
        assertTrue(separatorCheckBox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithThePromptSendingFlagsOff() {
        TuiLauncherSettings.getInstance().state.submitPromptOnSend = false
        TuiLauncherSettings.getInstance().state.appendPromptSeparatorOnSend = false

        val configurable = configuration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithRestoreOpenTabsOn() {
        TuiLauncherSettings.getInstance().state.restoreOpenTabs = true

        val configurable = configuration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testThePromptBoxPerAppCheckBoxesArePresentAndOffByDefault() {
        val component = configuration().createComponent() as JPanel

        assertFalse(findCheckBox(component, PROMPT_BOX_VISIBILITY_PER_APP_LABEL)!!.isSelected)
        assertFalse(findCheckBox(component, PROMPT_BOX_SIZE_PER_APP_LABEL)!!.isSelected)
    }

    fun testTurningThePromptBoxPerAppModesOnIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, PROMPT_BOX_VISIBILITY_PER_APP_LABEL)!!.isSelected = true
        findCheckBox(component, PROMPT_BOX_SIZE_PER_APP_LABEL)!!.isSelected = true
        configurable.apply()

        assertTrue(settings.state.rememberPromptBoxVisibilityPerApp)
        assertTrue(settings.state.rememberPromptBoxSizePerApp)
    }

    fun testTurningThePromptBoxPerAppModesBackOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.rememberPromptBoxVisibilityPerApp = true
        settings.state.rememberPromptBoxSizePerApp = true

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        assertTrue(findCheckBox(component, PROMPT_BOX_VISIBILITY_PER_APP_LABEL)!!.isSelected)
        assertTrue(findCheckBox(component, PROMPT_BOX_SIZE_PER_APP_LABEL)!!.isSelected)

        findCheckBox(component, PROMPT_BOX_VISIBILITY_PER_APP_LABEL)!!.isSelected = false
        findCheckBox(component, PROMPT_BOX_SIZE_PER_APP_LABEL)!!.isSelected = false
        configurable.apply()

        assertFalse(settings.state.rememberPromptBoxVisibilityPerApp)
        assertFalse(settings.state.rememberPromptBoxSizePerApp)
    }

    fun testTogglingAPromptBoxPerAppModeMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, PROMPT_BOX_SIZE_PER_APP_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresBothPromptBoxPerAppCheckBoxes() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val visibilityCheckBox = findCheckBox(component, PROMPT_BOX_VISIBILITY_PER_APP_LABEL)!!
        val sizeCheckBox = findCheckBox(component, PROMPT_BOX_SIZE_PER_APP_LABEL)!!

        visibilityCheckBox.doClick()
        sizeCheckBox.doClick()
        configurable.reset()

        assertFalse(visibilityCheckBox.isSelected)
        assertFalse(sizeCheckBox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithThePromptBoxPerAppModesOn() {
        TuiLauncherSettings.getInstance().state.rememberPromptBoxVisibilityPerApp = true
        TuiLauncherSettings.getInstance().state.rememberPromptBoxSizePerApp = true

        val configurable = configuration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testTheFocusTuiAfterSendCheckBoxIsPresentAndOffByDefault() {
        val component = configuration().createComponent() as JPanel

        assertFalse(findCheckBox(component, FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL)!!.isSelected)
    }

    fun testTurningTheFocusTuiAfterSendFlagOnIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL)!!.isSelected = true
        configurable.apply()

        assertTrue(settings.state.focusTuiAfterPromptBoxSend)
    }

    fun testTurningTheFocusTuiAfterSendFlagBackOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.focusTuiAfterPromptBoxSend = true

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL)!!
        assertTrue(checkbox.isSelected)

        checkbox.isSelected = false
        configurable.apply()

        assertFalse(settings.state.focusTuiAfterPromptBoxSend)
    }

    fun testTogglingTheFocusTuiAfterSendFlagMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheFocusTuiAfterSendCheckBox() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL)!!

        checkbox.doClick()
        configurable.reset()

        assertFalse(checkbox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithTheFocusTuiAfterSendFlagOn() {
        TuiLauncherSettings.getInstance().state.focusTuiAfterPromptBoxSend = true

        val configurable = configuration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testTheHidePromptBoxAfterSendCheckBoxIsPresentAndOffByDefault() {
        val component = configuration().createComponent() as JPanel

        assertFalse(findCheckBox(component, HIDE_PROMPT_BOX_AFTER_SEND_LABEL)!!.isSelected)
    }

    fun testTurningTheHidePromptBoxAfterSendFlagOnIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, HIDE_PROMPT_BOX_AFTER_SEND_LABEL)!!.isSelected = true
        configurable.apply()

        assertTrue(settings.state.hidePromptBoxAfterSend)
        assertFalse(settings.state.focusTuiAfterPromptBoxSend)
    }

    fun testTurningTheHidePromptBoxAfterSendFlagBackOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.hidePromptBoxAfterSend = true

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, HIDE_PROMPT_BOX_AFTER_SEND_LABEL)!!
        assertTrue(checkbox.isSelected)

        checkbox.isSelected = false
        configurable.apply()

        assertFalse(settings.state.hidePromptBoxAfterSend)
    }

    fun testTogglingTheHidePromptBoxAfterSendFlagMarksThePanelModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, HIDE_PROMPT_BOX_AFTER_SEND_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheHidePromptBoxAfterSendCheckBox() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, HIDE_PROMPT_BOX_AFTER_SEND_LABEL)!!

        checkbox.doClick()
        configurable.reset()

        assertFalse(checkbox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithTheHidePromptBoxAfterSendFlagOn() {
        TuiLauncherSettings.getInstance().state.hidePromptBoxAfterSend = true

        val configurable = configuration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testFocusingThePromptBoxIsTheLastBuiltInPrefixCommandRow() {
        val component = configuration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(9, shortcutTable.rowCount)
        assertEquals("Focus prompt box", shortcutTable.model.getValueAt(8, 0))
        assertEquals("Not set", shortcutTable.model.getValueAt(8, 1))
    }

    fun testAssigningThePromptBoxPrefixKeyIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        shortcutTable.selectionModel.setSelectionInterval(8, 8)
        pressKeyOn(shortcutTable, KeyEvent.VK_B)
        assertTrue(configurable.isModified())

        configurable.apply()

        assertEquals(KeyEvent.VK_B, settings.state.focusPromptBoxKeyCode)
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresThePromptBoxPrefixKey() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        shortcutTable.selectionModel.setSelectionInterval(8, 8)
        pressKeyOn(shortcutTable, KeyEvent.VK_B)
        configurable.reset()

        assertEquals("Not set", shortcutTable.model.getValueAt(8, 1))
        assertFalse(configurable.isModified())
    }

    fun testThePromptBoxAsksJetBrainsAiForCompletionsUntilCopilotIsChosen() {
        val component = configuration().createComponent() as JPanel

        assertEquals(JETBRAINS_AI_COMPLETION_SOURCE_ITEM, findCompletionSourceCombo(component)!!.selectedItem)
        assertEquals(
            listOf(JETBRAINS_AI_COMPLETION_SOURCE_ITEM, COPILOT_COMPLETION_SOURCE_ITEM),
            comboItems(findCompletionSourceCombo(component)!!),
        )
        assertFalse(findServerPathField(component)!!.isEnabled)
        assertFalse(findLabel(component, COPILOT_SERVER_HINT_NAME)!!.isEnabled)
        assertFalse(findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.isEnabled)
        assertFalse(findButton(component, CHECK_COPILOT_STATUS_LABEL)!!.isEnabled)
    }

    fun testChoosingGitHubCopilotEnablesItsSettings() {
        val component = configuration().createComponent() as JPanel

        findCompletionSourceCombo(component)!!.selectedItem = COPILOT_COMPLETION_SOURCE_ITEM

        assertTrue(findServerPathField(component)!!.isEnabled)
        assertTrue(findLabel(component, COPILOT_SERVER_HINT_NAME)!!.isEnabled)
        assertTrue(findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.isEnabled)
        assertTrue(findButton(component, CHECK_COPILOT_STATUS_LABEL)!!.isEnabled)
    }

    fun testTheServerPathFieldOffersAutoDetectionAndTheHistoryContextIsOn() {
        val component = configuration().createComponent() as JPanel

        assertEquals("", findServerPathField(component)!!.text)
        assertEquals(COPILOT_SERVER_PATH_PLACEHOLDER, serverPathPlaceholder(component))
        assertTrue(findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.isSelected)
    }

    fun testTheHintNamesTheAutoDetectedServerAndThenTheTypedOne() {
        copilotServer.locations = { path ->
            if (path.isEmpty()) foundInPlugin("/plugins/copilot/copilot-language-server") else foundAt(path)
        }
        val component = configuration().createComponent() as JPanel

        awaitTheServerHint(component, "Found in the GitHub Copilot plugin: /plugins/copilot/copilot-language-server")

        findServerPathField(component)!!.text = "/opt/copilot/copilot-language-server"

        assertEquals(
            "Found at the configured path: /opt/copilot/copilot-language-server",
            findLabel(component, COPILOT_SERVER_HINT_NAME)!!.text,
        )
        assertEquals(listOf("", "/opt/copilot/copilot-language-server"), copilotServer.locatedPaths)
    }

    fun testTheServerIsAutoDetectedOffTheEventDispatchThread() {
        copilotServer.locations = { foundInPlugin("/plugins/copilot/copilot-language-server") }
        val component = configuration().createComponent() as JPanel

        assertEquals(DETECTING_COPILOT_SERVER_TEXT, findLabel(component, COPILOT_SERVER_HINT_NAME)!!.text)
        awaitTheServerHint(component, "Found in the GitHub Copilot plugin: /plugins/copilot/copilot-language-server")

        assertEquals(listOf(false), copilotServer.autoDetectionsOnTheEdt)
    }

    fun testADisposedPanelIsNeverModified() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        findCompletionSourceCombo(component)!!.selectedItem = COPILOT_COMPLETION_SOURCE_ITEM
        assertTrue(configurable.isModified())

        configurable.disposeUIResources()

        assertFalse(configurable.isModified())
    }

    fun testChoosingGitHubCopilotIsPersistedAndStartsTheServer() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCompletionSourceCombo(component)!!.selectedItem = COPILOT_COMPLETION_SOURCE_ITEM
        findServerPathField(component)!!.text = "/opt/copilot/copilot-language-server"
        findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.isSelected = false
        assertTrue(configurable.isModified())
        configurable.apply()

        assertEquals(PromptBoxCompletionSource.COPILOT, settings.state.promptBoxCompletionSource)
        assertEquals("/opt/copilot/copilot-language-server", settings.state.copilotLanguageServerPath)
        assertFalse(settings.state.copilotPromptHistoryContext)
        assertEquals(1, copilotServer.starts)
        assertEquals(0, copilotServer.restarts)
        assertEquals(0, copilotServer.stops)
        assertFalse(configurable.isModified())
    }

    fun testAChangedServerPathRestartsTheServerWhileCopilotStaysSelected() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.promptBoxCompletionSource = PromptBoxCompletionSource.COPILOT
        settings.state.copilotLanguageServerPath = "/opt/copilot/copilot-language-server"

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        findServerPathField(component)!!.text = "/usr/local/bin/copilot-language-server"
        configurable.apply()

        assertEquals("/usr/local/bin/copilot-language-server", settings.state.copilotLanguageServerPath)
        assertEquals(1, copilotServer.restarts)
        assertEquals(0, copilotServer.starts)
        assertEquals(0, copilotServer.stops)
    }

    fun testAnUnchangedServerPathLeavesTheServerAlone() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.promptBoxCompletionSource = PromptBoxCompletionSource.COPILOT
        settings.state.copilotLanguageServerPath = "/opt/copilot/copilot-language-server"

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.isSelected = false
        configurable.apply()

        assertEquals(0, copilotServer.restarts)
        assertEquals(0, copilotServer.starts)
        assertEquals(0, copilotServer.stops)
    }

    fun testGoingBackToJetBrainsAiStopsTheServer() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.promptBoxCompletionSource = PromptBoxCompletionSource.COPILOT

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        assertEquals(COPILOT_COMPLETION_SOURCE_ITEM, findCompletionSourceCombo(component)!!.selectedItem)

        findCompletionSourceCombo(component)!!.selectedItem = JETBRAINS_AI_COMPLETION_SOURCE_ITEM
        configurable.apply()

        assertEquals(PromptBoxCompletionSource.JETBRAINS_AI, settings.state.promptBoxCompletionSource)
        assertEquals(1, copilotServer.stops)
        assertEquals(0, copilotServer.starts)
        assertEquals(0, copilotServer.restarts)
    }

    fun testTogglingTheHistoryContextMarksThePanelModified() {
        TuiLauncherSettings.getInstance().state.promptBoxCompletionSource = PromptBoxCompletionSource.COPILOT

        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheCompletionSettingsAndTheirEnabledState() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel

        findCompletionSourceCombo(component)!!.selectedItem = COPILOT_COMPLETION_SOURCE_ITEM
        findServerPathField(component)!!.text = "/opt/copilot/copilot-language-server"
        findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.doClick()
        configurable.reset()

        assertEquals(JETBRAINS_AI_COMPLETION_SOURCE_ITEM, findCompletionSourceCombo(component)!!.selectedItem)
        assertEquals("", findServerPathField(component)!!.text)
        assertTrue(findCheckBox(component, COPILOT_PROMPT_HISTORY_CONTEXT_LABEL)!!.isSelected)
        assertFalse(findServerPathField(component)!!.isEnabled)
        assertFalse(findButton(component, CHECK_COPILOT_STATUS_LABEL)!!.isEnabled)
        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithCopilotSelected() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.promptBoxCompletionSource = PromptBoxCompletionSource.COPILOT
        settings.state.copilotLanguageServerPath = "/opt/copilot/copilot-language-server"
        settings.state.copilotPromptHistoryContext = false

        val configurable = configuration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testTheStatusCheckReportsTheSignedInUserForTheTypedPath() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        findCompletionSourceCombo(component)!!.selectedItem = COPILOT_COMPLETION_SOURCE_ITEM
        findServerPathField(component)!!.text = "/opt/copilot/copilot-language-server"

        findButton(component, CHECK_COPILOT_STATUS_LABEL)!!.doClick()

        assertEquals(listOf("/opt/copilot/copilot-language-server"), copilotServer.statusRequests)
        assertEquals(CHECKING_COPILOT_STATUS_TEXT, findLabel(component, COPILOT_STATUS_NAME)!!.text)

        copilotServer.answerStatus(CopilotServerState.Ready("falleng0d"))

        assertEquals("Signed in as falleng0d", findLabel(component, COPILOT_STATUS_NAME)!!.text)
    }

    fun testAStatusResultThatArrivesAfterAResetIsDropped() {
        val configurable = configuration()
        val component = configurable.createComponent() as JPanel
        findCompletionSourceCombo(component)!!.selectedItem = COPILOT_COMPLETION_SOURCE_ITEM

        findButton(component, CHECK_COPILOT_STATUS_LABEL)!!.doClick()
        configurable.reset()
        copilotServer.answerStatus(CopilotServerState.NotSignedIn("NotSignedIn"))

        assertEquals("", findLabel(component, COPILOT_STATUS_NAME)!!.text)
    }

    private fun configuration(): TuiLauncherConfiguration = TuiLauncherConfiguration(copilotServer)

    private fun foundAt(path: String): CopilotServerLocation =
        CopilotServerLocation.Found(Path.of(path), CopilotServerSource.EXPLICIT_PATH)

    private fun foundInPlugin(path: String): CopilotServerLocation =
        CopilotServerLocation.Found(Path.of(path), CopilotServerSource.COPILOT_PLUGIN)

    private fun comboItems(combo: JComboBox<*>): List<Any?> = (0 until combo.itemCount).map { combo.getItemAt(it) }

    private fun awaitTheServerHint(component: JPanel, expected: String) {
        PlatformTestUtil.waitWithEventsDispatching(
            "The Copilot server hint never named the auto-detected server",
            { findLabel(component, COPILOT_SERVER_HINT_NAME)?.text == expected },
            AUTO_DETECTION_TIMEOUT_SECONDS,
        )
    }

    private fun serverPathPlaceholder(component: JPanel): String =
        (findServerPathField(component)!!.textField as JBTextField).emptyText.text

    private class FakeCopilotServerControl : CopilotServerControl {
        var locations: (String) -> CopilotServerLocation = { CopilotServerLocation.NotFound("nothing was tried") }
        val locatedPaths = CopyOnWriteArrayList<String>()
        val autoDetectionsOnTheEdt = CopyOnWriteArrayList<Boolean>()
        val statusRequests = mutableListOf<String>()
        var starts = 0
        var restarts = 0
        var stops = 0

        private var pendingStatusResult: ((CopilotServerState) -> Unit)? = null

        override fun locate(explicitPath: String): CopilotServerLocation {
            locatedPaths += explicitPath
            if (explicitPath.isEmpty()) {
                autoDetectionsOnTheEdt += ApplicationManager.getApplication().isDispatchThread
            }
            return locations(explicitPath)
        }

        override fun checkStatus(explicitPath: String, onResult: (CopilotServerState) -> Unit) {
            statusRequests += explicitPath
            pendingStatusResult = onResult
        }

        override fun ensureStarted() {
            starts++
        }

        override fun restart() {
            restarts++
        }

        override fun stop() {
            stops++
        }

        fun answerStatus(state: CopilotServerState) {
            val result = pendingStatusResult ?: throw AssertionError("no status check is waiting for a result")
            pendingStatusResult = null
            result(state)
        }
    }

    private fun configurableWithApp(name: String): Pair<TuiLauncherConfiguration, JPanel> {
        TuiLauncherSettings.getInstance().state.tuiApps.add(TuiAppConfig(name = name, command = name))
        val configurable = configuration()
        return configurable to (configurable.createComponent() as JPanel)
    }

    private fun appsModelOf(component: JPanel): TuiAppTableModel =
        findAppsTable(component)!!.model as TuiAppTableModel

    private fun pressKeyOn(table: JTable, keyCode: Int) {
        val event = KeyEvent(table, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, KeyEvent.CHAR_UNDEFINED)
        val shortcutKeyListeners = table.keyListeners.filterIsInstance<KeyAdapter>()
        assertTrue(shortcutKeyListeners.isNotEmpty())
        shortcutKeyListeners.forEach { it.keyPressed(event) }
    }

    private fun assertApplyRejects(configurable: TuiLauncherConfiguration, expectedMessagePart: String) {
        try {
            configurable.apply()
        } catch (e: ConfigurationException) {
            assertTrue(e.localizedMessage.contains(expectedMessagePart))
            return
        }
        fail("Expected ConfigurationException")
    }

    private fun layOutTheTree(page: JPanel, width: Int, height: Int) {
        page.setBounds(0, 0, width, height)
        layOutEveryContainer(page)
    }

    private fun layOutEveryContainer(component: Component) {
        if (component !is Container) return
        component.doLayout()
        component.components.forEach { layOutEveryContainer(it) }
    }

    private fun leftEdgeIn(page: JPanel, component: Component): Int {
        var left = 0
        var current: Component? = component
        while (current != null && current !== page) {
            left += current.x
            current = current.parent
        }
        assertSame(page, current)
        return left
    }

    private fun topEdgeIn(page: JPanel, component: Component): Int {
        var top = 0
        var current: Component? = component
        while (current != null && current !== page) {
            top += current.y
            current = current.parent
        }
        assertSame(page, current)
        return top
    }

    private fun widestSessionOptionWidth(page: JPanel): Int =
        SESSION_OPTION_LABELS.maxOf { findCheckBox(page, it)!!.preferredSize.width }

    private fun appsTablePanel(page: JPanel): Component =
        generateSequence(findAppsTable(page) as Component) { it.parent }.first { it.parent === page }

    private fun promptBoxCompletionsPanel(page: JPanel): Component =
        findComponent<JPanel>(page) { (it.border as? TitledBorder)?.title == PROMPT_BOX_COMPLETIONS_TITLE }!!

    private fun descendantsOf(container: Container): Sequence<Component> = sequence {
        for (component in container.components) {
            yield(component)
            if (component is Container) yieldAll(descendantsOf(component))
        }
    }

    private inline fun <reified T : Component> findComponent(
        container: Container,
        crossinline matches: (T) -> Boolean,
    ): T? = descendantsOf(container).filterIsInstance<T>().firstOrNull { matches(it) }

    private fun findCheckBox(container: Container, text: String): JCheckBox? =
        findComponent<JCheckBox>(container) { it.text == text }

    private fun positionInItsPanel(component: Component): Int =
        component.parent?.components?.indexOf(component) ?: -1

    private fun findButton(container: Container, text: String): JButton? =
        findComponent<JButton>(container) { it.text == text }

    private fun findLabel(container: Container, name: String): JLabel? =
        findComponent<JLabel>(container) { it.name == name }

    private fun findServerPathField(container: Container): TextFieldWithBrowseButton? =
        findComponent<TextFieldWithBrowseButton>(container) { true }

    private fun findModifierCombo(container: Container): JComboBox<*>? =
        findComponent<JComboBox<*>>(container) { it.getItemAt(0) == "Ctrl" }

    private fun findCompletionSourceCombo(container: Container): JComboBox<*>? =
        findComponent<JComboBox<*>>(container) { it.getItemAt(0) == JETBRAINS_AI_COMPLETION_SOURCE_ITEM }

    private fun findAppsTable(container: Container): JTable? =
        findComponent<JTable>(container) { it.columnCount == 4 }

    private fun findShortcutTable(container: Container): JTable? =
        findComponent<JTable>(container) { it.columnCount == 2 && it.getColumnName(0) == "Action" }

    private companion object {
        const val AUTO_DETECTION_TIMEOUT_SECONDS = 30
        const val PAGE_WIDTH = 1200
        const val PAGE_HEIGHT = 900

        val SESSION_OPTION_LABELS = listOf(
            RESTORE_OPEN_TABS_LABEL,
            RESTORE_AGENT_SESSIONS_LABEL,
            SUBMIT_PROMPT_ON_SEND_LABEL,
            APPEND_PROMPT_SEPARATOR_LABEL,
            FOCUS_PROMPT_FILE_LABEL,
            PROMPT_BOX_VISIBILITY_PER_APP_LABEL,
            PROMPT_BOX_SIZE_PER_APP_LABEL,
            FOCUS_TUI_AFTER_PROMPT_BOX_SEND_LABEL,
            HIDE_PROMPT_BOX_AFTER_SEND_LABEL,
        )
    }
}
