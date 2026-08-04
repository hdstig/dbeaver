#!/bin/sh
# Swaps the staged, fixed build into /Applications/DBeaver-9414.app.
# Refuses to run while that app is open, because replacing bundle jars
# under a running Eclipse/OSGi app can break lazy bundle loading.
set -e
APP=/Applications/DBeaver-9414.app
STAGED=/Applications/DBeaver-9414-staged.app

if [ ! -d "$STAGED" ]; then
    echo "No staged build at $STAGED" >&2
    exit 1
fi
if pgrep -f "$APP/Contents/MacOS/dbeaver" > /dev/null; then
    echo "DBeaver-9414 is still running. Quit it first (Cmd-Q), then re-run." >&2
    exit 1
fi

rm -rf "$APP.old"
[ -d "$APP" ] && mv "$APP" "$APP.old"
mv "$STAGED" "$APP"
echo "Swapped in the fixed build. Previous version kept at $APP.old"
echo "Once the new one looks good:  rm -rf '$APP.old'"
