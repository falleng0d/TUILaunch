package com.github.atm1020.tuilaunch

import com.github.atm1020.tuilaunch.action.findOrCreatePromptFile
import com.github.atm1020.tuilaunch.action.findOrCreatePromptFileIn
import com.github.atm1020.tuilaunch.action.openProjectPromptFile
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files

class OpenPromptFileActionTest : BasePlatformTestCase() {

    private lateinit var projectRoot: VirtualFile

    override fun setUp() {
        super.setUp()
        projectRoot = project.guessProjectDir()!!
        assertEquals("temp", projectRoot.fileSystem.protocol)
    }

    private fun openFiles(): List<VirtualFile> = FileEditorManager.getInstance(project).openFiles.toList()

    fun testThePromptFileIsCreatedEmptyAtTheProjectRoot() {
        assertNull(projectRoot.findChild("PROMPT.md"))

        val promptFile = findOrCreatePromptFile(project)!!

        assertEquals("PROMPT.md", promptFile.name)
        assertEquals(projectRoot, promptFile.parent)
        assertEquals("", VfsUtil.loadText(promptFile))
    }

    fun testAnExistingPromptFileIsReusedWithItsContentIntact() {
        val existing = findOrCreatePromptFile(project)!!
        runWriteAction { VfsUtil.saveText(existing, "---\nfirst prompt\n---\n") }

        val reopened = findOrCreatePromptFile(project)!!

        assertEquals(existing, reopened)
        assertEquals("---\nfirst prompt\n---\n", VfsUtil.loadText(reopened))
    }

    fun testOpeningThePromptFileTwiceLeavesASingleOpenTab() {
        val firstOpen = openProjectPromptFile(project)!!
        val secondOpen = openProjectPromptFile(project)!!

        assertEquals(firstOpen, secondOpen)
        assertEquals(listOf(firstOpen), openFiles())
    }

    fun testAPromptFileWrittenOutsideTheIdeIsFoundWithItsContentIntact() {
        val diskRoot = FileUtil.createTempDirectory("tuilaunch", null, true)
        val staleRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(diskRoot)!!
        assertEmpty(staleRoot.children.toList())

        Files.writeString(diskRoot.toPath().resolve("PROMPT.md"), "written in a terminal\n")
        assertNull(staleRoot.findChild("PROMPT.md"))

        val promptFile = findOrCreatePromptFileIn(staleRoot)!!

        assertEquals("written in a terminal\n", VfsUtil.loadText(promptFile))
    }

    fun testOpeningAPromptFileThatAlreadyExistsDoesNotRewriteIt() {
        val existing = findOrCreatePromptFile(project)!!
        runWriteAction { VfsUtil.saveText(existing, "already typed\n") }

        openProjectPromptFile(project)

        assertEquals("already typed\n", VfsUtil.loadText(existing))
        assertTrue(FileEditorManager.getInstance(project).isFileOpen(existing))
    }
}
