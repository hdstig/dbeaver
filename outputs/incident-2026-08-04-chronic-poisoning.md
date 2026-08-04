# Incident 2026-08-04: chronic handler poisoning — diagnosis and deferred fix plan

Status: **on hold** (2026-08-04, Stig's call — watch for further occurrences before
changing anything). Build in use: patched 25.3.4, guard at `3c0c96907a`
(devel: `5a563c7bae`).

## Symptom

Home/End reverted to text start/end in the fixed build after ~a week of quiet
operation. Switching between editors did **not** fix it; it recovered "by
itself" shortly after. No exceptions anywhere in the logs.

## Evidence (from `~/Library/DBeaverData/workspace6/.metadata/dbeaver-debug.log`)

- Instance started 2026-08-03 14:42. First guard repair 15 minutes in.
- **199 repair bursts in ~23 h of uptime**, last at 13:56 — poisoning is
  *chronic* in this workload, roughly every few minutes of active editing.
  The guard has been silently healing it all along; the 13:43–13:51 window was
  only the first time the timing made it user-visible.
- Zero exceptions in `.log` around the incident (13:35–13:55). Repair bursts
  are the only entries.

## Diagnosis

1. **Why it felt broken despite 15 repair bursts in that window:** the recovery
   check fires ~1 s after each session start (`RECOVERY_CHECK_DELAY_MS`).
   During continuous typing, auto-activation starts a new completion session
   every few keystrokes, and each new session re-nulls the handlers before the
   previous repair matters. Back-to-back ~1 s dead windows read as "broken".
   Pausing typing let the last repair stick — the mysterious self-heal.
2. **Why editor switching didn't help:** per-editor cache seeding gap. An
   editor never active while handlers were healthy has no cache entry, and
   repair (correctly) refuses to install another editor's handler. Switching
   *to* an unseeded editor repairs nothing; switching back was followed by
   re-poisoning within seconds.
3. **Why no log clues:** a throwing listener would leave a stack trace; there
   is none. The silent mechanisms live in `ContentAssistant` itself:
   - the begin/end **asymmetry** — `fireSessionEndEvent` silently not fired
     when `getProcessors(...)` is null/empty at hide-time caret offset;
   - the **no-popup session** — a begin event fires, no proposals materialize,
     no end event ever follows.
   Both null the handlers via the platform's `KeyBindingSupportForAssistant`
   and never restore, leave no trace, and plausibly fire on nearly every
   session while typing in certain contexts. Frequency (199/day) fits.

Lesson correction: the review-time argument "recovery is near-immediate, so
prevention can be deferred" is true per incident and **false under sustained
churn** — a per-session poisoning source outruns a 1 s repair loop.

## Deferred fix plan (small, both files already ours)

1. **Repair at session start.** The guard's listener provably runs before the
   platform listener nulls the handlers (registration order, demonstrated by
   the throwing-listener smoke). Repairing there fixes the previous session's
   damage before the platform snapshots-and-nulls again — collapses the broken
   window during typing to ~nothing, and makes the platform's own save/restore
   healthy again (it saves whatever handler is current at start).
2. **Snapshot at session start** — closes the unseeded-editor cache gap, so
   editor switching always has something to restore.
3. **Detect the silent end-drop in `hide()`** (protected, overridable in
   `SQLContentAssistant`): popup was open before `super.hide()` and the guard
   saw no end event during it ⇒ log one explicit root-cause line ("session
   ended without an end event") and repair immediately instead of waiting for
   the timer. Note: does **not** catch the no-popup variant (hide may never be
   called there) — the timer remains the backstop for that one.
4. **De-noise the repair log** — one line per burst listing the commands, not
   eight lines.

Escalation criterion: if after 1–3 the explicit root-cause logging shows drops
coming from a *listener* (a stack trace appears) rather than the silent
asymmetry, the deferred universal listener wrapping moves back on the table
(remember: `ICompletionListener` + `Extension` + `Extension2`, and the
superclass-constructor registration hazard — see final-report.md).

Verification when implemented: 25.3.4 unit tests + BOTH smoke harnesses
(`outputs/harnesses/`, run both — see README there), plus a new unit case per
change; red-check at least the session-start repair (inject: skip it, assert a
mid-typing sequence stays broken).

## Quick triage for a future recurrence

```sh
L=~/Library/DBeaverData/workspace6/.metadata/dbeaver-debug.log
grep -c "Restored lost handler for 'org.eclipse.ui.edit.text.goto.lineStart'" "$L"  # bursts this session
grep -m1 "is starting" "$L"                                                          # session start time
awk '/<incident time window>/' ~/Library/DBeaverData/workspace6/.metadata/.log       # any real exceptions?
```

High burst count + zero exceptions ⇒ same silent mechanism as this incident.
Bursts *stopping* while symptoms persist ⇒ different problem (guard disabled or
cache empty) — treat as new.
