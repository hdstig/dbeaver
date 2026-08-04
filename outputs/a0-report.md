# A0 status: red harness confirmed (FAIL on unpatched build)

Date: 2026-07-27. Checkout: `work/dbeaver`, branch `fix/9414-assist-handler-guard`
(off `devel` @ `09bc17b009`), zero commits — harness kept uncommitted per brief.

## Result (unpatched build)

```
FAIL
sessionStarted=true
listenerThrew=true
handledBefore=true
handledAfter=false
completionError=null
hideError=java.lang.RuntimeException: Injected dbeaver#9414 completion-listener failure
```

Launcher exit code: 1 (= FAIL). Raw file: `work/dbeaver/work/smoke-results/unpatched.txt`
(copy: `outputs/a0-smoke-unpatched-result.txt`).

This confirms the section-1 analysis end-to-end: one listener throwing in
`assistSessionEnded` starves the platform's `KeyBindingSupportForAssistant` restore
(the injected exception propagated all the way out of `ContentAssistant.hide()` —
no SafeRunner in the listener loop), leaving
`org.eclipse.ui.edit.text.goto.lineStart` permanently unhandled.

## Red/green matrix (brief §A4)

| Build            | Expected | Actual |
|------------------|----------|--------|
| unpatched        | FAIL     | **FAIL** ✓ |
| after A1         | FAIL     | **FAIL** ✓ (2026-07-27, `work/smoke-results/after-a1.txt`) |
| after A2         | FAIL     | **FAIL** ✓ (2026-07-28, `work/smoke-results/after-a2.txt`) |
| after A3         | PASS     | **PASS** ✓ (2026-07-28, `work/smoke-results/after-a3.txt`) |

Matrix complete: FAIL → FAIL → FAIL → PASS, attributing the fix to the A3 watchdog.

A1 commit: `cf7b4caeef` — `#9414 Do not propagate exceptions from SQL completion
listener` (only production `ICompletionListener`: `SQLCompletionProcessor.CompletionListener`;
`assistSessionRestarted` left unwrapped — bare field assignment, cannot throw).

A2 commit: `cf4830d36b` — `#9414 Re-show completion popup only after the previous
session has ended`. The re-show trigger moved from `setLastCompletionOffset(-1)`
(a side effect of the processor listener's `assistSessionEnded`) into a dedicated
`SessionRestartListener` registered first on the assistant itself; verified
`setLastCompletionOffset(-1)` had no other caller, so behavior is preserved.
`outputs/a0-harness.patch` was regenerated on top of A2 (the harness and A2
touch the same file).

A3 (uncommitted yet — will be committed together with the A4 tests):
`AssistCommandHandlerGuard` in `plugins/.../sql/syntax/`, installed from
`SQLContentAssistant`'s constructor (before any listener that may fail).
Static `GuardState` (command→handler cache + active-session counter) behind a
`CommandAccess` interface for workbench-free unit testing. Snapshot at guard
construction, after each healthy session end, and on editor part activation
(per-window `IPartListener2`); repair on `assistSessionEnded` (deferred via
`asyncExec` so the platform restore runs first) and on editor part activation.
Deliberate deviation from the brief: repair only when `getHandler() == null`
(the actual #9414 poisoned state) instead of `!isHandled()` — never stomps a
live-but-currently-disabled foreign handler. Consequence observed in the
smoke log: the guard restored the 8 nulled commands; `lineUp`/`lineDown`
(replaced with proposal-navigation handlers, not nulled) are left to the
upstream B1 fix.
Note: plain `git add`/`commit` hang on this checkout; the commit was made with
plumbing (`hash-object` → `update-index --cacheinfo` → `write-tree` →
`commit-tree` → `update-ref`), which avoids the worktree scan entirely.

## Harness (never commit)

Patch: `outputs/a0-harness.patch` — three files:

- `plugins/.../sql/syntax/SQLContentAssistant.java` — registers a completion
  listener that throws once in `assistSessionEnded`, drives
  `showPossibleCompletions()` + `hide()`, asserts
  `getCommand("...goto.lineStart").isHandled()` after two `asyncExec` cycles,
  writes PASS/FAIL/ERROR to `-Ddbeaver.test.9414.output` and `System.exit`s
  (0=PASS, 1=FAIL, 2=ERROR).
- `plugins/.../app/standalone/ApplicationWorkbenchWindowAdvisor.java` — on
  `postWindowOpen` creates project `dbeaver-9414-smoke` with `scratch.sql`,
  opens it in the SQL editor; 30 s watchdog writes ERROR if the assistant
  hook never runs.
- `plugins/.../app/standalone/ApplicationWorkbenchAdvisor.java` — backup hook.

To restore after `git stash`/reset: `git apply outputs/a0-harness.patch` from
the `work/dbeaver` root.

## How to re-run the smoke (after A1, A2, A3)

1. Rebuild the touched plugins (Tycho): at minimum
   `mvn -pl plugins/org.jkiss.dbeaver.ui.editors.sql -am package` and same for
   `plugins/org.jkiss.dbeaver.ui.app.standalone` (JDK 21 at
   `~/Library/Java/JavaVirtualMachines/temurin-21.0.6`; default `java` is 17).
2. Refresh the product jars in
   `product/community/target/products/org.jkiss.dbeaver.core.product/macosx/cocoa/aarch64/DBeaver.app/Contents/Eclipse/plugins/`
   — the existing jars (`..._202607240941.jar`) were updated in place with the
   rebuilt classes (keep the original filenames/manifest version; a full
   product rebuild also works but is slow).
3. Launch:
   `DBeaver.app/Contents/MacOS/dbeaver --launcher.suppressErrors`
   The `dbeaver.ini` next to the launcher already carries `-data` (smoke
   workspace), `-clean`, `-Ddbeaver.test.9414=true` and
   `-Ddbeaver.test.9414.output=.../work/smoke-results/unpatched.txt` — point
   the output at a new file per phase (e.g. `after-a1.txt`).
4. The app exits by itself in ~1 min; read the output file.

## Gotchas discovered

- **`Boolean.getBoolean` needs the literal `true`**: the ini originally had
  `-Ddbeaver.test.9414=1`, which is why the Jul 24 runs (11:33/11:39/11:47)
  silently did nothing for 24 min. Fixed to `=true` in the product
  `dbeaver.ini` (that file is build output — regenerated on product rebuild,
  re-apply if rebuilt).
- A benign `BadLocationException` from
  `SQLCompletionProcessor.computeCompletionProposals` is logged during the
  scratch-editor completion; the session still starts (`sessionStarted=true`),
  so it does not affect the oracle.
- (resolved 2026-07-28) `git status` / full-tree scans used to stall for 5+
  minutes: the checkout lived in iCloud-synced `~/Documents` and had been
  dataless'ed. The tree now lives at
  `/Users/stig/dev/codex-chats/2026-07-24/ple` (outside iCloud) and git is
  instant. `~/Documents/Codex` is now a symlink to `~/dev/codex-chats`, so
  both paths refer to the same tree. The A1 commit was made with git plumbing
  to work around the stall; normal `git add`/`commit` is fine from now on.
