# Producing an installable DBeaver with the #9414 fix (macOS arm64)

Two routes. **Route A (jar injection into an official install) is the recommended
one** and is what `/Applications/DBeaver-9414.app` currently is: DBeaver 25.3.4
with the fix, sharing the workspace format of the official install.
Route B is a full source build, which only works cleanly on `devel`.

Branches on the fork `hdstig/dbeaver`:

- `fix/9414-assist-handler-guard` — off `devel` (3 commits)
- `fix/9414-assist-handler-guard-25.3.4` — same 3 commits cherry-picked onto the
  `25.3.4` tag, no conflicts

---

## Route A: patch an official install (recommended, ~2 min)

Works for any DBeaver release: compile the three changed source files against
that release's own bundle jars, then update them inside the jar. Because the
sources come from the tag-matched branch, this is version-exact — not a
back-port guess.

```sh
JH=~/Library/Java/JavaVirtualMachines/temurin-21.0.6/Contents/Home
SRC=work/build-2534/dbeaver/plugins/org.jkiss.dbeaver.ui.editors.sql/src
P=/Applications/DBeaver.app/Contents/Eclipse/plugins
SQLJ=$(ls "$P" | grep 'ui.editors.sql_')

# 1. extract the target jar (needed on the classpath: the fix references
#    package-private siblings)
mkdir -p /tmp/w/orig /tmp/w/classes && cd /tmp/w/orig && unzip -q "$P/$SQLJ"

# 2. compile the three files against the release's own jars
"$JH/bin/javac" -proc:none -nowarn -encoding UTF-8 -d /tmp/w/classes \
  -cp "/tmp/w/orig:$P/*" \
  "$SRC"/org/jkiss/dbeaver/ui/editors/sql/syntax/{SQLCompletionProcessor,SQLContentAssistant,AssistCommandHandlerGuard}.java

# 3. copy the app, inject, re-sign
ditto /Applications/DBeaver.app /Applications/DBeaver-9414.app
cd /tmp/w/classes && zip -X -q \
  "/Applications/DBeaver-9414.app/Contents/Eclipse/plugins/$SQLJ" \
  org/jkiss/dbeaver/ui/editors/sql/syntax/{SQLCompletionProcessor,SQLContentAssistant,AssistCommandHandlerGuard}*.class
codesign --force --deep --sign - /Applications/DBeaver-9414.app
xattr -dr com.apple.quarantine /Applications/DBeaver-9414.app
```

Why this is safe rather than a hack: the app already bundles its own JRE and
platform, the sources are the same version as the binary, and the compiler
checks the fix against the real API. Compare the class list before/after —
it must be identical plus `SQLContentAssistant$SessionRestartListener` and the
five `AssistCommandHandlerGuard*` classes, with no leftovers.

**`zip` updates never delete entries.** Re-injecting after a code change can
leave orphaned inner classes behind (this bit once: a stale
`$SessionRestartListener` survived into a control build). Check with
`unzip -l "$P/$SQLJ" | grep <ClassName>` and `zip -d` anything unexpected.

### Verify a patched app

```sh
A=/Applications/DBeaver-9414.app
J="$A/Contents/Eclipse/plugins/$(ls "$A/Contents/Eclipse/plugins" | grep 'ui.editors.sql_')"
unzip -l "$J" | grep -c AssistCommandHandlerGuard          # want 5
unzip -l "$J" | grep -c SessionRestartListener             # want 1 (A2)
unzip -p "$J" 'org/jkiss/dbeaver/ui/editors/sql/syntax/SQLCompletionProcessor$CompletionListener.class' \
  | grep -ac 'Error handling content assist'               # want >0 (A1)
unzip -p "$J" org/jkiss/dbeaver/ui/editors/sql/syntax/SQLContentAssistant.class \
  | grep -ac 9414                                          # want 0 (no harness)
grep -c 'test.9414' "$A/Contents/Eclipse/dbeaver.ini"      # want 0
```

Boot check (own workspace, prints the JRE actually used):

```sh
"$A/Contents/MacOS/dbeaver" -data /tmp/ws -nosplash
grep -a "is starting\|Java version" /tmp/ws/.metadata/dbeaver-debug.log
```

---

## Route B: full source build (only clean on `devel`)

```sh
cd work/dbeaver
git worktree add --detach ../dbeaver-clean fix/9414-assist-handler-guard
cd ../dbeaver-clean
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/temurin-21.0.6/Contents/Home
mvn -T 1C package -DskipTests -B -P product-dbeaver-ce
```

- **`-P product-dbeaver-ce` is mandatory.** Without it the product module list is
  empty and no `.app` is produced — while the build still reports SUCCESS.
- JDK 21 required; the machine default `java` is 17 and Tycho fails on it.
- **Tycho does not bundle a JRE**, yet the generated `dbeaver.ini` points `-vm`
  at `../Eclipse/jre/Contents/Home/lib/libjli.dylib`. Without a JRE there the
  app dies with "A Java Runtime Environment (JRE) or Java Development Kit (JDK)
  must be available in order to run Dbeaver." Fix:
  `ditto /Applications/DBeaver.app/Contents/Eclipse/jre "$APP/Contents/Eclipse/jre"`
  (Temurin 21.0.8, ~94 MB), then re-sign — adding files invalidates the
  signature.

Output:
`product/community/target/products/org.jkiss.dbeaver.core.product/macosx/cocoa/aarch64/DBeaver.app`

Optional dmg:
`hdiutil create -volname "DBeaver 9414fix" -srcfolder "$APP" -ov -format UDZO out.dmg`

### Why route B does not work for 25.3.4

Building the `25.3.4` tag needs more than a worktree:

1. It pins `dbeaver-common` at **2.6.0-SNAPSHOT** (`devel` is on 2.8.0), so that
   repo needs its own `25.3.4` worktree, and `mvn install` there to publish the
   2.6.0 artifacts.
2. It needs a **third sibling repo `dbeaver-jdbc-libsql`** (`../dbeaver-jdbc-libsql`
   is in its module list) that `devel` no longer references. Clone it and check
   out its own `25.3.4` tag.
3. **Blocker**: Tycho then still resolves `org.jkiss.utils` **2.8.0** from the
   local Maven repo (installed by an earlier `devel` build) in preference to
   2.6.0, and `RestServer` lost its type parameter between those versions →
   `The type RestServer is not generic` in `org.jkiss.dbeaver.model.cli`, 32
   errors. Fixing this means isolating the build (`-Dmaven.repo.local=...`,
   which re-downloads the whole target platform) or evicting 2.8.0 from `~/.m2`.

Not worth it — route A gives a verified 25.3.4 build in two minutes.
The worktrees are at `work/build-2534/{dbeaver,dbeaver-common,dbeaver-jdbc-libsql}`
if you want to retry.

---

## Keeping it current

When a new official DBeaver is installed, re-run route A against it. If the fix
no longer compiles, upstream changed the touched classes — rebase the branch:

```sh
cd work/dbeaver
git fetch origin --tags
git branch fix/9414-<newver> <newtag>
git cherry-pick cf7b4caeef cf4830d36b 111286ac34   # A1, A2, A3+A4
```

All three cherry-picked onto 25.3.4 without conflicts, so the fix is not
tightly coupled to a version.
