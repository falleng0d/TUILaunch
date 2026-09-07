# TUILaunch

![Build](https://github.com/atm1020/TUILaunch/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.github.atm1020.tuilaunch.svg)](https://plugins.jetbrains.com/plugin/com.github.atm1020.tuilaunch)

[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.github.atm1020.tuilaunch.svg)](https://plugins.jetbrains.com/plugin/com.github.atm1020.tuilaunch)

<!-- Plugin description -->
**TUILaunch** launches TUI applications, such as `lazygit`, inside IntelliJ-based IDEs.
It is not limited to Git tools: you can also open your favorite terminal-based agent harness,
such as `claude`, `opencode`, `pi`, etc.
Every saved launch command gets an auto-generated IDE Action, so it can be opened from the
IDE keymap, the Tools menu, or IdeaVim's `<Action>(...)` mapping.

The plugin is inspired by Neovim's [ToggleTerm plugin](https://github.com/akinsho/toggleterm.nvim?tab=readme-ov-file#custom-terminals) custom terminal feature.
<!-- Plugin description end -->

<p align="center">
  <img src="assets/opencode_tuilaunch.png" alt="OpenCode running in the TUILaunch tool window">
</p>

## Features

Open the settings and add/edit commands:
<kbd>Settings/Preferences</kbd> > <kbd>Tools</kbd> > <kbd>TUI Launcher</kbd>

<p align="center">
  <img src="assets/tui_app_table.png" alt="TUI app table">
</p>

### Create actions for TUI apps

Add an installed application to the table and save it. TUILaunch registers an action with the format `TUILauncher.{name}`.

Saved apps are registered automatically in the built-in IDE keymap, so you can assign normal IntelliJ shortcuts to them.

<p align="center">
  <img src="assets/keymap.png" alt="Saved app actions in the keymap">
</p>

You can also call the generated action ID from IdeaVim with `<Action>(TUILauncher.{name})`.
Running the same app action again focuses the already-open tab instead of creating a duplicate.

### Dedicated TUILaunch tool window

TUI apps open in a dedicated `TUILaunch` tool window instead of the built-in Terminal tool window.
Multiple TUI apps can stay open at the same time as separate tabs, and each tab closes automatically when its command exits.

TUILaunch remembers the last tool window size used by each app and restores it when that app tab is selected again.

### Prompt box

The **Toggle Prompt Box** button in the `TUILaunch` tool window title bar, left of the button that opens `PROMPT.md`,
splits the active TUI tab and shows a prompt box next to the session: a real IntelliJ editor for writing the next
prompt. The box sits under the terminal while the tool window is docked to the left or right edge and beside it while
the window is docked to the top or the bottom, and it follows the window when you move it to another edge. It takes
30% of the tab until you drag the divider, and it starts hidden.

Two settings decide how far that state carries. While **Remember the prompt box visibility per TUI app** and
**Remember the prompt box size per TUI app** are both off, showing the box or dragging its divider applies to every
open tab and to the next tab that opens. Turn one on and each TUI app keeps its own answer, the way the tool window
size is already remembered per app.

Type in the box as in any editor: <kbd>Enter</kbd> inserts a newline, undo and redo work, and long lines are wrapped.
<kbd>Ctrl</kbd> + <kbd>Enter</kbd>, or <kbd>Cmd</kbd> + <kbd>Return</kbd> on macOS, sends the prompt to that tab's
session, and a **Send** button floating in the bottom-right corner of the box does the same. The button steps out of
the way while you type or move the cursor and comes back a second later. The shortcut only sends while the cursor is
inside a prompt box, so it keeps doing whatever the IDE binds it to everywhere else; rebind it in
<kbd>Settings/Preferences</kbd> > <kbd>Keymap</kbd> under "TUILaunch Send Prompt Box", and the box's grey hint text
names the shortcut currently assigned.

A send also appends the prompt to `PROMPT.md`, so the box doubles as a way of writing that file. The prompt goes in
behind a `---` separator, or into the empty slot at the end of the file when an earlier send left one there, and the
box is emptied for the next prompt. Sending follows the same settings as the play buttons in the `PROMPT.md` gutter:
**Send the prompt immediately instead of only typing it** decides whether the session gets <kbd>Enter</kbd> after the
text, and **Add a new prompt separator to PROMPT.md after sending** decides whether the file is left with a fresh
empty slot. Nothing is written to the file and the box is not emptied when the session refuses the text. The cursor
stays in the box after a send, like a chat input, unless **Move focus to the TUI after sending from the prompt box**
is turned on.

<kbd>Up</kbd> on the first line and <kbd>Down</kbd> on the last line walk the prompts already recorded in `PROMPT.md`
the way a shell walks its command history; anywhere else inside a longer prompt both keys just move the cursor.
<kbd>Up</kbd> shows the prompt sent last, then the one before it, and <kbd>Down</kbd> works back towards the newest
one. Whatever you were typing is kept aside and comes back when <kbd>Down</kbd> walks past the newest prompt. Editing
a prompt from the file turns that edit into the text kept aside while the prompt in the file stays as it is, so
walking away and back shows the original again. The list is re-read from `PROMPT.md` every time browsing starts, so a
prompt sent in the meantime is there, and a send ends browsing. A prompt that contains `---` lines of its own is
appended to the file verbatim and comes back as several separate history entries. A prompt arriving from the history
opens with its fenced code blocks folded, so a long block does not bury the prose around it; the fold markers in the
box's gutter expand them again.

<kbd>Escape</kbd> in the box hands the keyboard to that tab's terminal, after dropping a text selection or closing a
completion popup first, as <kbd>Escape</kbd> does in any editor.

Switching tabs with `TUILauncher.NextTuiTab`, `TUILauncher.PreviousTuiTab` or their prefix commands keeps the keyboard
where it already is: the cursor moves from one tab's box into the box of the tab it lands on, or into that tab's
session when the cursor was in the session or the tab it lands on has no box showing.

Inline completions appear in the box as grey ghost text while you type, exactly as they do in a file: <kbd>Tab</kbd>
accepts the suggestion, <kbd>Escape</kbd> dismisses it, a second <kbd>Escape</kbd> then hands the keyboard to the
terminal, and <kbd>Up</kbd> and <kbd>Down</kbd> move the cursor instead of browsing the history while a suggestion is
showing. They come from JetBrains AI Assistant unless you pick GitHub Copilot instead.

Three more actions ship with no shortcut and can be given one in <kbd>Settings/Preferences</kbd> > <kbd>Keymap</kbd>:

- `TUILauncher.FocusPromptBox` — show the prompt box of the active TUI tab and put the cursor in it, from the editor,
  from the terminal, or from the box itself. It is also available as a tmux-like prefix command, "Focus prompt box".
- `TUILauncher.PromptHistoryPrevious` / `TUILauncher.PromptHistoryNext` — the same history walk as <kbd>Up</kbd> and
  <kbd>Down</kbd>, from keys of your own. On macOS <kbd>Ctrl</kbd> + <kbd>P</kbd> and <kbd>Ctrl</kbd> + <kbd>N</kbd>
  already browse the history, because the box borrows the caret movement keys those are bound to.

### Copilot in the prompt box

A **Prompt box completions** group in the TUILaunch settings decides where the box asks for its inline completions.
**Completion source** offers **JetBrains AI Assistant**, which is the default, and **GitHub Copilot**. Pick Copilot
and the ghost text in the box comes from Copilot instead; nothing changes for any other editor, which keeps whatever
completes it today.

Copilot is reached through the standalone Copilot Language Server rather than through the JetBrains Copilot plugin, so
it needs a Copilot subscription and a Copilot client already signed in on this machine. TUILaunch reuses the
credentials that client stored under `~/.config/github-copilot` and never asks for a login of its own. Leave
**Copilot Language Server** empty and the binary is looked up in the installed GitHub Copilot plugin first and on
`PATH` (`copilot-language-server`) second, and the line under the field names what was found; fill it in to use a
binary of your own, such as one installed with `npm i -g @github/copilot-language-server`. **Check Copilot status**
asks the server who is signed in, using the path currently in the field, so a path can be tried before the settings
are applied.

**Include the PROMPT.md history as completion context** decides what Copilot sees. With it on, which is the default,
the prompts already recorded in `PROMPT.md` are sent ahead of the draft, separated by `---` lines, so a suggestion can
pick up the wording and the subject of earlier prompts; the newest prompts win when the history grows past 48 000
characters. With it off only the draft is sent. Either way the text is a Markdown document of TUILaunch's own and no
file of the project is opened for it.

While Copilot is not ready — no binary found, no signed-in client, or a server that failed to start — the box quietly
falls back to JetBrains AI Assistant, and a balloon names it once per reason, quoting the status the
server answered rather than guessing at it. A sign-in that happens after the server started is picked up from the
server's own status notifications, so the box begins completing without a restart. The server is started when a
prompt box first becomes visible with Copilot selected, so the first suggestion of a session takes a second or two
longer than the ones after it.

### Reopen TUI tabs when the project is opened

Turn on **Reopen TUI tabs when the project is opened** in the TUILaunch settings and the plugin remembers, per project,
which TUI tabs were open: the apps, their left-to-right order, their custom names, and which tab was selected.
The next time you open that project, the tabs come back the first time the tool window is shown.

Reopening means relaunching. Each tab starts a brand new session, so processes and scrollback are gone; only the shape
of the tab strip comes back. An app that has since been deleted from the settings table is skipped, and an app whose
command was edited starts the new command. The setting is off by default and applies to every project.

The tab list lives in the project's `.idea/workspace.xml`, so it is per developer rather than shared through VCS.
While the setting is off nothing is recorded and nothing is reopened; the list from before you turned it off stays on
disk and is used again if you turn the setting back on before opening any TUI tab.

### Focus and tab actions

TUILaunch registers global actions that can be bound in the IDE keymap or called from IdeaVim:

<p align="center">
  <img src="assets/keymap_global_actions.png" alt="Global TUILaunch actions in the keymap">
</p>

- `TUILauncher.FocusTui` — focus the active TUI session.
- `TUILauncher.FocusEditor` — return focus to the editor.
- `TUILauncher.ToggleFocus` — switch focus between the editor and the active TUI session.
- `TUILauncher.ToggleToolWindow` — show or hide the `TUILaunch` tool window.
- `TUILauncher.ToggleToolWindowAndFocus` — show and focus the tool window, or hide it.
- `TUILauncher.CloseActiveTui` — close the selected TUI tab.
- `TUILauncher.NextTuiTab` / `TUILauncher.PreviousTuiTab` — switch TUI tabs and focus the terminal.
- `TUILauncher.NextTuiTabWithoutFocus` / `TUILauncher.PreviousTuiTabWithoutFocus` — switch TUI tabs without moving keyboard focus into the terminal.

### IdeaVim + tmux-like workflow

TUILaunch works well with both IntelliJ keymaps and IdeaVim. Standard IntelliJ keymap shortcuts continue to work while focus is inside a TUI app because the IDE handles those shortcuts first. This is enough if you rely mainly on IntelliJ keybindings.

If you mainly use IdeaVim, you can combine IdeaVim mappings in the editor with TUILaunch's tmux-like prefix shortcuts inside TUI sessions. For example, configure <kbd>Ctrl</kbd> + <kbd>Space</kbd> as the TUILaunch prefix key, then reuse familiar follow-up keys from your IdeaVim mappings.

```vim
" Launch a saved app. The action ID is TUILauncher.{name}.
nmap <Space>gg <Action>(TUILauncher.lazygit)

" Toggle focus between the editor and the active TUI tab.
nmap <Space>tt <Action>(TUILauncher.ToggleFocus)

" Focus the active TUI tab, then return to the editor.
nmap <Space>tj <Action>(TUILauncher.FocusTui)
nmap <Space>tk <Action>(TUILauncher.FocusEditor)

" Make Ctrl+Space available to IdeaVim in normal mode.
sethandler <C-Space> n:vim

" Ctrl+Space prefix-style mappings for TUILaunch.
nmap <C-Space><Space> <Action>(TUILauncher.ToggleToolWindow)
nmap <C-Space>e <Action>(TUILauncher.FocusTui)
nmap <C-Space>n <Action>(TUILauncher.NextTuiTab)
nmap <C-Space>p <Action>(TUILauncher.PreviousTuiTab)
```

Then register matching shortcuts in the TUILaunch tmux-like keybindings table, such as <kbd>Ctrl</kbd> + <kbd>Space</kbd>, then <kbd>G</kbd> to launch or focus `lazygit`.

### Tmux-like prefix keybindings

When focus is inside a TUI session, you can configure a prefix key that runs TUILaunch actions without sending the keys to the TUI app.

In the TUILaunch settings you can:

- Enable or disable tmux-like keybindings.
- Choose the prefix modifier: <kbd>Ctrl</kbd> or <kbd>Alt</kbd>.
- Record the prefix key.
- Assign prefix commands for focusing the editor, focusing the prompt box, closing the active TUI, switching tabs, toggling the tool window, and launching saved apps.
- Clear assigned shortcuts with <kbd>Delete</kbd> or <kbd>Backspace</kbd>.

<p align="center">
  <img src="assets/tmux_keybindings.png" alt="Tmux-like keybindings">
</p>

Defined TUI apps automatically appear in the prefix-key table as launch actions, so they can be called from inside an active TUI session too.

> **Note:** If you update the tmux-like keybindings while TUI tabs are already open, restart those active TUILaunch tabs so the new keybindings take effect inside them.

<p align="center">
  <img src="assets/tmux_app_shortcuts.png" alt="Per-app tmux-like shortcuts">
</p>

Example workflow after configuring <kbd>Ctrl</kbd> + <kbd>Space</kbd> as the prefix and <kbd>E</kbd> as “Focus editor”:

1. Focus a TUI tab.
2. Press <kbd>Ctrl</kbd> + <kbd>Space</kbd>.
3. Press <kbd>E</kbd>.
4. Focus returns to the editor, and the key sequence is not sent to the TUI app.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "TUILaunch"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/atm1020/TUILaunch/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Implementation notes

Platform behaviour this plugin depends on, collected so it does not have to be rediscovered:

- `Content.setDisplayName` fires the property change the tab UI repaints on; `setTabName` does not.
- `ToolWindowFactory.init(ToolWindow)` runs before `createToolWindowContent` and before any content exists, which is the only point where `ToolWindowEx.setTabActions` can install the tab bar's "+" button.
- A tab only gets an X and enabled "Close Tab"/"Close Other Tabs"/"Close All Tabs" actions when `Content.isCloseable` is true *and* the `<toolWindow>` element declares `canCloseContents="true"`; either one alone leaves them disabled.
- Once content is closeable the platform removes tabs without calling back into plugin code, so a `ContentManagerListener.contentRemoved` hook is the only way to learn that a session is gone.
- Register `ContentManager` listeners on the tool window's disposable, not on a tab's, or they are unhooked when the first session closes.
- `ContentManagerImpl` drops a tab from the selection *before* it fires `contentRemoved` and only picks the neighbouring tab *after*, so `contentRemoved` cannot tell whether the closed tab was the active one; `contentRemoveQuery` fires while the tab is still selected and is the only place to read that and the tool window size.
- The `ContentManager`'s `contents` array is the single source of truth for tab order and the current index; deriving order from an internal map desyncs during a close (the removal lands an event-queue turn later) and after the user drags a tab.
- Resolve the active tab *inside* the deferred block when moving the selection: reading it up front makes a burst of key presses all measure from the same pre-burst tab and advance a single step in total.
- `ToolWindowEx.stretchWidth`/`stretchHeight` are relative, so re-applying a size to a docked tool window whose selection change is already restoring it doubles the stretch and overshoots.
- `stretchWidth` and `stretchHeight` both call the same private `ToolWindowPane.stretch`, which picks the axis from the tool window's anchor and ignores which of the two was called, so a docked window can only ever be resized along one axis: height for `TOP`/`BOTTOM`, width for `LEFT`/`RIGHT`.
- The `<toolWindow>` `anchor` attribute reaches a `WindowInfoImpl` only through `DesktopLayout.create`, which `ToolWindowManagerImpl.registerToolWindow` skips whenever workspace.xml already holds a `window_info` entry for the id; `RestoreDefaultLayoutAction` keeps the recorded anchor too, so changing the declared anchor is visible only in a project that has never had the window registered.
- `ToolWindowPane.stretch` clamps only against `ide.mainSplitter.min.size` (30 scaled px), so replaying a width recorded from a bottom-docked window onto a side-docked one squeezes the editor down to that minimum; a recorded size is only meaningful for the axis it was recorded on.
- Programmatic resizing emits the same `componentResized` events as a user drag, so size recording has to be suppressed while a saved size is applied and re-armed on the next event-queue pass.
- `Disposer.dispose` on an already-disposed object is a no-op, which is what lets a plugin-initiated dispose race the platform's own disposal of the `Content`.
- `JBTerminalWidget.asJediTermWidget` only unwraps the classic Gen-1 widget; that widget's `terminalStarter` is the only write path to the child process.
- JediTerm's `TerminalKeyEncoder` has no `VK_ESCAPE` entry, so `TerminalStarter.getCode(27, 0)` returns null and callers must fall back to sending the character themselves, exactly as `TerminalPanel` does.
- Sending with `userInput = true` also scrolls to the cursor and clears the selection, which is what makes a forwarded key indistinguishable from real typing.
- The platform's `TerminalEscapeKeyListener` moves focus to the editor on bare Escape in any tool window other than the bundled "Terminal", so a custom terminal window has to intercept Escape before it.
- That listener is a field of `JBTerminalPanel` driven from that panel's own `handleKeyEvent`, so it only ever sees keys delivered to a terminal panel; a sibling component in the same tool window tab can bind Escape without it interfering.
- IntelliJ delivers each `KEY_PRESSED` to a global `KeyEventDispatcher` twice; de-duplicate on (timestamp, key code) or a forwarded Escape reaches the child process twice.
- Consuming a `KEY_PRESSED` does not suppress the matching `KEY_TYPED`, which has to be swallowed separately or its character still lands in the terminal.
- `NextTab`/`PreviousTab` keystrokes differ across the default, macOS and system-shortcut keymaps, so resolve them from `ActionManager` at event time rather than hardcoding them.
- A `KeyEventDispatcher` sees one keystroke at a time and cannot arbitrate a chord, so two-stroke shortcuts must be ignored rather than matched on their first stroke.
- `ToolWindowTabRenameActionBase` hardcodes `Balloon.Position.above`, which is wrong for a bottom-anchored tool window.
- `JTable.getSelectedRow` returns -1 when nothing is selected, and `convertRowIndexToModel` passes that -1 straight through when no row sorter is installed, so an empty selection has to be rejected before the index is used.
- `ContentLayout.shouldShowId` compares the `ToolWindowContentUi.HIDE_ID_LABEL` client property against the *string* `"true"`, so a `Boolean` value leaves the "TUILaunch:" prefix on the tab bar; the property is read from the first ancestor of the content component that has it set, which makes `toolWindow.component` the place to put it.
- `TabContentLayout` derives the visible tab strip solely from `contentAdded`/`contentRemoved`, so a tab reorder the user can see has necessarily fired both events on the `ContentManager`; there is no separate reorder path to listen for.
- A tab drag is a temporary removal and re-add of the same `Content` with `Content.TEMPORARY_REMOVED_KEY` set, so every removal hook has to check that key before treating it as a close; the key is still set when `contentRemoved` and the matching `contentAdded` fire, and is cleared only once the drag ends.
- The platform skips `contentRemoveQuery` for a temporary removal, so only `contentRemoved` can be relied on to observe a drag.
- `ToolWindowInnerDragHelper.canStartDragging` allows a tab reorder when `Registry.is("ide.allow.tool.window.tabs.reorder")` is on, which is the shipped default, so tab dragging reproduces in the sandbox `runIde` instance; the older `ToolWindowContentUi.ALLOW_DND_FOR_TABS` client property is no longer part of that gate.
- Dropping a tool window tab into the editor ends with the platform calling `Disposer.dispose` on the `Content`, which takes any disposable registered under it with it, so a session can disappear without any close event of ours running.
- Whether a tool window keeps its content visible during indexing is decided only by the factory instance: `ToolWindowSetInitializer.beanToTask` sets `RegisterToolWindowTaskData.canWorkInDumbMode` from `DumbService.isDumbAware(factory)`, which reaches `PossiblyDumbAware.isDumbAware`'s `this is DumbAware` default, and `ToolWindowImpl` uses that flag to decide whether to wrap the content in `DumbService.wrapGently`'s "indexes are being rebuilt" `DumbUnawareHider`. There is no `<toolWindow>` attribute for it, so the `DumbAware` marker on the factory is the only lever.
- `LineMarkerProviders.allForLanguageOrAny` walks `Language.getBaseLanguage()` upward and then appends `Language.ANY`, and `MarkdownLanguage` and `PlainTextLanguage` both extend `Language` directly, so a `lineMarkerProvider` registered on `TEXT` never fires for a `.md` file; a gutter icon there has to be added to the editor's `MarkupModel` from an `editorFactoryListener` instead.
- Highlighters put straight on the `MarkupModel` bypass the highlighting daemon, so nothing re-runs after an edit; a `DocumentListener` has to remove and re-add the plugin's own highlighters, and `removeAllHighlighters` would take other plugins' markers with it.
- `Editor.getVirtualFile()` defaults to returning null and `EditorImpl` answers it from a field rather than from the document, and that field is not populated for every editor by the time `EditorFactoryListener.editorCreated` runs, so a listener that filters by file name has to ask `FileDocumentManager.getFile(editor.document)` instead.
- `Editor` is not a `Disposable`, so a listener scoped to one editor needs its own `Disposer.newDisposable` handed to `EditorUtil.disposeWithEditor`; left unparented that disposable becomes a root in the disposer tree and outlives the editor.
- JediTerm wraps a paste in `ESC[200~`/`ESC[201~` inside the private `TerminalPanel.pasteFromClipboard`, not in `TerminalStarter.sendString`, and bracketed-paste mode lives in a private field with no getter, so callers that send multi-line text have to build the wrapper themselves; JediTerm also does not strip an `ESC[201~` already present in the text, which would end the paste early.
- `ToolWindowImpl.setTitleActions` calls `ensureContentManagerInitialized` and then dereferences its `decorator` without a null check, so a title bar button has to be installed from `createToolWindowContent`; `init` is too early and is only usable for `setTabActions`.
- `MarkupModel.addLineHighlighter` produces a zero-length range marker anchored at the line's *first non-space* character, not at its start offset, and a deletion that strictly contains that offset invalidates the marker and silently drops the gutter icon; any edit-skipping optimisation has to re-check `RangeHighlighter.isValid` after a deletion.
- `ToolWindowImpl.getContentManager` runs `createContentIfNeeded`, which invokes `ToolWindowFactory.createToolWindowContent` synchronously, so the first `contentManager` touch from plugin code re-enters the factory and anything the factory starts has to be pushed onto the event queue first.
- `ContentManagerImpl.removeContent` takes the content out of `contents` before firing `contentRemoved` and moves the selection only afterwards, so the strip order read in `contentRemoved` is already correct while the selection is not.
- `ContentManagerImpl` is a `Disposable.Parent` whose `dispose` clears `contents` and its listener list without firing anything, and `ContentImpl.dispose` never calls back into its manager, so tearing a tool window down at project close fires no `contentRemoved` at all; the only bulk path that does is `removeAllContents`, a plain loop over `removeContent` whose sole caller is `ToolWindowImpl.createContentIfNeeded`.
- `ContentManagerImpl.setSelectedContent` fires nothing when the content is already selected, which is why the very first tab added to an empty strip produces a `contentAdded` and an auto-selection but no further selection event for plugin code to react to.
- `$WORKSPACE_FILE$` resolves to `.idea/workspace.xml`, and only that name is covered by the standard JetBrains `.gitignore`; `$PRODUCT_WORKSPACE_FILE$` resolves to `.idea/product-workspace.xml` only under `isUnitTestMode` and otherwise to `<IDE config dir>/workspace/<projectWorkspaceId>.xml`, which the platform uses for open editors and the tool window layout.
- `MergingUpdateQueue.flushAllQueues()` only does anything when the `intellij.MergingUpdateQueue.enable.global.flusher` system property is set before the class loads, and even a zero merge span still hands the update to the alarm thread before it reaches the event queue, so a single `dispatchAllInvocationEventsInIdeEventQueue` races it; a test has to poll the queue's own `isEmpty` while pumping the event queue.
- Inside `ESC[200~`/`ESC[201~` a carriage return is paste content rather than a submit, so submitting text sent as a paste needs a separate key event after the paste ends; appending the return to the payload cannot work because the payload is trimmed of trailing returns before the markers go on.
- `Splitter.doLayout` treats a null or invisible second component as absent and hides the divider, so hiding that child gives the first one the whole area while its component, and anything living in it, stays in the hierarchy.
- `JBSplitter.setSplitterProportionKey` stores the proportion in the application-level `PropertiesComponent`, so a split size that belongs to a project or to one TUI app has to be persisted by the plugin itself.
- `EditorFactoryImpl.createEditor` installs a file-type highlighter only in the overloads that take a `VirtualFile` or a `FileType`, so an editor built from a bare `Document` shows unhighlighted text.
- `UndoRedoAction` reads only `PlatformCoreDataKeys.FILE_EDITOR` while `EditorComponentImpl` publishes just `EDITOR`, `CARET` and `EDITOR_VIRTUAL_SPACE`, so the container around a standalone `EditorFactory` editor has to publish `TextEditorProvider.getInstance().getTextEditor(editor)` or undo does nothing in it.
- `FileTypeManager.getFileTypeByFileName` answers `UnknownFileType` for a `.md` name when the Markdown plugin is not loaded, and `FileDocumentManager.getDocument` returns null for a binary file type, so a `LightVirtualFile` meant to be edited needs a plain-text fallback whenever the resolved type is binary.
- `LightIdeaTestFixtureImpl.tearDown` runs `LightPlatformTestCase.checkEditorsReleased`, which fails a test for every editor still alive, and a test host never disposes a tab's disposable, so an editor that belongs to a tool window tab is only created once that tab actually shows it.
- The three-argument `ToolWindowManagerListener.stateChanged(ToolWindowManager, ToolWindow, ToolWindowManagerEventType)` is `@ApiStatus.Internal`, so a docking edge change has to be observed through the two-argument overload and the anchor re-read from the tool window.
- `IdeKeyEventDispatcher` collects the actions registered on the focused component chain with `AnAction.registerCustomShortcutSet` before the keymap ones and runs the first *enabled* one, and a disabled action falls through to the next, which is how `Console.Execute.Multiline` shares Ctrl+Enter with `EditorSplitLine`: an action that is enabled only inside its own component can take a keystroke the IDE already uses elsewhere.
- `AnAction.setShortcutSet` logs a `PluginException` warning for an action registered in `ActionManager` unless the new set is the very same object, so re-registering a global action's own `shortcutSet` on a component is the safe way to give it component-chain precedence, and `ActionManagerImpl.registerAction` replaces whatever set the instance had with a keymap-backed `ProxyShortcutSet`.
- `IdeKeyEventDispatcher.isControlEnterOnDialog` short-circuits Ctrl+Enter inside a `DialogWrapper` so the dialog's OK button gets it, which means a component-registered Ctrl+Enter action never fires in a dialog.
- A keymap that declares its own `<keyboard-shortcut>` for an action id does not inherit the parent keymap's shortcuts for that id, so binding `meta ENTER` in "Mac OS X 10.5+" leaves the macOS keymaps with only that keystroke while `$default` keeps `control ENTER`.
- `SingleAlarm` and `Alarm` are `@ApiStatus.Obsolete` in 262; the supported debounce is `MergingUpdateQueue(...).setRestartTimerOnAdd(true)`, which restarts the merge window on every queued update, and it only collapses updates immediately in unit tests when `usePassThroughInUnitTestMode()` is called.
- `BasePlatformTestCase` does not load the plugin's own `plugin.xml`, so nothing declared there exists in a test: an action has to be registered through `ActionManager.registerAction` and its default keystroke added to the active keymap by the test itself.
- `ConsoleHistoryController` shares one console's Up and Down between history browsing and caret movement by registering per-console actions on the console component carrying `ActionManager.getActionOrStub("EditorUp"/"EditorDown").shortcutSet`, and enabling them only when the keystroke came from one of those keys *and* the caret sits on the first or last line; every other Up and Down falls through to the keymap's own caret movement.
- `Console.History.Previous` and `Console.History.Next` ship with no default keystroke at all, so the arrow keys reach them only through the shortcut sets borrowed from `EditorUp` and `EditorDown`, which is what keeps them out of every other editor.
- The macOS keymaps bind `control P` and `control N` to `EditorUp` and `EditorDown` on top of the arrow keys, so borrowing those shortcut sets brings the Emacs-style pair along with them.
- `JLayeredPane`'s layer constants are `Integer`, and Kotlin unboxes them, so `add(component, JLayeredPane.PALETTE_LAYER)` binds to `Container.add(Component, int index)` instead of the constraint overload: the layer is never assigned and the component lands behind everything already in the default layer. `setLayer(component, layer)` followed by a plain `add(component)` is the unambiguous way to place it.
- `FileDocumentManager.saveDocument` lets `TrailingSpacesStripper` edit the document from `beforeDocumentSaving`, and with the IDE's "Remove trailing blank lines at end of saved files" option on it deletes every blank line at the end, so text that deliberately ends in one loses it in the document as well as on disk; `saveDocumentAsIs` turns the stripper off for that file around the save.
- Inline completion needs both halves of a real file editor: `InlineCompletionEditorListener` installs the handler only for `EditorKind.MAIN_EDITOR` (outside unit tests), and `InlineCompletionEditorType.get` calls it `MAIN_EDITOR` only when `EditorUtil.isRealFileEditor` is true, which means `TextEditorProvider.getTextEditor(editor)` has to be a `TextEditorImpl`; `EditorFactory.createEditor` gives a cached wrapper instead, so the editor has to come from `TextEditorProvider.createEditor(project, file)` and be disposed with `Disposer.dispose(textEditor)` rather than `EditorFactory.releaseEditor`. Off the EDT the framework only reads the PSI file from `PsiDocumentManager.getCachedPsiFile`, so the owner has to resolve it once and hold it.
- `TextEditorProvider.createEditor` wraps the editor in a `TextEditorComponent` that turns the error stripe on and puts the editor component into its own `GridBagLayout`; a container that lays the editor out itself adds `editor.component` and switches the error stripe back off. Per-editor sticky lines cannot be turned off at all: `EditorSettings` exposes `areStickyLinesShown` with no setter, and the flag follows the global option plus whether the language has a `BreadcrumbsProvider`, which Markdown does.
- GitHub Copilot's provider sends only `{uri, version}` and its agent learns a buffer from the `textDocument/didOpen` that `LSPEditorListener.fileOpenedSync` sends for a `TextEditor` opened through `FileEditorManager`, so a tool-window editor over a light file never reaches it however real the editor looks.
- `EscapeInlineCompletionHandler` dismisses ghost text through the `EditorEscape` action, which the keymap reaches only after the actions registered on the focused component chain, so any Escape action of ours on an editor panel has to report itself disabled while a completion is showing or the first press skips the dismissal.
- `copilot-language-server` speaks LSP only with the `--stdio` flag, which is required rather than a default; the native binary can be probed with `--version` but crashes on `--help`, so availability checks must use `--version`.
- The Copilot server refuses to work until `initialize` carries `initializationOptions.editorInfo` and `initializationOptions.editorPluginInfo`, and it derives the identity of its credential store entry from `editorPluginInfo.name`: the store is the shared `~/.config/github-copilot` database, and a new editor name adopts a token that any other Copilot client on the machine already holds, while signing out only affects that one name.
- `checkStatus` with `{"options":{"localChecksOnly":true}}` answers in about a millisecond with `MaybeOK` plus a user name as soon as any token is cached on the machine, while `localChecksOnly:false` costs a round trip and is the only form that returns the verified `OK`; the answers that mean a usable session are `OK` and `AlreadySignedIn`, and the rest of the set is `MaybeOK`, `NotSignedIn`, `NotAuthorized` and `FailedToGetToken`.
- `didChangeStatus` carries only `{kind, busy, message}`, with the `category: "auth"` detail and the status itself on `didChangeStatus/v2`; `checkStatus` emits no status change of its own, but every inline completion toggles `busy` around its request, so a `Normal` change is no more than a hint to run `checkStatus` again.
- Every capability or `copilotCapabilities` entry declared at `initialize` obliges the client to answer a matching server request, and the server blocks its own startup on `workspace/configuration` (an array of one object per entry in `params.items`), so declaring nothing but `workspace.configuration`, `textDocument.inlineCompletion` and `window.showDocument` keeps the handler table down to what is actually implemented.
- `-32802` (superseded), `-32800` (cancelled), `-32801` (`Document Version Mismatch`) and `1000` (not authenticated) are ordinary control flow from the Copilot server rather than faults: it auto-cancels the previous inline completion for a document, and it rejects a request whose `textDocument.version` does not match the last `didChange`, so the text, the version and the position have to be snapshotted together.
- Gson's default configuration drops a null-valued field, which would strip the `"result": null` that a JSON-RPC response to `window/showMessageRequest` or `shutdown` must carry, so the serializer needs `serializeNulls()`.
- `XmlSerializer` leaves a field at its default when the stored value names no constant of that enum, so persisted enum settings survive a renamed or removed constant without any fallback of their own.
- A settings dialog is modal, so a result computed off the EDT reaches its label only through `invokeLater(runnable, ModalityState.any())`; with the default modality the runnable waits until the dialog is closed.
- Exactly one inline completion provider serves an event: `InlineCompletionHandler.getProvider` takes the first provider in loading order whose `isEnabled` is true, and AI Assistant's own cloud provider registers as `order="first, before ...Copilot..."`, so a provider that wants to answer before it needs `order="first"` too. Returning false is then the whole fallback mechanism, because the next provider in order gets the event.
- The `inline.completion.provider` extension point is declared in `EditorExtensionPoints.xml`, which reaches a plugin only through `com.intellij.modules.lang`; `com.intellij.modules.platform` alone does not bring it.
- `isEnabled` runs on the EDT and its exceptions are logged and read as false, so a provider that depends on an out-of-process backend has to answer false while the backend is still starting rather than wait for it.
- Copilot's `textDocument/inlineCompletion` answers with the whole line as `insertText` and a `range` that starts at character 0, which means it replaces the current line: the ghost text is `insertText` minus the text already on the line inside the range, an item whose `insertText` does not start with that text has to be skipped, and a range that ends past the cursor cannot be rendered as ghost text at all.
- Copilot positions and `acceptedLength` count UTF-16 code units, which is exactly what IntelliJ `Document` offsets and Kotlin `String.length` already are, so no conversion is needed even for astral characters.
- `InlineCompletionHandler.insert` asserts write access, so a test that accepts ghost text has to call it inside a write action even though the platform's own `InsertInlineCompletionAction` looks like it does not.
- `EditorFactoryListener.editorCreated` runs before the owner of a light editor can attach its own user data, but `editorReleased` still sees that data, which makes the release hook the only listener able to recognise a plugin's own editor without holding a reference to it.
- Coroutine cancellation cannot interrupt a thread parked in `InputStream.read` on a child process's stdout, so a scope that owns such a read loop never completes and its `Job.invokeOnCompletion` never runs, which stalls IDE exit for the shutdown timeout; teardown has to start when cancellation starts instead, from a child coroutine whose `finally` after `awaitCancellation` closes the stream and destroys the process.
- `LineStatusTrackerManager` is a project service and reports every `EditorKind.MAIN_EDITOR` editor still alive when it is disposed, while a tool window's `ContentManager` is disposed after the project services, so an editor owned by a tool window tab has to be released from `ProjectCloseListener.projectClosing` rather than from a disposable parented to the `Content`.
- `CodeFoldingManager.updateFoldRegions(Editor)` is annotated `@RequiresReadLock` only, but it finishes by running a batch folding operation, so it has to be called on the EDT; it also produces nothing while `EditorSettings.isAutoCodeFoldingEnabled` is off, and commits the document itself, so a caller that has just changed the text does not have to.
- A fold region is treated as a fenced code block only when its first line opens a fence and its last line closes it, because `MarkdownFoldingBuilder` folds paragraphs, list items, tables, block quotes, link destinations and heading sections as well, and it also folds an unclosed fence from its opener to the end of the file; leading block-quote markers are stripped from both lines first, so a fence inside a quote is still recognised.
- `FoldingModel.runBatchFoldingOperation(Runnable)` is the variant that is allowed to move the caret out of a region it collapses; `runBatchFoldingOperationDoNotCollapseCaret` leaves a region holding the caret expanded instead.
- Fold regions are built whether or not `EditorSettings.isFoldingOutlineShown` is on, so an editor that collapses regions programmatically still has to turn the outline on for the user to be able to expand them.
- `BaseOSProcessHandler.startNotify` attaches a `BaseOutputReader` that consumes the child's stdout and decodes it into lines, which destroys `Content-Length` framing, and `ProcessHandler.destroyProcess` queues its work behind `startNotify`, so a handler for a stdio LSP server has to call `startNotify` and override `createProcessOutReader` to hand the base class an empty reader while the JSON-RPC loop reads `handler.process.inputStream` itself.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
