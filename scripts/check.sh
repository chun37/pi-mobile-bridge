#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
mkdir -p evidence
chmod 700 evidence
python3 scripts/sync-release.py --check > evidence/release-check.log 2>&1
python3 -m unittest discover -s tests -p '*_test.py' > evidence/soak-tests-check.log 2>&1
npm run typecheck > evidence/typecheck-check.log 2>&1
npm test > evidence/extension-tests-check.log 2>&1
(cd relay && go test -race ./... && go vet ./...) > evidence/relay-tests-check.log 2>&1
(cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest) > evidence/android-check.log 2>&1
printf 'PASS: monitor unit, TypeScript, extension, Go race/vet, Android unit/lint/APKs. Logs: %s/evidence/\n' "$root"
