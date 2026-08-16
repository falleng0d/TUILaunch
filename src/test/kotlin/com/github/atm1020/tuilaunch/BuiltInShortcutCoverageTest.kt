package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.services.TuiLauncherSettings
import com.github.atm1020.tuilaunch.ui.TuiLauncherConfiguration
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BuiltInShortcutCoverageTest : BasePlatformTestCase() {

    fun testEveryStateKeyCodePropertyHasABuiltInShortcut() {
        assertEquals(stateKeyCodePropertyNames(), builtInShortcutPropertyNames().toSet())
    }

    fun testBuiltInShortcutsDoNotRepeatAStateProperty() {
        val propertyNames = builtInShortcutPropertyNames()

        assertEquals(propertyNames.size, propertyNames.toSet().size)
    }

    fun testPrefixIsTheOnlyShortcutRowWithAModifier() {
        val shortcuts = TuiLauncherConfiguration().builtInShortcuts

        assertEquals("Prefix", shortcuts.first().actionName)
        assertEquals(listOf(true) + List(shortcuts.size - 1) { false }, shortcuts.map { it.includeModifier })
    }

    private fun builtInShortcutPropertyNames(): List<String> =
        TuiLauncherConfiguration().builtInShortcuts.map { it.stateProperty.name }

    private fun stateKeyCodePropertyNames(): Set<String> = TuiLauncherSettings.State::class.java.declaredFields
        .filterNot { it.isSynthetic }
        .filter { it.type == Int::class.javaObjectType && it.name.endsWith("KeyCode") }
        .mapTo(mutableSetOf()) { it.name }
}
