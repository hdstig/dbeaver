# Final report: DBeaver #9414 — Task A (macOS Home/End keybindings die after content assist)

Date: 2026-07-28 (updated after a post-release defect — see the last section).
All work per `dbeaver-9414-agent-brief.md`. Branches pushed to the fork
`hdstig/dbeaver` at the user's request; no PRs or issue comments (per brief guardrails).

## Branch and commits

Repo: `work/dbeaver`, branch `fix/9414-assist-handler-guard` off `devel` @ `09bc17b009`:

| Commit | Sub-task | Summary |
|---|---|---|
| `cf7b4caeef` | A1 | #9414 Do not propagate exceptions from SQL completion listener |
| `cf4830d36b` | A2 | #9414 Re-show completion popup only after the previous session has ended |
| `111286ac34` | A3+A4 | #9414 Watchdog restoring text navigation handlers lost after content assist |

The A0 fault-injection harness stays uncommitted (3 modified files in the
working tree); it is preserved verbatim in the appendix below and as
`outputs/a0-harness.patch` (`git apply` from the repo root to restore).

## Fault-injection smoke matrix (the A0 oracle)

Oracle: `ICommandService.getCommand("org.eclipse.ui.edit.text.goto.lineStart").isHandled()`
after a completion listener throws once in `assistSessionEnded` (never caret
movement — macOS SWT fallbacks mask the breakage).

| Build | Expected | Actual | Result file |
|---|---|---|---|
| unpatched (`devel`) | FAIL | **FAIL** | `a0-smoke-unpatched-result.txt` |
| after A1 | FAIL | **FAIL** | `a1-smoke-result.txt` |
| after A2 | FAIL | **FAIL** | `a2-smoke-result.txt` |
| after A3 | PASS | **PASS** | `a3-smoke-result.txt` |

Repeated on the **25.3.4** release base (the version in daily use), with the same
harness compiled against that release's own jars:

| Build | Expected | Actual | Result file |
|---|---|---|---|
| pristine 25.3.4 | FAIL | **FAIL** | `a3-smoke-25.3.4-unpatched.txt` |
| 25.3.4 + fix | PASS | **PASS** | `a3-smoke-25.3.4-patched.txt` |

So the bug reproduces and the fix holds on a shipped release, not only on `devel`.
The three commits cherry-pick onto the `25.3.4` tag with no conflicts (branch
`fix/9414-assist-handler-guard-25.3.4`, base `b09ab8b34b`).

The matrix attributes the fix to the A3 watchdog: the injected fault bypasses
A1/A2 by design. In the PASS run the guard logged
`Restored lost handler for '<id>' (see dbeaver#9414)` for exactly the eight
commands the platform nulls; `lineUp`/`lineDown` (replaced with
proposal-navigation handlers, not nulled) are deliberately left to the
upstream fix (see Deviations).

## Build and test results

- Tycho: `mvn -pl plugins/org.jkiss.dbeaver.ui.editors.sql,test/org.jkiss.dbeaver.ui.editors.sql.test -am integration-test`
  → BUILD SUCCESS; both modules compile.
- Unit tests (new fragment `test/org.jkiss.dbeaver.ui.editors.sql.test`,
  `AssistCommandHandlerGuardTest`): **11/11 pass** under tycho-surefire in the
  OSGi runtime, and in a plain JUnit 5 run. Coverage: healthy snapshot +
  repair after lost end; no repair during active/nested sessions; no
  overwrite of foreign handlers (handled or unhandled-but-present); latest
  healthy handler wins; no snapshot during a session; no-op without snapshot
  or without command service; counter never goes negative.
- Acceptance (brief §A4): matrix FAIL/FAIL/FAIL/PASS ✓; plugin compiles in
  Tycho ✓; unit tests pass ✓; with the fault injected, lineStart is handled
  again within one UI event cycle after popup close ✓ (guard repair runs from
  the first asyncExec scheduled during the end-event dispatch).

## Changes

- **A1** (`SQLCompletionProcessor`): the bodies of `assistSessionStarted` /
  `assistSessionEnded` / `selectionChanged` in DBeaver's only production
  `ICompletionListener` wrapped in try/catch logging via the class `Log`
  (`assistSessionRestarted` is a bare assignment — left unwrapped).
- **A2** (`SQLContentAssistant`): the popup re-show requested by
  `SQLAutoAssistListener.verifyKey` is now scheduled from a dedicated
  `SessionRestartListener.assistSessionEnded` (first in the listener list)
  instead of the `setLastCompletionOffset(-1)` side effect; verified `-1` had
  no other caller, so behavior is preserved while no longer depending on
  another listener's health.
- **A3** (`AssistCommandHandlerGuard`, new): static snapshot/repair watchdog
  for the ten guarded command IDs; session counter for multi-editor safety;
  repair on session end (asyncExec-deferred so the platform restore runs
  first) and on editor part activation; never throws; null-safe services.
- **A4**: state machine behind a `CommandAccess` seam; tests in a new test
  fragment modeled on `org.jkiss.dbeaver.model.sql.test` (the plugin had no
  test infrastructure; a fragment keeps internals package-private).

## Deviations from the brief

1. **Repair condition**: `getHandler() == null` instead of `!isHandled()` —
   only the null handler is the #9414 poisoned state; this never stomps a
   live-but-disabled foreign handler. Consequence: stale proposal-navigation
   handlers on `lineUp`/`lineDown` after a lost end are not repaired
   (identical to pre-fix behavior; masked by SWT fallbacks; properly fixed by
   upstream B1).
2. **Test placement**: the brief suggested the closest existing test bundle;
   a new per-plugin test fragment matches the repo's actual convention
   (`model.sql.test` et al.) and avoids widening the guard's visibility.
   Noted in the commit message as the brief asks.
3. **A0 harness placement**: the workbench-open hook lives in
   `ApplicationWorkbenchWindowAdvisor`/`ApplicationWorkbenchAdvisor` (product
   plugin) rather than a generic "early UI startup point"; inherited from the
   original session and kept.

## Task B (upstream eclipse.platform.ui)

Not started (optional per brief). The DBeaver-side fix is self-sufficient for
the reported symptom; B1/B2/B3 remain worthwhile upstream (SafeRunner in the
listener loops, tri-state restore, begin/end symmetry, dispose-time restore).

## Post-release defect: the guard disabled itself (fixed 2026-07-28)

The shipped A3 guard **failed in real use**: Line Start/End broke again on a
patched 25.3.4 build, with the guard installed and zero repairs logged.

Cause: session tracking used a counter incremented in `assistSessionStarted`
and decremented in `assistSessionEnded`, and both `snapshot()` and `repair()`
were skipped while it was positive. A **lost end event never decrements it**, so
the counter latched positive and the guard became a permanent no-op — defeated
by the exact failure it exists to repair.

Why the A0-A3 matrix missed it: the harness injects a listener that *throws
inside* `assistSessionEnded`. The guard is registered first, so its own
`assistSessionEnded` had already run and balanced the counter before the throw.
The fault injected was therefore not representative of the real failure, where
the end event is never delivered at all.

Fix (`92f2ec6351` on devel, `c40c014bdb` on 25.3.4): track a start timestamp per
session, weakly keyed on the listener; a session counts as finished when it goes
stale (`SESSION_STALE_MS` = 30 s) or when an editor part is activated (activation
proves no earlier popup is open). Session state can no longer latch.

New evidence, on 25.3.4, simulating a genuinely lost end event (completion
listeners cleared mid-session so `hide()` fires no end event at all):

| Guard version | handledAfterLostEnd | handledAfterActivation | Verdict |
|---|---|---|---|
| as shipped (`42e65992c4`) | false | false | **FAIL** (`a5-lostend-oldguard.txt`) |
| fixed (`c40c014bdb`) | false | **true** (8 repairs logged) | **PASS** (`a5-lostend-fixed.txt`) |

Unit tests: 14/14 pass; the three new regression cases (staleness expiry,
activation clears leaked sessions, snapshot recovers after a leak) were verified
to **fail** against the counter implementation before the fix was restored.

Lesson recorded: a fault injector must reproduce the *mechanism* of the real
failure, not merely a failure with the same visible symptom.

## Review follow-up: middle-ground hardening (2026-07-28)

A review requested six changes. Four were adopted as one follow-up commit
(`5a563c7bae` on devel, `3c0c96907a` on 25.3.4); two were deferred by agreement.

Adopted:

1. **Live popup state replaces the staleness timeout.** Session activity now comes
   from `ContentAssistant#isProposalPopupActive()` (exposed package-private via
   `SQLContentAssistant#isProposalPopupCurrentlyActive`), consulted on every call.
   No timeout constant, and no bookkeeping a lost event can latch. A popup left
   open indefinitely is never mistaken for a finished session.
2. **Deferred recovery check.** A one-shot check is scheduled after each session
   start, re-armed only while the popup is genuinely open, and made a no-op by a
   generation token once the session ends or a newer one starts. A lost end event
   is repaired about a second later with no user action - previously it needed
   another session or an editor activation.
3. **Per-editor handler cache.** Handlers are cached per editor part (weakly
   keyed) and only installed while their own part is active; dropped on
   `partClosed` and on assistant `uninstall()`. This fixed a **reachable** bug:
   part activation repaired *before* re-snapshotting and a nulled handler is never
   re-cached, so switching from editor A into a poisoned editor B installed A's
   handler under B.
4. **Honest command scope.** `GUARDED_COMMAND_IDS` narrowed from ten to the eight
   the platform nulls; `LINE_UP`/`LINE_DOWN` are replaced rather than nulled and
   were never restorable by a guard that only fills in missing handlers.
   Recognising them would mean matching private platform classes by name.

Deferred by agreement (both *prevent* poisoning rather than recover from it, and
carry the most regression surface; with recovery now near-immediate their
marginal value is small):

- Wrapping every completion listener so any third-party/platform listener that
  throws cannot starve the loop. Note for whoever picks this up: this jface has
  **three** listener interfaces (`ICompletionListener`,
  `ICompletionListenerExtension`, `ICompletionListenerExtension2`) - a wrapper
  preserving only the first two silently drops `applied(...)` notifications. Also
  `ContentAssistant`'s constructor may register listeners before subclass fields
  initialise, so wrapper bookkeeping needs lazy init.
- Unconfiguring the source viewer before `super.dispose()` in `SQLEditorBase`.

Verification (all on 25.3.4, the version in daily use):

| Check | Result |
|---|---|
| Unit tests | **19/19** under tycho-surefire |
| Cross-editor + throwing-owner regressions | verified **red** by injecting each defect, then restored |
| Throwing-listener smoke | **PASS** (`tier1-smoke-throwing-listener.txt`) |
| Lost-end smoke | **PASS**, 8 repairs logged (`tier1-smoke-lostend.txt`) |
| `git diff --check` | clean |
| Focused reactor build | BUILD SUCCESS (plugin + test fragment) |

The harnesses are now preserved as whole files under `outputs/harnesses/` with a
README, rather than as a diff: the previous `a0-harness.patch` silently rotted
against this very commit, which is exactly how the acceptance gate would have
been lost.

## Appendix: A0 harness patch (verbatim, never commit)

```diff
diff --git a/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchAdvisor.java b/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchAdvisor.java
index 30988ec236..c2b0ead84b 100644
--- a/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchAdvisor.java
+++ b/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchAdvisor.java
@@ -67,6 +67,7 @@ import org.jkiss.dbeaver.ui.AWTUtils;
 import org.jkiss.dbeaver.ui.DBeaverIcons;
 import org.jkiss.dbeaver.ui.UIExecutionQueue;
 import org.jkiss.dbeaver.ui.UIFonts;
+import org.jkiss.dbeaver.ui.UIUtils;
 import org.jkiss.dbeaver.ui.actions.datasource.DataSourceHandler;
 import org.jkiss.dbeaver.ui.app.standalone.internal.CoreApplicationActivator;
 import org.jkiss.dbeaver.ui.app.standalone.internal.CoreApplicationMessages;
@@ -295,6 +296,15 @@ public class ApplicationWorkbenchAdvisor extends IDEWorkbenchAdvisor {
         if (DBWorkbench.getPlatform() instanceof DesktopPlatform platformDesktop) {
             platformDesktop.setWorkbenchStarted(true);
         }
+
+        if (Boolean.getBoolean("dbeaver.test.9414")) {
+            UIUtils.asyncExec(() -> {
+                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
+                if (window != null) {
+                    ApplicationWorkbenchWindowAdvisor.runIssue9414Smoke(window);
+                }
+            });
+        }
     }
 
     private void filterPreferencePages() {
diff --git a/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchWindowAdvisor.java b/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchWindowAdvisor.java
index b95f6a34ae..476a3349e5 100644
--- a/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchWindowAdvisor.java
+++ b/plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/ApplicationWorkbenchWindowAdvisor.java
@@ -39,6 +39,7 @@ import org.eclipse.ui.internal.ide.IDEWorkbenchPlugin;
 import org.eclipse.ui.internal.ide.application.IDEWorkbenchWindowAdvisor;
 import org.eclipse.ui.internal.progress.ProgressManagerUtil;
 import org.eclipse.ui.internal.registry.EditorRegistry;
+import org.eclipse.ui.ide.IDE;
 import org.eclipse.ui.part.EditorInputTransfer;
 import org.eclipse.ui.part.MarkerTransfer;
 import org.eclipse.ui.part.ResourceTransfer;
@@ -63,10 +64,15 @@ import org.jkiss.dbeaver.ui.editors.DatabaseEditorPreferences;
 import org.jkiss.dbeaver.ui.editors.EditorUtils;
 import org.jkiss.dbeaver.utils.GeneralUtils;
 
+import java.io.ByteArrayInputStream;
+import java.nio.charset.StandardCharsets;
+import java.nio.file.Files;
+import java.nio.file.Path;
 import java.util.StringJoiner;
 
 public class ApplicationWorkbenchWindowAdvisor extends IDEWorkbenchWindowAdvisor implements DBPProjectListener, IResourceChangeListener {
     private static final Log log = Log.getLog(ApplicationWorkbenchWindowAdvisor.class);
+    private static boolean issue9414SmokeStarted;
 
 
     private IEditorPart lastActiveEditor = null;
@@ -343,6 +349,10 @@ public class ApplicationWorkbenchWindowAdvisor extends IDEWorkbenchWindowAdvisor
         log.debug("Finish initialization");
         super.postWindowOpen();
 
+        if (Boolean.getBoolean("dbeaver.test.9414")) {
+            runIssue9414Smoke(getWindowConfigurer().getWindow());
+        }
+
         closeEmptyEditors();
 
         try {
@@ -361,6 +371,55 @@ public class ApplicationWorkbenchWindowAdvisor extends IDEWorkbenchWindowAdvisor
         initWorkbenchWindows();
     }
 
+    static void runIssue9414Smoke(IWorkbenchWindow window) {
+        if (issue9414SmokeStarted) {
+            return;
+        }
+        issue9414SmokeStarted = true;
+        String output = System.getProperty("dbeaver.test.9414.output");
+        try {
+            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("dbeaver-9414-smoke");
+            if (!project.exists()) {
+                project.create(new NullProgressMonitor());
+            }
+            if (!project.isOpen()) {
+                project.open(new NullProgressMonitor());
+            }
+
+            IFile file = project.getFile("scratch.sql");
+            var contents = new ByteArrayInputStream("select 1;\n".getBytes(StandardCharsets.UTF_8));
+            if (file.exists()) {
+                file.setContents(contents, true, false, new NullProgressMonitor());
+            } else {
+                file.create(contents, true, new NullProgressMonitor());
+            }
+
+            IWorkbenchPage page = window.getActivePage();
+            IDE.openEditor(page, file, "org.jkiss.dbeaver.ui.editors.sql.SQLEditor", true);
+
+            Display.getCurrent().timerExec(30_000, () -> {
+                if (output != null && !Files.exists(Path.of(output))) {
+                    try {
+                        Files.writeString(Path.of(output), "ERROR\nSQL content assistant smoke hook did not run\n");
+                    } catch (Exception e) {
+                        log.error("Error writing #9414 smoke result", e);
+                    }
+                    System.exit(2);
+                }
+            });
+        } catch (Throwable e) {
+            log.error("Error starting #9414 smoke test", e);
+            if (output != null) {
+                try {
+                    Files.writeString(Path.of(output), "ERROR\n" + e + "\n");
+                } catch (Exception writeError) {
+                    log.error("Error writing #9414 smoke result", writeError);
+                }
+            }
+            System.exit(2);
+        }
+    }
+
     @Override
     public void handleActiveProjectChange(@NotNull DBPProject oldValue, @NotNull DBPProject newValue) {
         UIUtils.asyncExec(this::recomputeTitle);
@@ -564,4 +623,3 @@ public class ApplicationWorkbenchWindowAdvisor extends IDEWorkbenchWindowAdvisor
     }
 
 }
-
diff --git a/plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax/SQLContentAssistant.java b/plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax/SQLContentAssistant.java
index 5e0c981221..2d66de7636 100644
--- a/plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax/SQLContentAssistant.java
+++ b/plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax/SQLContentAssistant.java
@@ -16,18 +16,23 @@
  */
 package org.jkiss.dbeaver.ui.editors.sql.syntax;
 
-import org.eclipse.jface.text.contentassist.ContentAssistEvent;
-import org.eclipse.jface.text.contentassist.ContentAssistant;
 import org.eclipse.jface.text.contentassist.ICompletionListener;
 import org.eclipse.jface.text.contentassist.ICompletionProposal;
+import org.eclipse.jface.text.contentassist.ContentAssistEvent;
+import org.eclipse.jface.text.contentassist.ContentAssistant;
 import org.eclipse.swt.SWT;
 import org.eclipse.swt.events.VerifyEvent;
+import org.eclipse.ui.PlatformUI;
+import org.eclipse.ui.commands.ICommandService;
 import org.jkiss.dbeaver.Log;
 import org.jkiss.dbeaver.ui.UIUtils;
 import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
 import org.jkiss.dbeaver.ui.editors.sql.SQLEditorUtils;
 import org.jkiss.dbeaver.ui.editors.sql.SQLPreferenceConstants;
 
+import java.nio.file.Files;
+import java.nio.file.Path;
+
 /**
  * SQL Completion proposal
  */
@@ -35,6 +40,8 @@ public class SQLContentAssistant extends ContentAssistant {
 
     private static final Log log = Log.getLog(SQLContentAssistant.class);
 
+    private static boolean issue9414SmokeStarted;
+
     private final SQLEditorBase editor;
 
     private SQLCompletionSorterUI sorter;
@@ -48,6 +55,83 @@ public class SQLContentAssistant extends ContentAssistant {
         enableColoredLabels(true);
         addCompletionListener(new SessionRestartListener());
         AssistCommandHandlerGuard.install(this, editor.getSite() == null ? null : editor.getSite().getWorkbenchWindow());
+        startIssue9414Smoke();
+    }
+
+    private void startIssue9414Smoke() {
+        if (!Boolean.getBoolean("dbeaver.test.9414") || issue9414SmokeStarted) {
+            return;
+        }
+        issue9414SmokeStarted = true;
+
+        var listener = new ICompletionListener() {
+            boolean started;
+            boolean threw;
+
+            @Override
+            public void assistSessionStarted(ContentAssistEvent event) {
+                started = true;
+            }
+
+            @Override
+            public void assistSessionEnded(ContentAssistEvent event) {
+                if (!threw) {
+                    threw = true;
+                    throw new RuntimeException("Injected dbeaver#9414 completion-listener failure");
+                }
+            }
+
+            @Override
+            public void selectionChanged(ICompletionProposal proposal, boolean smartToggle) {
+            }
+        };
+        addCompletionListener(listener);
+
+        UIUtils.asyncExec(() -> UIUtils.asyncExec(() -> {
+            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
+            var command = commandService.getCommand("org.eclipse.ui.edit.text.goto.lineStart");
+            boolean handledBefore = command.isHandled();
+            String completionError = null;
+            Throwable hideError = null;
+            try {
+                completionError = showPossibleCompletions();
+                hide();
+            } catch (Throwable e) {
+                hideError = e;
+            }
+
+            String finalCompletionError = completionError;
+            Throwable finalHideError = hideError;
+            UIUtils.asyncExec(() -> UIUtils.asyncExec(() -> {
+                boolean handledAfter = command.isHandled();
+                String result;
+                int exitCode;
+                if (!listener.started || !listener.threw || !handledBefore) {
+                    result = "ERROR";
+                    exitCode = 2;
+                } else if (handledAfter) {
+                    result = "PASS";
+                    exitCode = 0;
+                } else {
+                    result = "FAIL";
+                    exitCode = 1;
+                }
+
+                String report = result + "\n"
+                    + "sessionStarted=" + listener.started + "\n"
+                    + "listenerThrew=" + listener.threw + "\n"
+                    + "handledBefore=" + handledBefore + "\n"
+                    + "handledAfter=" + handledAfter + "\n"
+                    + "completionError=" + finalCompletionError + "\n"
+                    + "hideError=" + finalHideError + "\n";
+                try {
+                    Files.writeString(Path.of(System.getProperty("dbeaver.test.9414.output")), report);
+                } catch (Exception e) {
+                    exitCode = 2;
+                }
+                System.exit(exitCode);
+            }));
+        }));
     }
 
     public void setLastCompletionOffset(int lastCompletionOffset) {
```
