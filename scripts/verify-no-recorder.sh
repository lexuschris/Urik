#!/usr/bin/env bash
# Privacy guard: fails if an APK contains the diagnostic typing recorder.
#
#   scripts/verify-no-recorder.sh <apk>                   # release APK: markers must be absent
#   scripts/verify-no-recorder.sh --expect-present <apk>  # diagnostic APK: self-test, markers must exist
#
# The markers are string constants that only exist in src/diagnostic (and the
# recorder-only call sites R8 strips from release), so they survive obfuscation.
set -euo pipefail

expect_present=false
if [ "${1:-}" = "--expect-present" ]; then
  expect_present=true
  shift
fi
apk="${1:?usage: verify-no-recorder.sh [--expect-present] <apk>}"

markers=(
  "urik_trace.jsonl"
  "urik-input-trace"
  "recording_since_wall_ms"
  "field_skipped"
  "invalidation_in_tracked_region"
)

work="$(mktemp -d)"
trap 'rm -rf -- "${work:?}"' EXIT
unzip -q "$apk" 'classes*.dex' -d "$work"

status=0
for marker in "${markers[@]}"; do
  # grep the files directly: `cat | grep -q` under pipefail misreports hits (SIGPIPE).
  if grep -a -q -F -- "$marker" "$work"/classes*.dex; then
    found=true
  else
    found=false
  fi
  if [ "$expect_present" = true ] && [ "$found" = false ]; then
    echo "::error::self-test failed: '$marker' not found in $apk (guard would be blind)"
    status=1
  elif [ "$expect_present" = false ] && [ "$found" = true ]; then
    echo "::error::recorder marker '$marker' found in $apk; the typing recorder must never ship in this build"
    status=1
  fi
done

if [ "$status" -eq 0 ]; then
  if [ "$expect_present" = true ]; then
    echo "Self-test OK: all recorder markers present in $apk"
  else
    echo "OK: no recorder code in $apk"
  fi
fi
exit "$status"
