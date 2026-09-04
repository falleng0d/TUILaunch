<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# TUILaunch Changelog

## [Unreleased]

### Added

- Every TUI tab can now show a prompt box next to its session: a real editor to write the next prompt
  in. The new "Toggle Prompt Box" button in the TUILaunch tool window title bar, left of the button
  that opens `PROMPT.md`, splits the active tab and shows it. The box sits under the terminal while the
  tool window is docked to the left or right edge and next to it while the window is docked to the top
  or the bottom, follows the window when it is moved to another edge, and takes 30% of the tab until
  its divider is dragged. It starts hidden, and the button is how it appears.
- Two settings decide how much the prompt box remembers: "Remember the prompt box visibility per TUI
  app" and "Remember the prompt box size per TUI app". While both are off, showing the box or dragging
  its divider applies to every open tab and to the next tab that opens. Turn one on and each TUI app
  keeps its own answer, the way the tool window size is already remembered per app.
- Ctrl+Enter, or Cmd+Return on macOS, sends what is typed in the prompt box to that tab's TUI session
  and appends it to `PROMPT.md` as a new prompt, so the box doubles as a way of writing the file. The
  box is then emptied for the next prompt. Enter still inserts a newline, and the shortcut only sends
  while the cursor is in a prompt box, so it keeps doing whatever the IDE binds it to everywhere else.
  Rebind it in Settings | Keymap under "TUILaunch Send Prompt Box"; the box's own grey hint text names
  the shortcut currently assigned. A "Send" button floating in the bottom-right corner of the box does
  the same thing, and steps out of the way while you type or move the cursor.
- Sending from the box follows the existing "Send the prompt immediately instead of only typing it"
  and "Add a new prompt separator to PROMPT.md after sending" settings. A new setting, "Move focus to
  the TUI after sending from the prompt box", hands the keyboard to the session after each send; while
  it is off, which is the default, the cursor stays in the box like a chat input. Nothing is written to
  `PROMPT.md` and the box is not emptied when the session refuses the text.
- Up on the first line of the prompt box and Down on the last line walk the prompts already recorded
  in `PROMPT.md` the way a shell walks its command history: Up shows the prompt sent last, then the one
  before it, and Down works back towards the newest one. Anywhere else inside a longer prompt both keys
  still just move the cursor. Whatever was being typed is kept aside and comes back when Down walks
  past the newest prompt, and editing a prompt from the file turns that edit into the text kept aside
  while the prompt in the file stays as it is. The list is re-read from `PROMPT.md` every time browsing
  starts, so a prompt sent in the meantime is there, and a send ends browsing. On macOS Ctrl+P and
  Ctrl+N do the same; other keys can be assigned in Settings | Keymap under "TUILaunch Previous Prompt
  in History" and "TUILaunch Next Prompt in History".
- Escape in the prompt box hands the keyboard to that tab's terminal, after dropping a text selection
  or closing a completion popup first, as it does in any editor. A new action, "TUILaunch Focus Prompt
  Box", goes the other way: it shows the box of the active TUI tab and puts the cursor in it, from the
  editor, from the terminal, or from the box itself. It ships with no shortcut, so bind one in
  Settings | Keymap, or record a key for the new "Focus prompt box" row in the tmux-like keybindings
  table to reach the box from inside a running session.
- The prompt box is now a normal text editor as far as the rest of the IDE is concerned, so inline
  completions from JetBrains AI Assistant appear in it as grey ghost text while you type, exactly as
  they do in a file. Tab accepts the suggestion and Escape dismisses it; a second Escape then hands the
  keyboard to the terminal, and Up and Down move the cursor instead of browsing the history while a
  suggestion is showing. GitHub Copilot cannot complete here, because its agent only ever learns about
  files opened as editor tabs.
- A "Prompt box completions" group in the TUILaunch settings holds the settings to choose GitHub
  Copilot as the prompt box completion source instead of JetBrains AI Assistant, which stays the
  default. Copilot is reached through the standalone Copilot Language Server, so it needs a Copilot
  subscription and a Copilot client already signed in on this machine. "Copilot Language Server" takes
  a binary of your own; left empty, the one that ships with the GitHub Copilot plugin or the one on
  `PATH` is used, and the line under the field names what was found. "Include the PROMPT.md history as
  completion context" decides whether the prompts already in the file travel with the draft, and
  "Check Copilot status" reports who is signed in, for the path currently in the field.

## [0.7.0] - 2026-09-03

### Added

- After the last prompt in `PROMPT.md` is sent, the file gets a fresh `---` separator at the end and
  the cursor moves into the empty space below it, so the next prompt can be typed right away. Sending
  again when the file already ends in an empty slot does not add a second separator, and sending an
  earlier prompt leaves the file and the cursor where they are. Turn off "Add a new prompt separator
  to PROMPT.md after sending" in Settings to leave the file untouched.
- Sending a prompt now leaves the cursor in `PROMPT.md` instead of moving it into the TUI session, so
  the next prompt can be typed straight away. The tool window still opens and shows the session that
  received the prompt. Turn off "Focus PROMPT.md again after sending" in Settings to have the session
  take the keyboard again after each send.
- A prompt can now be sent from the keyboard: put the cursor anywhere inside a prompt in `PROMPT.md`
  and run "TUILaunch Send Prompt Block", which does exactly what clicking that prompt's play button
  does. It ships with no shortcut of its own, so bind one in Settings | Keymap by searching for
  "TUILaunch Send Prompt Block". The action stays greyed out when the cursor is not inside a prompt,
  such as on a `---` line or in the empty space at the end of the file, so a stray press cannot
  resend the prompt that was just sent.

### Changed

- Clicking a prompt's play button in `PROMPT.md` now submits the prompt instead of only typing it into
  the session, so the TUI starts working on it straight away. This changes what 0.5.0 did, where the
  prompt was typed "without submitting it" and waited for you to press Enter. Turn off "Send the
  prompt immediately instead of only typing it" in Settings to get the old behaviour back.

## [0.6.0] - 2026-08-31

### Added

- TUI tabs now come back when a project is reopened. The tabs that were open are relaunched in the
  same order, keeping their renamed titles and the tab that was selected. Tabs are remembered per
  project, and an app that has since been removed from the settings is skipped. Turn off "Restore
  open tabs" in Settings to go back to starting with an empty tool window.

### Changed

- The TUILaunch tool window now opens on the right edge of the IDE by default. Projects that have
  already used the plugin keep the position they are in; drag the window to the right edge once to
  move it.

## [0.5.0] - 2026-08-31

### Added

- A `PROMPT.md` file now shows a play button in the editor gutter next to each prompt, and clicking it
  types that prompt into the active TUI session without submitting it, so it can be reviewed and sent
  with Enter. Prompts are separated by lines containing only `---`; the prompt currently being written
  gets its play button straight away, and a file with no `---` at all counts as a single prompt.
- A button in the TUILaunch tool window title bar, next to the gear, opens the project's `PROMPT.md`
  and puts the cursor in it, creating the file at the project root when it is not there yet and
  pinning its editor tab.

### Changed

- Prompt gutter icons are much cheaper to keep up to date, so editing a long `PROMPT.md` stays
  responsive. Typing that cannot move a prompt boundary no longer re-reads the file at all, a file
  whose code fences are left open no longer takes seconds to scan, and adding a line above the
  prompts now moves the existing icons instead of rebuilding every one of them.

### Fixed

- TUI sessions stay visible and usable while the IDE rebuilds its indexes, instead of being
  replaced by the "indexes are being rebuilt" panel. The TUILaunch actions and shortcuts also
  keep working during indexing.

## [0.4.0] - 2026-08-27

### Added

- The TUILaunch tool window now has an icon on the tool window stripe.

### Changed

- TUILaunch now requires IntelliJ IDEA 2026.2 or newer. Older IDEs, including the whole 2025.x
  line, are no longer supported and will not offer the plugin as an update.
- The "TUILaunch:" label no longer takes space ahead of the session tabs, leaving the full width of
  the tab bar for the tabs themselves.

### Fixed

- Dragging a tab to a new position no longer breaks "Select Next/Previous Tab". The dragged tab used
  to become unreachable, and the shortcuts walked the remaining tabs in the wrong order. Dragging a
  tab also no longer unhooks that session's prefix key.

## [0.3.1] - 2026-08-16

### Fixed

- Closing a session with the tab's X button or the tab context menu now remembers the tool window
  size for that app, the same way the close shortcut already did.
- Closing the last session with the mouse now returns focus to the editor when the session was
  started from the editor. Closing a session while others remain leaves focus on the tab the tool
  window selects next.
- The Reset button in Settings now restores every field to its saved value. It previously did
  nothing at all.
- The Settings panel no longer looks edited the moment it opens. Apply stayed enabled and OK
  re-registered every action even when nothing had been changed.
- Pressing a key in the shortcut table with no row selected no longer throws. "Remove Shortcut" is
  now disabled unless tmux keybindings are on and a row is selected.

## [0.3.0] - 2026-08-15

### Added

- Added a "+" button to the tool window tab bar that lists the configured TUI apps and starts the
  selected one as a new session.
- Added "Rename Session" to the tab context menu, so a session can be given a name of its own.
- Multiple sessions of the same TUI app can now be open at once. New sessions of an app that is
  already open get a numbered name, such as `claude`, `claude 1`, `claude 2`.
- TUI tabs can now be closed from the tab context menu and the tab's close button. "Close Tab",
  "Close Other Tabs" and "Close All Tabs" all work, and closing a tab this way shuts down its
  session.

### Fixed

- "Select Next/Previous Tab" now follows the visible tab order. It previously drifted out of order
  after wrapping past the first or last tab, after closing a tab, or after dragging a tab, because
  the order was read from internal state instead of from the tool window itself.
- Holding or repeating the tab shortcut now advances one tab per press instead of collapsing several
  presses into a single step.
- The "Rename Session" input now opens below the tab instead of on top of it.

## [0.2.0] - 2026-08-15

### Fixed

- Escape now reaches the running TUI app instead of moving focus to the editor.
- The IDE's "Select Next/Previous Tab" shortcuts now switch TUILaunch tabs while a TUI terminal is
  focused, and are left to the IDE everywhere else.

## [0.1.0] - 2026-07-11

### Added

- Added a dedicated TUILaunch tool window.
     - TUI apps no longer use the built-in Terminal tool window directly.
     - Sessions are managed in their own TUILaunch tool window.
- Added tmux-like prefix keybindings inside TUI terminals.

[Unreleased]: https://github.com/atm1020/TUILaunch/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/atm1020/TUILaunch/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/atm1020/TUILaunch/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/atm1020/TUILaunch/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/atm1020/TUILaunch/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/atm1020/TUILaunch/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/atm1020/TUILaunch/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/atm1020/TUILaunch/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/atm1020/TUILaunch/commits/v0.1.0
