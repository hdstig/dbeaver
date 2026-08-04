# #9414 fault-injection harnesses (never commit to the DBeaver repo)

Two whole-file replacements for
`plugins/org.jkiss.dbeaver.ui.editors.sql/src/.../syntax/SQLContentAssistant.java`,
each carrying the branch code plus one injected fault, and one patch adding the
startup hook that opens a scratch SQL editor.

These are kept as **whole files, not diffs**, because a diff against the branch
rots the moment `SQLContentAssistant` changes — which it did, silently, when the
Tier-1 hardening landed.

| File | Injected fault | Expect |
|---|---|---|
| `SQLContentAssistant-throwing-listener-harness.java` | a completion listener throws once in `assistSessionEnded` (starves the listener loop) | PASS with the guard, FAIL without |
| `SQLContentAssistant-lostend-harness.java` | completion listeners cleared mid-session, so `hide()` fires **no end event at all** | PASS with the guard, FAIL without |
| `workbench-advisor-hooks.patch` | startup hook: creates a scratch project + `scratch.sql`, opens the SQL editor, 30 s watchdog | needed by both |

The lost-end harness is the important one: the throwing-listener harness alone
passed against a guard that was in fact broken in real use, because the guard's
own listener ran before the throw. Always run both.

## Running one

```sh
JH=~/Library/Java/JavaVirtualMachines/temurin-21.0.6/Contents/Home
P=/Applications/DBeaver.app/Contents/Eclipse/plugins
SRC=work/build-2534/dbeaver/plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax

# 1. the harness file must be compiled as SQLContentAssistant.java
mkdir -p /tmp/h/src /tmp/h/orig /tmp/h/classes
cp outputs/harnesses/SQLContentAssistant-lostend-harness.java /tmp/h/src/SQLContentAssistant.java
(cd /tmp/h/orig && unzip -q "$P"/org.jkiss.dbeaver.ui.editors.sql_*.jar)
"$JH/bin/javac" -proc:none -nowarn -d /tmp/h/classes -cp "/tmp/h/orig:$P/*" \
  /tmp/h/src/SQLContentAssistant.java "$SRC"/AssistCommandHandlerGuard.java "$SRC"/AssistPopupOwner.java

# 2. also compile the advisor hooks (apply workbench-advisor-hooks.patch to a worktree first)

# 3. copy an app, inject both class sets, add to dbeaver.ini:
#      -Ddbeaver.test.9414=true
#      -Ddbeaver.test.9414.lostend=true      (lost-end harness only)
#      -Ddbeaver.test.9414.output=/path/result.txt
#    then codesign --force --deep --sign - and run with -data <scratch ws> -nosplash
```

The app exits by itself: 0 = PASS, 1 = FAIL, 2 = ERROR (harness did not run).

## Rebuilding a harness after `SQLContentAssistant` changes

Take the current branch file and re-inject: add the `startIssue9414Smoke` /
`startLostEndSmoke` method plus its call at the end of the constructor, and the
extra imports (`PlatformUI`, `ICommandService`, `Files`, `Path`, and
`IWorkbenchPage` for the lost-end variant). Keep the fault logic byte-identical
so results stay comparable across versions.
