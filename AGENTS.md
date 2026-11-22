# Release policy

- The user explicitly selected release **0.4.0** on 2026-11-23.
- Do not change the release version or Android versionCode without a new explicit user instruction. Bug fixes, new commits, builds, APK reinstalls, deployments, and PR merges do not authorize a version bump.
- `release.json` is the canonical source for Android, relay, extension, and npm package metadata. Never edit generated version mirrors independently.
- Normal verification: `python3 scripts/sync-release.py --check` (also included in `scripts/check.sh` and CI).
- After an authorized release change only: edit `release.json`, then run `python3 scripts/sync-release.py --write` and the full checks. The generator does not select or increment numbers.
- Identify fixes within a release with build/load-time Git revisions and dirty flags. Do not read a newly installed file's version and attribute it to an already running old process.
- External Pi, Herdr, Node.js, Go, and third-party dependency versions are independent; do not change them to match the Bridge release.
