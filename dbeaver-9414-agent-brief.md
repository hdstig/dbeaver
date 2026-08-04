# Task brief: Fix DBeaver issue #9414 (macOS Home/End keybindings die after content assist)

Audience: an autonomous coding agent running on macOS with git, JDK 21+, and Maven available.
Deliverables: local branches with commits. **Do not push, open PRs, or comment on issues without human review.**

---

## 1. Background (verified analysis — do not re-derive, but do re-verify line references)

Issue: https://github.com/dbeaver/dbeaver/issues/9414 (also #5931, #4483).
Symptom: on macOS, keys bound to *Line Start* / *Line End* (Home/End/POS1, or any key) work after DBeaver
starts, then permanently revert to *Text Start* / *Text End* (jump to top/bottom of the document) after
some minutes of editing in the SQL editor. Only a restart fixes it. The "show key binding" popup still
claims Line Start/Line End is invoked. Disabling code-completion auto-activation is a known workaround.

Root cause (verified by reading source; all claims below were confirmed in code as of July 2026):

1. DBeaver's SQL editor extends Eclipse `AbstractTextEditor`, which installs
   `org.eclipse.ui.texteditor.KeyBindingSupportForAssistant`
   (repo: https://github.com/eclipse-platform/eclipse.platform.ui,
   path: `bundles/org.eclipse.ui.workbench.texteditor/src/org/eclipse/ui/texteditor/KeyBindingSupportForAssistant.java`).
2. On every content-assist session start, `assistSessionStarted` swaps out the **global, workbench-wide**
   handlers of these command IDs: `LINE_UP`, `LINE_DOWN` (replaced with proposal-navigation handlers) and
   `LINE_START`, `LINE_END`, `PAGE_UP`, `PAGE_DOWN`, `TEXT_START`, `TEXT_END`, `SCROLL_LINE_UP`,
   `SCROLL_LINE_DOWN` (handler set to `null`). `assistSessionEnded` restores them.
3. The design is self-poisoning. In the inner class `ReplacedCommand`:
   - `replaceWith(IHandler)` only saves the old handler `if (command.isHandled())`;
   - `activate()` only restores `if (handler != null)`.
   So if a single session-end event is ever lost, the handler stays `null`; every subsequent session then
   finds `isHandled() == false`, saves nothing, restores nothing. Permanent until JVM restart.
4. End events *can* be lost. In JFace `ContentAssistant`
   (repo eclipse.platform.ui, `bundles/org.eclipse.jface.text/src/org/eclipse/jface/text/contentassist/ContentAssistant.java`):
   - `fireSessionBeginEvent` / `fireSessionEndEvent` iterate `fCompletionListeners` in a **plain for loop
     with no SafeRunner** — one listener throwing in `assistSessionEnded` starves all later listeners,
     including the handler restore.
   - Both methods recompute `getProcessors(...)` at the **current caret offset**; if that returns
     null/empty at hide time, the end event is silently not fired at all (begin/end asymmetry).
   - `KeyBindingSupportForAssistant.dispose()` does **not** restore replaced commands (editor closed
     mid-session leaks null handlers).
5. DBeaver aggravates the churn: `SQLContentAssistant` (dbeaver repo,
   `plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax/SQLContentAssistant.java`)
   hides the popup and asynchronously re-shows it (`restartRequested` →
   `UIUtils.asyncExec(() -> showPossibleCompletions())` triggered from `SQLAutoAssistListener.verifyKey`
   on Backspace/ArrowLeft past the completion offset), creating overlapping session start/end events.
   DBeaver also registers its own `ICompletionListener`s (see `SQLCompletionProcessor`).
6. Why the symptom is macOS-only: when a command has no handler, the keystroke falls through to raw SWT
   `StyledText` defaults. In `StyledText.createKeyBindings()`
   (repo https://github.com/eclipse-platform/eclipse.platform.swt,
   `bundles/org.eclipse.swt/Eclipse SWT Custom Widgets/common/org/eclipse/swt/custom/StyledText.java`):
   `IS_MAC` maps `SWT.HOME → ST.TEXT_START` and `SWT.END → ST.TEXT_END`; other platforms map them to
   `ST.LINE_START`/`ST.LINE_END`, which masks the breakage there. **Therefore: never use caret movement as
   the test oracle. The oracle is `ICommandService.getCommand(id).isHandled()`.**

Affected command IDs (constants in `org.eclipse.ui.texteditor.ITextEditorActionDefinitionIds`):
`org.eclipse.ui.edit.text.goto.lineUp`, `.lineDown`, `.lineStart`, `.lineEnd`, `.pageUp`, `.pageDown`,
`.textStart`, `.textEnd`, `.scroll.lineUp`, `.scroll.lineDown`.

---

## 2. Task A (required): DBeaver-side mitigation

Repo: `git clone https://github.com/dbeaver/dbeaver.git` (branch off `devel`).
Build: `mvn -T 1C clean package` at repo root (Tycho build; first run downloads a lot). If the full build
is too slow for iteration, at minimum ensure the touched plugin compiles:
`mvn -pl plugins/org.jkiss.dbeaver.ui.editors.sql -am package`. Read `CONTRIBUTING.md` and match the
project's code style (no reformatting of untouched code; keep the standard license header on new files).

Work strictly test-first: complete A0 and see it fail before changing any production code.

### A0. Reproduce first (red harness)

Build the **unpatched** checkout and implement the fault-injection smoke harness. It needs a display,
but must be **self-driving in-process** — do NOT simulate keystrokes via AppleScript/System Events or
`Display.post()`; those need Accessibility permissions or foreground focus and are flaky. Preconditions:
an active macOS user GUI session (a plain SSH session without a logged-in window-server session cannot
create an SWT `Display`) and `-XstartOnFirstThread` on the JVM (the DBeaver launcher handles this; add it
manually if launching the built app via `java` directly). Recipe:

1. Add a temporary, uncommitted verification hook (e.g., guarded by a system property
   `-Ddbeaver.test.9414=1`, in an early UI startup point such as the workbench-window-open path):
   on the UI thread, open a scratch SQL editor programmatically, obtain its `SQLContentAssistant`,
   register an `ICompletionListener` that throws `RuntimeException` once in `assistSessionEnded`,
   call `showPossibleCompletions()`, then `hide()`.
2. After one or two `Display.asyncExec` cycles, assert
   `ICommandService.getCommand("org.eclipse.ui.edit.text.goto.lineStart").isHandled()`.
3. Write `PASS`/`FAIL` plus a short log to a well-known file (e.g., `/tmp/dbeaver-9414-smoke.txt`) and
   exit the application with a matching exit code, so the result is machine-readable without any GUI
   interaction.
4. Run it on the unpatched build. **Expected: FAIL.** This is the gate: a FAIL here confirms the section-1
   analysis end-to-end. If it unexpectedly PASSES, stop and report — do not proceed to code changes on an
   unconfirmed diagnosis.

Keep the harness (as a stash or clearly-separated patch, never committed) and re-run it after each of
A1–A3. Expected matrix: still FAIL after A1 and after A2 (they reduce real-world triggers; the injected
fault bypasses them by design), PASS only once A3 is in place. Record the matrix in the final report —
it is what attributes the fix to the watchdog.

Fallback if no GUI session is available: `ICommandService` requires a running workbench, so in-process
reproduction is impossible; document this, proceed with A1–A4 using the A4 unit tests as the only oracle,
and flag reduced confidence in the final report.

### A1. Harden DBeaver's own completion listeners

Find every DBeaver implementation of `org.eclipse.jface.text.contentassist.ICompletionListener`
(`grep -rn "ICompletionListener\|assistSessionStarted\|assistSessionEnded" plugins --include=*.java`).
Wrap the bodies of `assistSessionStarted` / `assistSessionEnded` / `selectionChanged` in try/catch that
logs via the class's `Log` instead of propagating. Rationale: item 1.4 — a DBeaver exception must never
starve the platform's handler restore.

### A2. Remove the overlapping hide + async re-show

In `SQLContentAssistant.SQLAutoAssistListener.verifyKey`, the `restartRequested = true; hide();` path plus
the async `showPossibleCompletions()` in `setLastCompletionOffset` can overlap a dying session with a new
one. Change it so the re-show is only scheduled after the session end has actually been observed:
register an `ICompletionListener` on the assistant itself and trigger the pending re-show from its
`assistSessionEnded` (still via `UIUtils.asyncExec`), instead of from `setLastCompletionOffset`.
Preserve existing behavior otherwise (the re-show must still happen in the same situations).
If you determine `setLastCompletionOffset(-1)` is itself only called on session end, a smaller change
that provably serializes the two sessions is acceptable — document your reasoning in the commit message.

### A3. Handler watchdog (the main fix)

New class, suggested name `AssistCommandHandlerGuard`, in
`plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax/`.

Design requirements:
- Keep a **static** map `commandId → IHandler` for the 10 command IDs listed in section 1.
- **Snapshot** phase: capture `command.getHandler()` for each ID whenever the command
  `isHandled()` and no assist session is known to be active. Good snapshot moments: guard construction
  (installed from `SQLContentAssistant`'s constructor via `addCompletionListener`), and each
  `assistSessionStarted` **is too late** (platform listener runs first and has already nulled them) —
  so also snapshot from a workbench part-activation listener or on first editor focus. Only overwrite a
  cached handler with a non-null, different one.
- **Repair** phase: on `assistSessionEnded` (schedule with `UIUtils.asyncExec` so the platform restore
  runs first) and on editor part activation: for each ID, if `!command.isHandled()` and a cached handler
  exists and **no session is currently active** (maintain a static active-session counter incremented in
  `assistSessionStarted`, decremented in `assistSessionEnded`), call `command.setHandler(cached)` and log
  a warning like `"Restored lost handler for <id> (see dbeaver#9414)"`.
- Must be safe with multiple SQL editors open (hence static state + session counter).
- Must never throw (wrap everything; this class runs inside the fragile listener loop).
- Access `ICommandService` via `PlatformUI.getWorkbench().getService(ICommandService.class)`, null-safe.

### A4. Tests + verification

- Unit test: extract the guard's decision logic behind a tiny interface (e.g., `CommandAccess` with
  `isHandled(id)`, `getHandler(id)`, `setHandler(id, h)`) so the snapshot/repair state machine is testable
  without a workbench. Cover: healthy snapshot; repair after simulated lost end; no repair while a session
  is active; no overwrite of a newly-set foreign handler when the command is handled. Place the test
  following existing test conventions in the repo (search for existing `*Test.java` under `test/` or
  `plugins/...test...`; if the plugin has no test infrastructure, create the test under the closest
  existing test bundle and note this in the commit message).
- Final smoke run: re-run the A0 harness against the fully patched build (expect PASS) and include the
  complete red/green matrix (unpatched, after A1, after A2, after A3) plus the harness patch verbatim in
  the final report.
- Acceptance criteria: A0 matrix as expected (FAIL, FAIL, FAIL, PASS); plugin compiles in the Tycho
  build; unit tests pass; with the throwing-listener fault injected, the lineStart command is handled
  again within one UI event cycle after popup close.

Commit as a small series on branch `fix/9414-assist-handler-guard`, each commit message referencing
`#9414` and summarizing the sub-change (A1, A2, A3+A4). The A0 harness stays uncommitted.

---

## 3. Task B (optional): upstream Eclipse platform fixes

Repo: `git clone https://github.com/eclipse-platform/eclipse.platform.ui.git`. Do **not** attempt the full
platform build; it is very heavy. It is sufficient to make the touched bundles compile
(`mvn -pl bundles/org.eclipse.jface.text -am compile` and
`mvn -pl bundles/org.eclipse.ui.workbench.texteditor -am compile`) and to write tests targeting the
existing test bundles (look under `tests/` for `org.eclipse.jface.text.tests` and
`org.eclipse.ui.workbench.texteditor.tests`; follow their existing JUnit style). Branch:
`fix/keybinding-support-for-assistant-poisoning`.

Same discipline as Task A: write the B3 regression tests **first**, run them against the unpatched
bundles, and confirm the poisoning test and the throwing-listener test fail before implementing B1/B2.
If either passes unpatched, stop and report.

### B1. `KeyBindingSupportForAssistant` — make restore unconditional

- Change `ReplacedCommand` to record tri-state: `HAD_HANDLER(h)` vs `WAS_UNHANDLED`, i.e. always record,
  even when `command.isHandled()` is false, and always null the handler on replace only when it had one.
- `activate()` restores accordingly: if the command currently has no handler and we recorded one, set it;
  keep honoring the bug-297834 guard (`!handler.getClass().isInstance(command.getHandler())`) only for the
  case where a *different non-null* handler is present.
- Restore all replaced commands in `dispose()` if a session is still open.

### B2. `ContentAssistant` — safe, symmetric event dispatch

- Wrap each listener invocation in `fireSessionBeginEvent` and `fireSessionEndEvent` in
  `org.eclipse.core.runtime.SafeRunner` (this is the standard platform pattern), so one throwing listener
  cannot starve the rest.
- Fix the begin/end asymmetry: remember at session begin that begin events were fired (and for which
  processors); fire the matching end events on hide regardless of what `getProcessors(...)` returns at
  the hide-time caret offset.

### B3. Regression test

JUnit plug-in test in the texteditor test bundle exercising the state machine directly (no keyboard
events, no macOS dependency — assert on `Command.isHandled()`):

```java
// pseudocode outline
Command lineStart = commandService.getCommand(ITextEditorActionDefinitionIds.LINE_START);
// ensure it has a dummy handler
support.assistSessionStarted(evt);   assertFalse(lineStart.isHandled());
support.assistSessionEnded(evt);     assertTrue(lineStart.isHandled());
// poisoning scenario: lost end event
support.assistSessionStarted(evt);   // handler saved & nulled
support2.assistSessionStarted(evt);  // fresh instance simulating next session after lost end
support2.assistSessionEnded(evt);
assertTrue(lineStart.isHandled());   // fails before B1, passes after
```

Plus a `ContentAssistant`-level test with a listener that throws in `assistSessionEnded`, asserting the
subsequent listeners still ran (fails before B2, passes after).

Acceptance criteria: touched bundles compile; new tests pass; no behavior change for the normal
begin/end path. Note in the final report that upstream contribution requires an ECA and review — leave
the branch local for the human to submit.

---

## 4. Guardrails and reporting

- Minimal diffs; no reformatting; keep license headers; match surrounding style.
- Verify every file/line reference in section 1 against the actual checkout before editing (code drifts).
- If any factual claim in section 1 turns out wrong on inspection, stop and report rather than forcing
  the planned change.
- Never disable or weaken the popup's own key handling (`CompletionProposalPopup.verifyKey`) — arrow-key
  navigation of proposals must keep working.
- Final report: branches created, commits, build/test results, fault-injection outcome (or why skipped),
  and any deviations from this brief with reasoning.
