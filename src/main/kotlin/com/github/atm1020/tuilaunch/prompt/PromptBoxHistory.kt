package com.github.atm1020.tuilaunch.prompt

internal enum class PromptHistoryDirection { PREVIOUS, NEXT }

internal class PromptBoxHistory(private val entries: List<String>) {

    private var browsedIndex: Int? = null
    private var draft = ""

    val isBrowsing: Boolean get() = browsedIndex != null

    fun previous(current: String): String? {
        val index = browsedIndex ?: return startBrowsingAtTheNewest(current)
        if (index == 0) return null
        keepAnEditedEntryAsTheDraft(current, index)
        val olderIndex = index - 1
        browsedIndex = olderIndex
        return entries[olderIndex]
    }

    fun next(current: String): String? {
        val index = browsedIndex ?: return null
        keepAnEditedEntryAsTheDraft(current, index)
        val newerIndex = index + 1
        if (newerIndex > entries.lastIndex) {
            browsedIndex = null
            return draft
        }
        browsedIndex = newerIndex
        return entries[newerIndex]
    }

    private fun startBrowsingAtTheNewest(current: String): String? {
        val newestIndex = entries.lastIndex
        if (newestIndex < 0) return null
        draft = current
        browsedIndex = newestIndex
        return entries[newestIndex]
    }

    private fun keepAnEditedEntryAsTheDraft(current: String, index: Int) {
        if (current != entries[index]) draft = current
    }
}
