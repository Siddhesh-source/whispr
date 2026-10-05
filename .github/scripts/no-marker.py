#!/usr/bin/env python3
"""Fails if a marker string appears in any of the given files.

Besides the raw bytes it looks for the encodings the marker could take on
the way: hex (Postgres bytea in pg_dump), base64 at each of the three byte
alignments (JSON payloads), and UTF-16.

Usage: no-marker.py MARKER FILE...
"""
import base64
import sys


def needles(marker: bytes) -> dict[str, bytes]:
    found = {"raw": marker, "hex": marker.hex().encode(), "utf-16le": marker.decode().encode("utf-16le")}
    for offset in range(3):
        encoded = base64.b64encode(b"\0" * offset + marker)
        # Drop the characters that depend on the unknown neighbouring bytes.
        start = (offset * 4 + 2) // 3 + (1 if offset else 0)
        found[f"base64+{offset}"] = encoded[start:-4]
    return found


def main() -> int:
    marker, files = sys.argv[1].encode(), sys.argv[2:]
    if len(marker) < 16 or not files:
        print("usage: no-marker.py MARKER(16+ chars) FILE...", file=sys.stderr)
        return 2
    leaks = 0
    for path in files:
        data = open(path, "rb").read()
        if not data:
            print(f"{path}: empty, nothing was captured", file=sys.stderr)
            leaks += 1
        for name, needle in needles(marker).items():
            if needle and needle in data:
                print(f"{path}: marker found ({name})", file=sys.stderr)
                leaks += 1
    print("no marker found" if not leaks else f"{leaks} problem(s)")
    return 1 if leaks else 0


if __name__ == "__main__":
    sys.exit(main())
