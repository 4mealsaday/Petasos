#!/usr/bin/env python3
"""Add reviewed bundle evidence to a private registry without changing existing rows.

This offline tool neither approves content nor rotates grants/tokens. It writes a new
file only; deployment and provider review remain separate operator actions.
"""

import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import stat
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.content_bundle import (
    ContentBundleError, MAX_BUNDLE_BYTES, build_evidence_registry, canonical_bytes,
)
from petasos_server.contracts import Failure, strict_json
from petasos_server.evidence import EvidenceRegistry, MAX_EVIDENCE_BYTES
from petasos_server.public_data import PublicDataError, write_private_candidates

PRIVATE_ROOT = ROOT / ".local"
CONTENT_ROOT = PRIVATE_ROOT / "content"


class CliError(ValueError):
    """Call sites provide only fixed public error codes, never input values."""


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("INVALID_ARGUMENTS")


def _private_path(path, root):
    path = path if path.is_absolute() else ROOT / path
    try:
        relative = path.relative_to(root)
        if (not relative.parts or ".." in relative.parts
                or any(parent.is_symlink() for parent in (path, *path.parents))):
            raise ValueError()
    except ValueError:
        raise CliError("INPUT_OR_OUTPUT_PATH_NOT_PRIVATE") from None
    return path


def _read_private(path, limit):
    try:
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode):
            raise CliError("INPUT_FILE_NOT_PRIVATE")
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if (not stat.S_ISREG(info.st_mode)
                    or (before.st_dev, before.st_ino) != (info.st_dev, info.st_ino)
                    or os.name == "posix" and (info.st_uid != os.geteuid()
                        or info.st_mode & 0o077 or not info.st_mode & stat.S_IRUSR)):
                raise CliError("INPUT_FILE_NOT_PRIVATE")
            raw = stream.read(limit + 1)
            after = os.fstat(stream.fileno())
        current = path.lstat()
        if len(raw) > limit:
            raise CliError("INPUT_FILE_TOO_LARGE")
        if ((info.st_dev, info.st_ino) != (current.st_dev, current.st_ino)
                or (info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                   != (after.st_size, after.st_mtime_ns, after.st_ctime_ns)
                or (after.st_size, after.st_mtime_ns, after.st_ctime_ns)
                   != (current.st_size, current.st_mtime_ns, current.st_ctime_ns)):
            raise CliError("INPUT_FILE_CHANGED")
        return strict_json(raw)
    except CliError:
        raise
    except Failure:
        raise CliError("INVALID_INPUT_JSON") from None
    except (OSError, ValueError, TypeError, RecursionError):
        raise CliError("INPUT_FILE_UNREADABLE") from None


def merge_evidence(base, incoming):
    """Append new revisions; preserve base row values/order and reject grant changes.

    Reference/grant order has no authorization meaning. Equal sets are idempotent,
    but the existing row's original ordering is retained in the output. In
    particular, an empty (revoked) existing grant cannot be re-enabled by import.
    """
    try:
        old, new = EvidenceRegistry(base), EvidenceRegistry(incoming)
    except (ValueError, TypeError, RecursionError):
        raise CliError("INVALID_EVIDENCE_REGISTRY") from None
    old_sources, old_facts = old.revision_fingerprints()
    new_sources, new_facts = new.revision_fingerprints()
    if (any(key in old_sources and old_sources[key] != digest for key, digest in new_sources.items())
            or any(key in old_facts and old_facts[key] != digest for key, digest in new_facts.items())):
        raise CliError("EVIDENCE_REVISION_CONFLICT")
    if any(key in old.sources and set(old.sources[key]["allowedDeviceIds"]) != set(row["allowedDeviceIds"])
           for key, row in new.sources.items()):
        raise CliError("EVIDENCE_GRANT_CONFLICT")
    merged = deepcopy(base)
    merged["sources"].extend(deepcopy(row) for row in incoming["sources"]
                             if row["sourceRevisionId"] not in old.sources)
    merged["facts"].extend(deepcopy(row) for row in incoming["facts"]
                           if row["factRevisionId"] not in old.facts)
    EvidenceRegistry(merged)
    # Match the actual serialized file, including the writer's trailing newline.
    if len(canonical_bytes(merged)) + 1 > MAX_EVIDENCE_BYTES:
        raise CliError("EVIDENCE_FILE_TOO_LARGE")
    return merged


def main(argv=None):
    try:
        parser = PrivateParser(description=__doc__)
        parser.add_argument("--base", required=True, type=Path, help="Existing private registry under .local")
        parser.add_argument("--bundle", required=True, type=Path, help="Reviewed bundle under .local/content")
        parser.add_argument("--allow-device-id", required=True, action="append",
                            help="Explicit device grant for incoming sources; never widens an existing grant")
        parser.add_argument("--output", required=True, type=Path, help="New registry under .local/content")
        args = parser.parse_args(argv)
        base_path = _private_path(args.base, PRIVATE_ROOT)
        bundle_path = _private_path(args.bundle, CONTENT_ROOT)
        output_path = _private_path(args.output, CONTENT_ROOT)
        if output_path.exists():
            raise CliError("OUTPUT_ALREADY_EXISTS")
        base = _read_private(base_path, MAX_EVIDENCE_BYTES)
        bundle = _read_private(bundle_path, MAX_BUNDLE_BYTES)
        incoming = build_evidence_registry(bundle, args.allow_device_id)
        document = merge_evidence(base, incoming)
        _, digest = write_private_candidates(output_path.parent, output_path.name, document, private_root=PRIVATE_ROOT)
        result = {"status": "MERGED", "sha256": digest,
                  "counts": {key: len(document[key]) for key in ("sources", "facts")},
                  "preserved": {key: len(base[key]) for key in ("sources", "facts")},
                  "added": {key: len(document[key]) - len(base[key]) for key in ("sources", "facts")},
                  "outputSaved": True, "networkRequests": 0}
        exit_code = 0
    except (CliError, ContentBundleError, PublicDataError) as error:
        result, exit_code = {"status": str(error), "outputSaved": False}, 1
    except Exception:
        result, exit_code = {"status": "MERGE_FAILED", "outputSaved": False}, 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
