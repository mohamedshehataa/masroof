#!/usr/bin/env python3
"""Count production classes that match the PIT targetClasses globs in backend/build.gradle.kts.

  pit-targets.py [--root DIR]    prints the matching source count; exit 0 always (2 on usage/parse error)

package-info.java and module-info.java are ignored (no mutable code). Used by scripts/make/module.sh to decide whether
"PIT found no mutations" is legitimate (no target classes yet) or a broken classpath/filter.
"""
from __future__ import annotations

import argparse
import fnmatch
import re
import sys
from pathlib import Path


def target_globs(kts: Path) -> list[str]:
    text = kts.read_text(encoding="utf-8-sig")
    m = re.search(r"targetClasses\s*=\s*setOf\((.*?)\)", text, re.S)
    if not m:
        raise ValueError(f"no targetClasses = setOf(...) in {kts}")
    return re.findall(r'"([^"]+)"', m.group(1))


def matching_sources(root: Path) -> list[str]:
    globs = target_globs(root / "backend" / "build.gradle.kts")
    src = root / "backend" / "src" / "main" / "java"
    found: list[str] = []
    for f in sorted(src.rglob("*.java")) if src.is_dir() else []:
        if f.name in ("package-info.java", "module-info.java"):
            continue
        fqn = ".".join(f.relative_to(src).with_suffix("").parts)
        if any(fnmatch.fnmatchcase(fqn, g) for g in globs):
            found.append(fqn)
    return found


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    args = ap.parse_args(argv)
    try:
        found = matching_sources(Path(args.root))
    except (OSError, ValueError) as e:
        print(f"pit-targets: {e}", file=sys.stderr)
        return 2
    print(len(found))
    for c in found:
        print(c, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
