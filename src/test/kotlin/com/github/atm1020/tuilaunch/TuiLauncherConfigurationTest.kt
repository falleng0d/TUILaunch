package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.model.TuiAppConfig
import com.github.atm1020.tuilaunch.model.TuiAppTableModel
import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.ui.APPEND_PROMPT_SEPARATOR_LABEL
import com.github.atm1020.tuilaunch.ui.FOCUS_PROMPT_FILE_LABEL
import com.github.atm1020.tuilaunch.ui.RESTORE_OPEN_TABS_LABEL
import com.github.atm1020.tuilaunch.ui.SUBMIT_PROMPT_ON_SEND_LABEL
import com.github.atm1020.tuilaunch.ui.TuiLauncherConfiguration
import com.intellij.openapi.options.ConfigurationException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTable

class TuiLauncherConfigurationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        TuiLauncherSettings.getInstance().state.apply {
            tuiApps.clear()
            tmuxKeybindingsEnabled = true
            restoreOpenTabs = false
            submitPromptOnSend = true
            appendPromptSeparatorOnSend = true
            focusPromptFileAfterSend = true
            escapeModifier = "CTRL"
            escapeKeyCode = null
            focusEditorKeyCode = null
            closeTuiKeyCode = null
            nextTuiKeyCode = null
            previousTuiKeyCode = null
            toggleToolWindowKeyCode = null
            nextTuiWithoutFocusKeyCode = null
            previousTuiWithoutFocusKeyCode = null
        }
    }

    fun testKeySelectionPanelUsesKeymapLikeShortcutTable() {
        val component = TuiLauncherConfiguration().createComponent() as JPanel
        val table = findShortcutTable(component)!!

        assertEquals("Action", table.columnModel.getColumn(0).headerValue)
        assertEquals("Shortcut", table.columnModel.getColumn(1).headerValue)
        assertEquals("Not set", table.model.getValueAt(0, 1))
    }

    fun testExistingAppsHaveLaunchShortcutRows() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "htop", command = "htop"))

        val component = TuiLauncherConfiguration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(9, shortcutTable.rowCount)
        assertEquals("Launch htop", shortcutTable.model.getValueAt(8, 0))
    }

    fun testAddingAppAddsLaunchShortcutRow() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val appsTable = findAppsTable(component)!!
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(8, shortcutTable.rowCount)
        (appsTable.model as TuiAppTableModel).addRow(TuiAppConfig(name = "lazygit", command = "lazygit"))

        assertEquals(9, shortcutTable.rowCount)
        assertEquals("Launch lazygit", shortcutTable.model.getValueAt(8, 0))
    }

    fun testTmuxKeybindingsCheckboxPersistsEnabledFlag() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tmuxKeybindingsEnabled = true
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = "lazygit"))

        val configurable = TuiLauncherConfiguration()
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

        val configurable = TuiLauncherConfiguration()
        configurable.createComponent()

        assertApplyRejects(configurable, "already assigned")
    }

    fun testApplyRejectsDuplicateShortcuts() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.focusEditorKeyCode = KeyEvent.VK_X
        settings.state.closeTuiKeyCode = KeyEvent.VK_X

        val configurable = TuiLauncherConfiguration()
        configurable.createComponent()

        assertApplyRejects(configurable, "already assigned")
    }

    fun testApplyRejectsEmptyName() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "", command = "lazygit"))

        val configurable = TuiLauncherConfiguration()
        configurable.createComponent()

        assertApplyRejects(configurable, "name cannot be empty")
    }

    fun testRejectedInvalidNewAppDoesNotMutatePersistedSettings() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = "lazygit"))

        val configurable = TuiLauncherConfiguration()
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

        val configurable = TuiLauncherConfiguration()
        configurable.createComponent()

        assertApplyRejects(configurable, "command cannot be empty")
    }

    fun testClearingShortcutWithoutSelectionLeavesShortcutsUntouched() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.escapeKeyCode = KeyEvent.VK_B

        val component = TuiLauncherConfiguration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(-1, shortcutTable.selectedRow)
        pressKeyOn(shortcutTable, KeyEvent.VK_DELETE)

        assertEquals("Ctrl+B", shortcutTable.model.getValueAt(0, 1))
    }

    fun testAssigningShortcutWithoutSelectionLeavesShortcutsUntouched() {
        val component = TuiLauncherConfiguration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!

        assertEquals(-1, shortcutTable.selectedRow)
        pressKeyOn(shortcutTable, KeyEvent.VK_A)

        repeat(shortcutTable.rowCount) { row ->
            assertEquals("Not set", shortcutTable.model.getValueAt(row, 1))
        }
    }

    fun testRemoveShortcutButtonIsDisabledWithoutSelection() {
        val component = TuiLauncherConfiguration().createComponent() as JPanel
        val shortcutTable = findShortcutTable(component)!!
        val removeButton = findButton(component, "Remove Shortcut")!!

        assertFalse(removeButton.isEnabled)

        shortcutTable.selectionModel.setSelectionInterval(1, 1)
        assertTrue(removeButton.isEnabled)

        shortcutTable.selectionModel.clearSelection()
        assertFalse(removeButton.isEnabled)
    }

    fun testConfigurableWithoutComponentIsNotModified() {
        assertFalse(TuiLauncherConfiguration().isModified())
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

        val configurable = TuiLauncherConfiguration()
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

        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val appsModel = findAppsTable(component)!!.model as TuiAppTableModel

        val movedApp = appsModel.appAt(0).copy()
        appsModel.removeRow(0)
        appsModel.addRow(movedApp)

        assertEquals(listOf("beta", "alpha"), appsModel.snapshot().map { it.name })
        assertTrue(configurable.isModified())
    }

    fun testAssignedShortcutIsModified() {
        val configurable = TuiLauncherConfiguration()
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

        shortcutTable.selectionModel.setSelectionInterval(8, 8)
        pressKeyOn(shortcutTable, KeyEvent.VK_G)

        assertEquals("G", shortcutTable.model.getValueAt(8, 1))
        assertTrue(configurable.isModified())
    }

    fun testToggledTmuxCheckBoxIsModified() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, "Enable tmux-like prefix keybindings")!!.isSelected = false

        assertTrue(configurable.isModified())
    }

    fun testChangedPrefixModifierIsModified() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel

        findComponent<JComboBox<*>>(component) { it.itemCount == 2 }!!.selectedItem = "Alt"

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
        assertEquals(9, findShortcutTable(component)!!.rowCount)
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresTmuxKeybindingsCheckBox() {
        val configurable = TuiLauncherConfiguration()
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

        val configurable = TuiLauncherConfiguration()
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
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val combo = findComponent<JComboBox<*>>(component) { it.itemCount == 2 }!!

        combo.selectedItem = "Alt"
        configurable.reset()

        assertEquals("Ctrl", combo.selectedItem)
        assertFalse(configurable.isModified())
    }

    fun testResetRestoresPrefixCommandKeyCodes() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.escapeKeyCode = KeyEvent.VK_B

        val configurable = TuiLauncherConfiguration()
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

        shortcutTable.selectionModel.setSelectionInterval(8, 8)
        pressKeyOn(shortcutTable, KeyEvent.VK_G)
        assertTrue(configurable.isModified())

        configurable.reset()

        assertEquals("Not set", shortcutTable.model.getValueAt(8, 1))
        assertFalse(configurable.isModified())
    }

    fun testResetClearsStaleTableSelections() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.tuiApps.add(TuiAppConfig(name = "alpha", command = "alpha"))

        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val appsTable = findAppsTable(component)!!
        val shortcutTable = findShortcutTable(component)!!

        (appsTable.model as TuiAppTableModel).addRow(TuiAppConfig(name = "beta", command = "beta"))
        appsTable.selectionModel.setSelectionInterval(1, 1)
        shortcutTable.selectionModel.setSelectionInterval(9, 9)

        configurable.reset()

        assertEquals(1, appsTable.rowCount)
        assertEquals(9, shortcutTable.rowCount)
        assertEquals(-1, appsTable.selectedRow)
        assertEquals(-1, shortcutTable.selectedRow)
        assertFalse(configurable.isModified())
    }

    fun testResetBeforeAnyEditKeepsPersistedValues() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.escapeModifier = "ALT"
        settings.state.focusEditorKeyCode = KeyEvent.VK_X
        settings.state.tuiApps.add(TuiAppConfig(name = "lazygit", command = "lazygit", shortcutKeyCode = KeyEvent.VK_G))

        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        configurable.reset()

        val shortcutTable = findShortcutTable(component)!!
        assertEquals("X", shortcutTable.model.getValueAt(1, 1))
        assertEquals("G", shortcutTable.model.getValueAt(8, 1))
        assertEquals("Alt", findComponent<JComboBox<*>>(component) { it.itemCount == 2 }!!.selectedItem)
        assertFalse(configurable.isModified())
    }

    fun testResetWithoutComponentIsSafe() {
        val configurable = TuiLauncherConfiguration()

        configurable.reset()

        assertFalse(configurable.isModified())
    }

    fun testRestoreOpenTabsCheckBoxIsPresentAndOffByDefault() {
        val component = TuiLauncherConfiguration().createComponent() as JPanel

        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)

        assertNotNull(checkbox)
        assertFalse(checkbox!!.isSelected)
    }

    fun testRestoreOpenTabsCheckBoxPersistsTheEnabledFlag() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!

        checkbox.isSelected = true
        configurable.apply()

        assertTrue(settings.state.restoreOpenTabs)
    }

    fun testRestoreOpenTabsCheckBoxPersistsBeingTurnedOff() {
        val settings = TuiLauncherSettings.getInstance()
        settings.state.restoreOpenTabs = true

        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!
        assertTrue(checkbox.isSelected)

        checkbox.isSelected = false
        configurable.apply()

        assertFalse(settings.state.restoreOpenTabs)
    }

    fun testTogglingRestoreOpenTabsMarksThePanelModified() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheRestoreOpenTabsCheckBox() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, RESTORE_OPEN_TABS_LABEL)!!

        checkbox.doClick()
        configurable.reset()

        assertFalse(checkbox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testThePromptSendingCheckBoxesArePresentAndOnByDefault() {
        val component = TuiLauncherConfiguration().createComponent() as JPanel

        assertTrue(findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.isSelected)
        assertTrue(findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!.isSelected)
        assertTrue(findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!.isSelected)
    }

    fun testTurningTheFocusPromptFileFlagOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = TuiLauncherConfiguration()
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

        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!
        assertFalse(checkbox.isSelected)

        checkbox.isSelected = true
        configurable.apply()

        assertTrue(settings.state.focusPromptFileAfterSend)
    }

    fun testTogglingTheFocusPromptFileFlagMarksThePanelModified() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresTheFocusPromptFileCheckBox() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel
        val checkbox = findCheckBox(component, FOCUS_PROMPT_FILE_LABEL)!!

        checkbox.doClick()
        configurable.reset()

        assertTrue(checkbox.isSelected)
        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithTheFocusPromptFileFlagOff() {
        TuiLauncherSettings.getInstance().state.focusPromptFileAfterSend = false

        val configurable = TuiLauncherConfiguration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testTurningSubmitPromptOnSendOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel

        findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.isSelected = false
        configurable.apply()

        assertFalse(settings.state.submitPromptOnSend)
        assertTrue(settings.state.appendPromptSeparatorOnSend)
    }

    fun testTurningTheAppendPromptSeparatorOffIsPersisted() {
        val settings = TuiLauncherSettings.getInstance()
        val configurable = TuiLauncherConfiguration()
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

        val configurable = TuiLauncherConfiguration()
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
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, SUBMIT_PROMPT_ON_SEND_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testTogglingTheAppendPromptSeparatorMarksThePanelModified() {
        val configurable = TuiLauncherConfiguration()
        val component = configurable.createComponent() as JPanel

        assertFalse(configurable.isModified())
        findCheckBox(component, APPEND_PROMPT_SEPARATOR_LABEL)!!.doClick()

        assertTrue(configurable.isModified())
    }

    fun testResetRestoresBothPromptSendingCheckBoxes() {
        val configurable = TuiLauncherConfiguration()
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

        val configurable = TuiLauncherConfiguration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    fun testAnUntouchedPanelIsUnmodifiedWithRestoreOpenTabsOn() {
        TuiLauncherSettings.getInstance().state.restoreOpenTabs = true

        val configurable = TuiLauncherConfiguration()
        configurable.createComponent()

        assertFalse(configurable.isModified())
    }

    private fun configurableWithApp(name: String): Pair<TuiLauncherConfiguration, JPanel> {
        TuiLauncherSettings.getInstance().state.tuiApps.add(TuiAppConfig(name = name, command = name))
        val configurable = TuiLauncherConfiguration()
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

    private fun findButton(container: Container, text: String): JButton? =
        findComponent<JButton>(container) { it.text == text }

    private fun findAppsTable(container: Container): JTable? =
        findComponent<JTable>(container) { it.columnCount == 4 }

    private fun findShortcutTable(container: Container): JTable? =
        findComponent<JTable>(container) { it.columnCount == 2 && it.getColumnName(0) == "Action" }
}
