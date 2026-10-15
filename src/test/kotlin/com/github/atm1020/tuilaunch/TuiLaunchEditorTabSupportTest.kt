package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.services.TUI_TOOL_WINDOW_ID
import com.github.atm1020.tuilaunch.toolwindow.IdeToolWindowHost
import com.github.atm1020.tuilaunch.toolwindow.TuiLaunchEditorTabSupport
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.KeyedExtensionCollector
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.wm.impl.tabInEditor.ToolWindowEditorTabSupport
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.TabbedPaneContentUI
import com.intellij.ui.content.impl.ContentManagerImpl
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import javax.swing.JPanel

private const val EDITOR_TAB_REGISTRY_KEY = "toolwindow.open.tab.in.editor"
private const val EDITOR_TAB_SUPPORT_EP = "com.intellij.toolWindowEditorTabSupport"

class TuiLaunchEditorTabSupportTest : BasePlatformTestCase() {

    private val support = TuiLaunchEditorTabSupport()

    fun testTheEditorTabFeatureIsOnAndServesTheTuiLaunchToolWindow() {
        val supports = KeyedExtensionCollector<ToolWindowEditorTabSupport, String>(EDITOR_TAB_SUPPORT_EP)
            .forKey(TUI_TOOL_WINDOW_ID)

        assertTrue(Registry.`is`(EDITOR_TAB_REGISTRY_KEY, false))
        assertTrue(supports.single() is TuiLaunchEditorTabSupport)
    }

    fun testOnlyTheTabOfALiveSessionCanMoveToTheEditor() {
        val toolWindow = ToolWindowHeadlessManagerImpl.MockToolWindow(project)
        val sessionLifetime = Disposer.newCheckedDisposable(testRootDisposable)
        IdeToolWindowHost(toolWindow).addTab(JPanel(), "claude", sessionLifetime)
        val sessionContent = toolWindow.contentManager.contents.single()
        val foreignContent = ContentFactory.getInstance().createContent(JPanel(), "Terminal", false)
        try {
            assertTrue(support.canBeMovedToEditor(sessionContent))
            assertFalse(support.canBeMovedToEditor(foreignContent))

            Disposer.dispose(sessionLifetime)

            assertFalse(support.canBeMovedToEditor(sessionContent))
        } finally {
            toolWindow.contentManager.removeContent(sessionContent, true)
            Disposer.dispose(foreignContent)
        }
    }

    fun testATabReturnedIntoASplitSectionIsReportedAsAdded() {
        val contentFactory = ContentFactory.getInstance()
        val topLevelManager = contentFactory.createContentManager(TabbedPaneContentUI(), true, project) as ContentManagerImpl
        val splitManager = contentFactory.createContentManager(TabbedPaneContentUI(), true, project) as ContentManagerImpl
        topLevelManager.addNestedManager(splitManager)
        val toolWindow = object : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
            override fun getContentManager(): ContentManager = topLevelManager
        }
        val host = IdeToolWindowHost(toolWindow)
        val added = mutableListOf<Any>()
        host.onTabAdded { added.add(it) }
        val content = contentFactory.createContent(JPanel(), "claude", false)
        try {
            splitManager.addContent(content)

            assertEquals(listOf(host.handleFor(content)), added)
        } finally {
            Disposer.dispose(toolWindow.disposable)
            Disposer.dispose(splitManager)
            Disposer.dispose(topLevelManager)
        }
    }

    fun testTheEditorTabTitleFollowsRenamesOfTheSession() {
        val content = ContentFactory.getInstance().createContent(JPanel(), "claude", false)
        try {
            val titles = runBlocking {
                support.getTabPresentationFlow(project, content)
                    .map { it.title }
                    .onEach { title -> if (title == "claude") content.displayName = "reviewer" }
                    .take(2)
                    .toList()
            }

            assertEquals(listOf("claude", "reviewer"), titles)
        } finally {
            Disposer.dispose(content)
        }
    }
}
