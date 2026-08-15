<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# TUILaunch Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/atm1020/TUILaunch/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/atm1020/TUILaunch/commits/v0.1.0
