# LinOx terminal v0.7

The terminal has two layers:

1. `PtySession` — native `forkpty()` process session.
2. `TerminalView` — a dependency-free VT/ANSI renderer.

The renderer supports:
- printable UTF-8-ish text
- CR/LF/BS/TAB
- CSI cursor movement and positioning
- erase display/line
- SGR 8/16 ANSI colors and inverse/bold
- save/restore cursor
- alternate screen mode (`?1049h/l`)
- PTY resize
- mobile Enter, Backspace, arrows and Ctrl+C

This is deliberately not advertised as a complete xterm emulator. Programs with
advanced terminal extensions may still need additional escape-sequence support.
