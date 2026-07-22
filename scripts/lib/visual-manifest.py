#!/usr/bin/env python3
"""Write a deterministic manifest for a Linux visual-audit artifact set."""

import argparse
import hashlib
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--field", action="append", default=[])
    parser.add_argument("--artifact", action="append", type=Path, default=[])
    args = parser.parse_args()
    fields = {}
    for item in args.field:
        if "=" not in item:
            raise SystemExit(f"invalid field: {item}")
        key, value = item.split("=", 1)
        if not key or key in fields:
            raise SystemExit(f"invalid or duplicate field: {key}")
        fields[key] = value
    artifacts = []
    for path in args.artifact:
        if not path.is_file():
            raise SystemExit(f"artifact is missing: {path}")
        data = path.read_bytes()
        artifacts.append({
            "name": path.name,
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })
    result = {
        "schema": "org.archphene.visual-audit.v1",
        "fields": fields,
        "artifacts": sorted(artifacts, key=lambda item: item["name"]),
    }
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
