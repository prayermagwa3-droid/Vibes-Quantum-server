#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME="${VIBES_WORKSPACE_SANDBOX_RUNTIME:-docker}"
IMAGE="${VIBES_WORKSPACE_SANDBOX_IMAGE:-vibes-workspace-android:8.13-agp8.13.2}"
if ! command -v "$RUNTIME" >/dev/null 2>&1; then
  echo "ERROR: $RUNTIME is not installed. No build was attempted." >&2
  exit 20
fi
"$RUNTIME" build -f "$ROOT/sandbox/WorkspaceBuild.Dockerfile" -t "$IMAGE" "$ROOT"
"$RUNTIME" image inspect "$IMAGE" >/dev/null
export VIBES_WORKSPACE_SANDBOX_RUNTIME="$RUNTIME"
export VIBES_WORKSPACE_SANDBOX_IMAGE="$IMAGE"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
python3 "$ROOT/scripts/workspace_runner.py" clone --project "$ROOT/sandbox/fixtures/disposable-android" --output "$TMP/candidate" > "$TMP/clone.json"
python3 "$ROOT/scripts/workspace_runner.py" test --project "$TMP/candidate" --timeout 300 --artifact-dir "$TMP/artifacts" > "$TMP/test.json"
python3 - "$TMP/test.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]))
print(json.dumps({k:r.get(k) for k in ('status','exitCode','durationSeconds','artifactCount','output')},indent=2))
if r.get('status') != 'TESTS_PASSED':
    raise SystemExit('Disposable Android build/test did not pass; worker is NOT ready.')
if not any(a.get('kind') == 'apk' for a in r.get('artifacts', [])):
    raise SystemExit('Build passed without a collected APK artifact; worker is NOT ready.')
PY
printf '\nWORKER_READY image=%s\n' "$IMAGE"
