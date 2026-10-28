#!/usr/bin/env sh
set -eu
PREFIX="${PREFIX:-}"

# Vibes offline-friendly Gradle launcher.
# Prefer an installed Gradle (common on Termux/Android build environments),
# then an explicitly configured GRADLE_HOME, then a locally cached distribution.
if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

if [ -n "${GRADLE_HOME:-}" ] && [ -x "$GRADLE_HOME/bin/gradle" ]; then
    exec "$GRADLE_HOME/bin/gradle" "$@"
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
for candidate in     "$SCRIPT_DIR/gradle/wrapper/dists/gradle-9.6.1-bin"/*/gradle-9.6.1/bin/gradle     "$HOME/.gradle/wrapper/dists/gradle-9.6.1-bin"/*/gradle-9.6.1/bin/gradle     "$PREFIX/opt/gradle/bin/gradle"     "$PREFIX/share/gradle/bin/gradle"
do
    if [ -x "$candidate" ]; then
        exec "$candidate" "$@"
    fi
done

echo "Gradle 9.6.1 is not installed or cached." >&2
echo "For a truly offline build, install/cache Gradle 9.6.1 and the project's dependencies once, then rerun with --offline." >&2
exit 127
