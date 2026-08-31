<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# TUILaunch Changelog

## [Unreleased]

### Added

- TUI tabs can be brought back when a project is reopened. Turn on "Restore open tabs" in Settings
  and the tabs that were open are relaunched in the same order, keeping their renamed titles and the
  tab that was selected. Tabs are remembered per project, and an app that has since been removed from
  the settings is skipped. This is on by default; turn off "Restore open tabs" in Settings to go back
  to starting with an empty tool window.

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

[Unreleased]: https://github.com/atm1020/TUILaunch/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/atm1020/TUILaunch/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/atm1020/TUILaunch/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/atm1020/TUILaunch/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/atm1020/TUILaunch/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/atm1020/TUILaunch/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/atm1020/TUILaunch/commits/v0.1.0
