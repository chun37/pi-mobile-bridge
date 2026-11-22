#!/usr/bin/env python3
"""Check release.json mirrors. Never choose/increment a version automatically.

Run --write only after an explicit user request to update release.json.
Normal builds and checks use --check (the default).
"""
import argparse
import json
from pathlib import Path
import re


def synchronize(root: Path, write: bool = False) -> list[str]:
    release = json.loads((root / "release.json").read_text())
    version = release["version"]
    code = release["androidVersionCode"]
    if not isinstance(version, str) or not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version):
        raise ValueError("invalid release version")
    if type(code) is not int or not 1 <= code <= 2100000000:
        raise ValueError("invalid Android versionCode")
    header = "Generated from release.json by scripts/sync-release.py; do not edit."
    outputs = {
        "relay/version_generated.go": f'// {header}\npackage main\n\nconst version = "{version}"\n',
        "extension/version.generated.ts": f'// {header}\nexport const VERSION = "{version}";\n',
    }
    for name in ("package.json", "package-lock.json"):
        data = json.loads((root / name).read_text())
        data["version"] = version
        if name == "package-lock.json":
            data["packages"][""]["version"] = version
        outputs[name] = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    mismatches = []
    for name, expected in outputs.items():
        path = root / name
        if not path.exists() or path.read_text() != expected:
            mismatches.append(name)
            if write:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(expected)
    return mismatches


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    flags = parser.add_mutually_exclusive_group()
    flags.add_argument("--check", action="store_true")
    flags.add_argument("--write", action="store_true")
    args = parser.parse_args()
    mismatches = synchronize(args.root, args.write)
    if mismatches and not args.write:
        parser.exit(1, "Release mismatch: " + ", ".join(mismatches) + "\nRegenerate mirrors only; do not bump release.json without explicit user instruction.\n")
    print("PASS: release mirrors" if not mismatches else "Synchronized: " + ", ".join(mismatches))


if __name__ == "__main__":
    main()
