"""Read a private, literal credentials file without shell expansion or execution.

Nonempty values already in the process environment take precedence over this file.
Comments occupy a whole line; quotes only delimit a value, with no escape syntax.
"""
import os
import re
import stat
from collections.abc import Mapping
from pathlib import Path

ALLOWED_KEYS = frozenset({
    "PETASOS_OPENROUTER_API_KEY",
    "PETASOS_DATA_GO_KR_SERVICE_KEY",
    "PETASOS_ODII_SERVICE_KEY",
    "PETASOS_TOURAPI_SERVICE_KEY",
    "PETASOS_VWORLD_API_KEY",
    "PETASOS_VWORLD_DOMAIN",
})
MAX_ENV_BYTES = 64 * 1024


class EnvFileError(ValueError):
    """A fixed error code; never include a path, file line, or credential."""


def read_private_env(path: str | Path) -> dict[str, str]:
    """Read only an owned regular file with no group/other permissions on POSIX."""
    try:
        before = os.lstat(path)
        if not stat.S_ISREG(before.st_mode):
            raise EnvFileError("ENV_FILE_NOT_REGULAR")
        flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0)
        descriptor = os.open(path, flags)
        with os.fdopen(descriptor, "rb") as stream:
            info = os.fstat(stream.fileno())
            if (not stat.S_ISREG(info.st_mode)
                    or (before.st_dev, before.st_ino) != (info.st_dev, info.st_ino)):
                raise EnvFileError("ENV_FILE_NOT_REGULAR")
            if os.name == "posix" and (
                    info.st_uid != os.geteuid()
                    or info.st_mode & 0o077
                    or not info.st_mode & stat.S_IRUSR):
                raise EnvFileError("ENV_FILE_NOT_PRIVATE")
            raw = stream.read(MAX_ENV_BYTES + 1)
    except EnvFileError:
        raise
    except (OSError, ValueError, TypeError):
        raise EnvFileError("ENV_FILE_UNREADABLE") from None
    if len(raw) > MAX_ENV_BYTES:
        raise EnvFileError("ENV_FILE_TOO_LARGE")
    try:
        content = raw.decode("utf-8")
    except UnicodeError:
        raise EnvFileError("ENV_FILE_INVALID_ENCODING") from None
    if "\x00" in content:
        raise EnvFileError("ENV_FILE_INVALID_FORMAT")
    values = {}
    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise EnvFileError("ENV_FILE_INVALID_FORMAT")
        if key not in ALLOWED_KEYS:
            raise EnvFileError("ENV_FILE_UNKNOWN_KEY")
        if key in values:
            raise EnvFileError("ENV_FILE_DUPLICATE_KEY")
        if value[:1] in ("'", '"'):
            if len(value) < 2 or value[-1] != value[0]:
                raise EnvFileError("ENV_FILE_INVALID_FORMAT")
            value = value[1:-1]
        elif value[-1:] in ("'", '"'):
            raise EnvFileError("ENV_FILE_INVALID_FORMAT")
        values[key] = value
    return values


def load_environment(path: str | Path | None, environ: Mapping[str, str] | None = None) -> dict[str, str]:
    """Return a copy, preserving nonempty environment values without global mutation."""
    environment = dict(os.environ if environ is None else environ)
    if path is not None:
        for key, value in read_private_env(path).items():
            if not environment.get(key):
                environment[key] = value
    return environment
