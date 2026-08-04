# #9414 working artifacts (not part of DBeaver)

Orphan branch backing up the artifacts around the `fix/9414-assist-handler-guard`
branches — things that must never be committed to the fix branches themselves
but are needed to maintain them:

- `dbeaver-9414-agent-brief.md` — the original task brief
- `outputs/harnesses/` — the two fault-injection smoke harnesses (whole files)
  and the workbench-advisor hook patch, with a README on running/rebuilding them.
  **Always run both harnesses**: the throwing-listener one alone passed against
  a guard that was broken in real use.
- `outputs/final-report.md` — full engineering report incl. the post-release
  defect and the review follow-up
- `outputs/build-installable.md` — how to produce a patched installable app
- `outputs/*.txt` — smoke run evidence for every red/green claim
- `outputs/swap-in-fixed-app.sh` — guarded install script

Fix branches: `fix/9414-assist-handler-guard` (devel),
`fix/9414-assist-handler-guard-25.3.4`.
