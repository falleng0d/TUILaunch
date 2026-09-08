<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# TUILaunch Changelog

## [Unreleased]

### Changed

- codex is no longer launched with `--dangerously-bypass-hook-trust`. The hook text is the same for every tab, so
  codex asks you to trust it once — answer **Trust all and continue** — and remembers that answer itself. Until
  you answer, the hooks do not run and a reopened codex tab starts fresh.

### Fixed

- A codex tab no longer asks you to trust its hook again for every new tab. The file the two hooks append to is
  named by a `TUILAUNCH_CODEX_STATE` prefix in front of the command instead of inside the hook command itself, so
  the command is the same text for every tab and every launch and codex has one review to offer you — answer
  **Trust all and continue** — which it then keeps itself under `hooks.state` in `~/.codex/config.toml`. A codex
  command that sets `TUILAUNCH_CODEX_STATE` itself is launched exactly as configured, as is every codex command
  on a shell that reads no environment prefix, which is `cmd` and PowerShell on Windows. A codex tab whose state
  file path holds a space or a quote is now followed like any other.
- A reopened codex tab whose two hooks wrote their records at the same moment now finds its session again. Both
  hooks fire at the start of the first turn and append to one file, so their records can share a line; the plugin
  reads the file as a stream of records rather than one record per line, and a record a killed hook left half
  written hides neither the records before it nor the ones appended after it.

## [0.11.0] - 2026-09-08

### Changed

- A reopened tab now comes back to the session you were last in, not the one the tab was launched with. All four
  CLIs report their active session to the plugin themselves, so switching conversations inside the TUI with
  `/resume`, `/clear`, `/new`, `/fork` or a session list is followed: claude through a `SessionStart` hook passed
  inline with `--settings`; codex through a `SessionStart` and a `UserPromptSubmit` hook passed with `-c`, which
  makes the switch visible from the first message sent in the new session onwards; omp through a small extension
  loaded with `--hook`; opencode through a small TUI plugin loaded with an `OPENCODE_TUI_CONFIG` and
  `TUILAUNCH_OPENCODE_STATE` prefix in front of the program, before `headroom` when the command is wrapped. A
  codex `/side` or `/btw` excursion is ignored, because those conversations cannot be resumed. A tab whose CLI
  reported nothing yet falls back to the conversation of its own identifier, so a tab you never typed in still
  comes back fresh.
- opencode is no longer started with `--port`/`--hostname`: the plugin neither creates, selects nor deletes
  sessions through its server any more, and a reopened tab starts with `--session <session id>` from the
  plugin's own record.
- An omp tab whose extension recorded nothing now resumes the session file of its directory that was changed
  last, rather than the one whose name sorts highest, so a session you resumed and kept working in is picked
  again.
- The omp extension and the opencode plugin and its `tui.json` are kept under
  `<IDE system directory>/TUILaunch/integrations/` and written there when they are missing or out of date. No
  configuration file of any CLI is touched: nothing is written or read under `~/.claude`, `~/.codex`,
  `~/.config/opencode`, or anywhere in `~/.omp` other than the one session directory per tab that
  `--session-dir` names.
- A command that carries a hook flag of its own is launched without ours: `claude --settings <file>`, because
  claude keeps only the last `--settings`, and `omp --trusted-extension`, because omp refuses that flag together
  with `--hook`. Such a tab comes back to the conversation of its own identifier, or for omp to the newest
  session file of its directory.
- An opencode command that sets `OPENCODE_TUI_CONFIG` or `TUILAUNCH_OPENCODE_STATE` itself is launched exactly as
  configured, as is every opencode command on a shell that reads no environment prefix, which is `cmd` and
  PowerShell on Windows. A claude tab on those shells is launched with its identifier pinned but no hook.

### Fixed

- A reopened tab that was not asked to resume any session is no longer restarted when it ends within 15 seconds
  of launching, so quitting such a tab yourself no longer reopens it and drops the session it was following.
- An omp extension that is handed another tab's session directory — after a cross-project resume, a `/move` or a
  `/wt` — now records into the directory of the tab it was launched for instead of overwriting that other tab's
  record.
- A bundled integration file that could not be written is left off the command line, so a launch fails no more
  and the tab falls back to starting fresh; the failure is logged.
- A codex state file whose last line was torn inside a multi-byte character no longer hides the records before
  it, and only the tail of a long file is read.
- The sweep that removes the state of tabs that are gone now also removes the staging files a failed rename can
  leave behind.

## [0.10.0] - 2026-09-08

### Added

- A TUI tab that runs `claude`, `codex`, `opencode` or `omp`, directly or through `headroom wrap`, now comes
  back to the conversation it had when the project is reopened instead of starting an empty one. Every tab
  carries an identifier of its own from its first launch, and the plugin adds the resume argument each CLI
  understands: `--session-id`/`--resume` for claude, a `SessionStart` hook plus `codex resume` for codex, a
  per-tab session directory plus `--resume` for omp, and `--session` for opencode, whose tab starts its
  embedded server on a loopback port so the plugin can create the session there as soon as the server answers
  and point the TUI at it. Any other command, any command that already picks a session itself, and any command
  that chains something else onto the CLI with `;`, `&&`, `|` or a redirection, is launched exactly as
  configured. The new "Resume the agent session when a TUI tab is reopened" setting sits under "Reopen TUI tabs
  when the project is opened", is on by default and only applies while that one is on. A tab you never typed in
  comes back fresh, and a reopened tab whose session cannot be resumed is started once more as a new tab with a
  new identity, keeping its name and its place in the tab strip. The CLI a session belongs to is remembered
  with it, so changing an app's command from one agent to another starts a fresh conversation. Closing an
  opencode tab whose session nobody ever prompted in asks its server to delete that session again.

## [0.9.0] - 2026-09-07

### Changed

- "Next TUI Tab" and "Previous TUI Tab", and the prefix commands that map to them, now keep the keyboard
  where it was: the cursor moves from the prompt box of one tab into the prompt box of the tab it lands
  on, and into that tab's session when the session had the keyboard or the tab it lands on has no box
  showing. The prompt box visibility of the tab it lands on is left as it is.
- A prompt recalled with Up or Down in the prompt box now arrives with its fenced code blocks folded,
  so the prose around a long block stays readable. A block is folded only when its fence is closed,
  whether it stands on its own or sits in a list item or a block quote, so an unfinished fence never
  hides the rest of the prompt. The box shows fold markers in its gutter, and clicking one expands that
  block again. The prompt you are typing yourself is never folded, not even when Down brings it back.

## [0.8.0] - 2026-09-04

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
  suggestion is showing.
- GitHub Copilot can complete the prompt box too, as an opt-in alternative. A "Prompt box completions"
  group in the TUILaunch settings offers it under "Completion source" next to JetBrains AI Assistant,
  which stays the default; pick Copilot and the ghost text in the box comes from Copilot instead, while
  every other editor keeps whatever completes it today. Copilot is reached through the standalone
  Copilot Language Server rather than through the JetBrains Copilot plugin, so it needs a Copilot
  subscription and a Copilot client already signed in on this machine, whose credentials TUILaunch
  reuses without ever asking for a login of its own. "Copilot Language Server" takes a binary of your
  own; left empty, the one that ships with the GitHub Copilot plugin or the one on `PATH` is detected
  instead, and the line under the field names what was found. "Check Copilot status" reports who is
  signed in, for the path currently in the field. "Include the PROMPT.md history as completion context"
  decides what Copilot sees: while it is on, which is the default, the prompts already recorded in
  `PROMPT.md` are sent ahead of the draft as one Markdown document, so a suggestion can pick up the
  wording and the subject of earlier prompts, and the newest prompts win once the history grows past
  48 000 characters; while it is off only the draft is sent. Either way the text Copilot receives is a
  Markdown document of TUILaunch's own rather than a file of the project. Whenever Copilot is not ready,
  whether no binary was found, no Copilot client on the machine is signed in, or the server failed to
  start, the box quietly falls back to JetBrains AI Assistant and a balloon names it once per reason.
  The server starts as soon as a prompt box becomes visible with Copilot selected, or as soon as Copilot
  is picked while a box is already open, so only the first suggestion of a session waits for it.

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

[Unreleased]: https://github.com/atm1020/TUILaunch/compare/v0.11.0...HEAD
[0.11.0]: https://github.com/atm1020/TUILaunch/compare/v0.10.0...v0.11.0
[0.10.0]: https://github.com/atm1020/TUILaunch/compare/v0.9.0...v0.10.0
[0.9.0]: https://github.com/atm1020/TUILaunch/compare/v0.8.0...v0.9.0
[0.8.0]: https://github.com/atm1020/TUILaunch/compare/v0.7.0...v0.8.0
[0.7.0]: https://github.com/atm1020/TUILaunch/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/atm1020/TUILaunch/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/atm1020/TUILaunch/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/atm1020/TUILaunch/compare/v0.3.1...v0.4.0
[0.3.1]: https://github.com/atm1020/TUILaunch/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/atm1020/TUILaunch/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/atm1020/TUILaunch/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/atm1020/TUILaunch/commits/v0.1.0
