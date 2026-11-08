#!/usr/bin/env python3
"""Compile one explicitly reviewed local content file; never fetch or approve sources."""

import argparse
import json
import os
from pathlib import Path
import stat
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.content_bundle import ContentBundleError, MAX_REVIEW_BYTES, build_reviewed_bundle, build_evidence_registry
from petasos_server.evidence import EvidenceRegistry
from petasos_server.public_data import PublicDataError, write_private_candidates

PRIVATE_ROOT = ROOT / ".local"
CONTENT_ROOT = PRIVATE_ROOT / "content"


class CliError(ValueError):
    """Only a fixed local error code is printable."""


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise CliError("INVALID_ARGUMENTS")


def _content_path(path):
    path = path if path.is_absolute() else ROOT / path
    try:
        relative = path.relative_to(CONTENT_ROOT)
        if not relative.parts or ".." in relative.parts or any(parent.is_symlink() for parent in (path, *path.parents)):
            raise ValueError()
    except ValueError:
        raise CliError("CONTENT_PATH_NOT_PRIVATE") from None
    return path


def _unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CliError("INVALID_REVIEW_JSON")
        result[key] = value
    return result


def _invalid_constant(_):
    raise CliError("INVALID_REVIEW_JSON")


def _read_review(path):
    try:
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode):
            raise CliError("REVIEW_FILE_NOT_PRIVATE")
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
        with os.fdopen(descriptor, "rb") as stream:
            current = os.fstat(stream.fileno())
            if (not stat.S_ISREG(current.st_mode) or (before.st_dev, before.st_ino) != (current.st_dev, current.st_ino)
                    or os.name == "posix" and (current.st_uid != os.geteuid() or current.st_mode & 0o077)):
                raise CliError("REVIEW_FILE_NOT_PRIVATE")
            raw = stream.read(MAX_REVIEW_BYTES + 1)
        if len(raw) > MAX_REVIEW_BYTES:
            raise CliError("REVIEW_FILE_TOO_LARGE")
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique, parse_constant=_invalid_constant)
    except CliError:
        raise
    except (OSError, ValueError, TypeError, RecursionError):
        raise CliError("REVIEW_FILE_UNREADABLE") from None


def main(argv=None):
    try:
        parser = PrivateParser(description=__doc__)
        source = parser.add_mutually_exclusive_group(required=True)
        source.add_argument("--review", type=Path, help="Private review JSON under .local/content")
        source.add_argument("--evidence-from-bundle", type=Path, help="Export generation evidence from an existing reviewed bundle")
        parser.add_argument("--allow-device-id", action="append", help="Explicit registered device allowed to use exported evidence (repeatable)")
        parser.add_argument("--output", type=Path, required=True, help="New private bundle .json under .local/content")
        args = parser.parse_args(argv)
        if bool(args.evidence_from_bundle) != bool(args.allow_device_id):
            raise CliError("EVIDENCE_REQUIRES_EXPLICIT_DEVICE_IDS")
        input_path, output_path = _content_path(args.review or args.evidence_from_bundle), _content_path(args.output)
        if output_path.exists():
            raise CliError("OUTPUT_ALREADY_EXISTS")
        if args.evidence_from_bundle:
            document = build_evidence_registry(_read_review(input_path), args.allow_device_id)
            EvidenceRegistry(document)
            names = ("sources", "facts")
        else:
            document = build_reviewed_bundle(_read_review(input_path))
            names = ("sources", "facts", "places", "stories", "versions")
        _, digest = write_private_candidates(output_path.parent, output_path.name, document, private_root=PRIVATE_ROOT)
        result = {"status": "EXPORTED" if args.evidence_from_bundle else "BUILT", "sha256": digest,
                  "counts": {name: len(document[name]) for name in names},
                  "outputSaved": True, "networkRequests": 0}
        if not args.evidence_from_bundle:
            result["formatVersion"] = document["formatVersion"]
        exit_code = 0
    except (CliError, ContentBundleError, PublicDataError) as error:
        result, exit_code = {"status": str(error), "outputSaved": False}, 1
    except Exception:
        result, exit_code = {"status": "BUILD_FAILED", "outputSaved": False}, 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
