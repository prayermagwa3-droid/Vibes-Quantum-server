#!/usr/bin/env bash
set -euo pipefail
TARGET="${1:-$HOME/Vibes Projects/VibesQuantumServer}"
PATCH_ROOT="$(cd "$(dirname "$0")" && pwd)"
if [[ ! -f "$TARGET/build.gradle.kts" || ! -d "$TARGET/src/main/kotlin" ]]; then
  echo "ERROR: target does not look like VibesQuantumServer: $TARGET" >&2
  echo "Usage: bash apply_fixes.sh \"/path/to/VibesQuantumServer\"" >&2
  exit 2
fi
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="$TARGET/.kotlin-fix-backup-$STAMP"
mkdir -p "$BACKUP"
COUNT=0
while IFS= read -r -d '' FILE; do
  REL="${FILE#"$PATCH_ROOT/"}"
  DEST="$TARGET/$REL"
  if [[ -f "$DEST" ]]; then
    mkdir -p "$BACKUP/$(dirname "$REL")"
    cp -p "$DEST" "$BACKUP/$REL"
  fi
  mkdir -p "$(dirname "$DEST")"
  cp "$FILE" "$DEST"
  COUNT=$((COUNT+1))
done < <(find "$PATCH_ROOT/src/main/kotlin" -type f -name '*.kt' -print0)
echo "Applied $COUNT Kotlin source fixes."
echo "Original files (where present) backed up to: $BACKUP"
echo "Next run: cd \"$TARGET\" && gradle clean compileKotlin"
