<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# TUILaunch Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/atm1020/TUILaunch/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/atm1020/TUILaunch/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/atm1020/TUILaunch/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/atm1020/TUILaunch/commits/v0.1.0
