import hashlib
import hmac
import json
import math
import re
import time
from dataclasses import dataclass
from pathlib import Path

from .contracts import Failure, ID


@dataclass(frozen=True, repr=False)
class DeviceToken:
    device_id: str
    token_hash: str
    expires_at: float
    revoked: bool = False


class TokenRegistry:
    """Only token digests are retained. Replacing the registry permits immediate revocation."""
    def __init__(self, tokens=(), clock=time.time, path=None):
        self.tokens = tuple(tokens)
        self.clock, self.path = clock, Path(path) if path else None

    def authenticate(self, authorization):
        if not authorization or not authorization.startswith("Bearer "):
            raise Failure("UNAUTHORIZED", 401)
        token = authorization[7:]
        if not re.fullmatch(r"[A-Za-z0-9_-]{43,256}", token):
            raise Failure("UNAUTHORIZED", 401)
        digest = hashlib.sha256(token.encode("ascii")).hexdigest()
        tokens = self.tokens
        if self.path:
            try:
                rows = json.loads(self.path.read_text())
                tokens = tuple(DeviceToken(r["deviceId"], r["tokenHash"], r["expiresAt"], r["revoked"]) for r in rows)
                if any(not isinstance(r.device_id, str) or not ID.fullmatch(r.device_id)
                       or not isinstance(r.token_hash, str) or not re.fullmatch("[a-f0-9]{64}", r.token_hash)
                       or type(r.expires_at) not in (int, float) or not math.isfinite(r.expires_at)
                       or type(r.revoked) is not bool for r in tokens):
                    raise ValueError()
                if len({r.token_hash for r in tokens}) != len(tokens):
                    raise ValueError()
            except (OSError, ValueError, TypeError, KeyError):
                raise Failure("UNAUTHORIZED", 401) from None
        found = None
        for row in tokens:
            if hmac.compare_digest(row.token_hash, digest):
                found = row
        if found is None or found.revoked or found.expires_at <= self.clock():
            raise Failure("UNAUTHORIZED", 401)
        return found.device_id
