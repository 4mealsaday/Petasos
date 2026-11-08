import asyncio
import copy
import hashlib
import hmac
import json
import math
import time
import uuid
from collections import defaultdict, deque
from dataclasses import dataclass

from jsonschema import Draft202012Validator

from .contracts import Failure, META_KEYS, VALIDATORS, model_schema
from .evidence import validate_semantics
from .provider import PROMPT_VERSION


@dataclass(repr=False)
class Entry:
    fingerprint: bytes
    future: asyncio.Future
    evidence_fingerprint: bytes
    task: asyncio.Task | None = None
    cancelled: bool = False
    evidence_denial: tuple | None = None
    expires_at: float | None = None
    retry_at: float | None = None


class GenerationService:
    def __init__(self, evidence, provider, fingerprint_key, clock=time.monotonic, timeout=14.0, ttl=60.0):
        if len(fingerprint_key) < 32 or not 0 < ttl <= 60 or not 0 < timeout <= 60:
            raise ValueError("INVALID_SERVICE_CONFIGURATION")
        self.evidence, self.provider, self.key = evidence, provider, fingerprint_key
        self.clock, self.timeout, self.ttl = clock, timeout, ttl
        self.entries, self.active, self.starts = {}, defaultdict(int), defaultdict(deque)
        self.lock = asyncio.Lock()
        self.cleanup_handles = set()

    def _prune(self):
        now = self.clock()
        for key, entry in list(self.entries.items()):
            if entry.expires_at is not None and entry.expires_at <= now:
                del self.entries[key]
        for owner, times in list(self.starts.items()):
            while times and times[0] <= now - 60:
                times.popleft()
            if not times:
                del self.starts[owner]

    def _expire(self, key, entry):
        if self.entries.get(key) is entry:
            self.entries.pop(key, None)

    def _check_evidence(self, owner, request, entry):
        """A revoked/stale request stays denied even if the grants are later restored."""
        if entry.evidence_denial is not None:
            status, body = entry.evidence_denial
            raise Failure(body["code"], status)
        try:
            current = self.evidence.resolve(owner, request)
            if not hmac.compare_digest(current.fingerprint(), entry.evidence_fingerprint):
                raise Failure("STALE_CONTEXT", 409)
        except Failure as error:
            entry.evidence_denial = (error.status, error.body(request["requestId"]))
            entry.retry_at = None
            # Suppress both completed cache results and all currently joined waiters.
            if entry.future.done():
                entry.future = asyncio.get_running_loop().create_future()
            entry.future.set_result(entry.evidence_denial)
            raise

    async def generate(self, owner, request):
        fingerprint = hmac.digest(self.key, json.dumps(request, sort_keys=True, ensure_ascii=False,
                                  separators=(",", ":"), allow_nan=False).encode(), "sha256")
        key = (owner, request["requestId"])
        async with self.lock:
            self._prune()
            entry = self.entries.get(key)
            if entry:
                if not hmac.compare_digest(entry.fingerprint, fingerprint):
                    raise Failure("IDEMPOTENCY_CONFLICT", 409)
                if not entry.cancelled:
                    self._check_evidence(owner, request, entry)
                # Transient failures must not consume the app's retry by replaying an error.
                # The fingerprint still binds all attempts; successes and cancellation never retry.
                if not entry.cancelled and entry.retry_at is not None and entry.retry_at <= self.clock():
                    entry = None
            if entry is None:
                if self.active[owner] >= 2:
                    raise Failure("RATE_LIMITED", 429, True, 1000)
                starts = self.starts[owner]
                if len(starts) >= 60:
                    raise Failure("RATE_LIMITED", 429, True, max(1, int((starts[0] + 60 - self.clock()) * 1000)))
                context = self.evidence.resolve(owner, request)
                starts.append(self.clock())
                self.active[owner] += 1
                entry = Entry(fingerprint, asyncio.get_running_loop().create_future(), context.fingerprint())
                self.entries[key] = entry
                entry.task = asyncio.create_task(self._run(key, entry, copy.deepcopy(request), context))
            future = entry.future
        # A disconnected waiter cannot cancel another identical request's shared operation.
        status, body = await asyncio.shield(future)
        async with self.lock:
            # An already-awakened waiter may hold a success from the old Future. Recheck
            # immediately before delivery, including cached results and retryable errors.
            if entry.cancelled:
                return 409, Failure("CANCELLED", 409).body(request["requestId"])
            self._check_evidence(owner, request, entry)
            body = copy.deepcopy(body)
            if body.get("retryable") and entry.retry_at is not None and body.get("retryAfterMs") is not None:
                body["retryAfterMs"] = max(0, math.ceil((entry.retry_at - self.clock()) * 1000))
        return status, body

    async def _run(self, key, entry, request, evidence):
        try:
            schema = model_schema(request["task"])
            if entry.cancelled:
                raise Failure("CANCELLED", 409)
            self._check_evidence(key[0], request, entry)
            async with asyncio.timeout(self.timeout):
                generated = await self.provider.generate(request["task"], request["payload"], evidence, schema)
            self._check_evidence(key[0], request, entry)
            result = copy.deepcopy(generated.result)
            if not Draft202012Validator(schema).is_valid(result):
                raise Failure("INVALID_MODEL_OUTPUT", 422)
            validate_semantics(request, result, evidence)
            if request["task"] in ("story", "answer"):
                for paragraph in result["paragraphs"]:
                    paragraph["paragraphKey"] = "p-" + uuid.uuid4().hex
            if request["task"] == "story":
                result["storyId"] = request["payload"]["storyId"]
                result["storyVersionId"] = "sv-" + uuid.uuid4().hex
            body = {key: copy.deepcopy(request[key]) for key in META_KEYS}
            body.update(status="ok", result=result, metadata={"modelId": generated.model_id,
                        "provider": generated.provider, "promptVersion": PROMPT_VERSION,
                        "inputTokens": generated.input_tokens, "outputTokens": generated.output_tokens})
            if not VALIDATORS["SuccessResponse"].is_valid(body):
                raise Failure("INVALID_MODEL_OUTPUT", 422)
            outcome = (200, body)
        except TimeoutError:
            outcome = (504, Failure("GENERATION_TIMEOUT", 504, True).body(request["requestId"]))
        except asyncio.CancelledError:
            outcome = (409, Failure("CANCELLED", 409).body(request["requestId"]))
        except Failure as error:
            outcome = (error.status, error.body(request["requestId"]))
        except Exception:
            # No provider traceback/body or exception string enters application logs.
            outcome = (503, Failure("UPSTREAM_UNAVAILABLE", 503, True).body(request["requestId"]))
        async with self.lock:
            self.active[key[0]] -= 1
            if self.active[key[0]] == 0:
                del self.active[key[0]]
            if entry.cancelled:
                outcome = (409, Failure("CANCELLED", 409).body(request["requestId"]))
            else:
                # A provider error must not bypass revocation or enable a later retry
                # with a snapshot that no longer represents the current reviewed input.
                try:
                    self._check_evidence(key[0], request, entry)
                except Failure as error:
                    outcome = (error.status, error.body(request["requestId"]))
                if outcome[1].get("retryable"):
                    entry.retry_at = self.clock() + (outcome[1].get("retryAfterMs") or 0) / 1000
            entry.expires_at = self.clock() + self.ttl
            if not entry.future.done():
                entry.future.set_result(outcome)
            # Eager eviction also clears private result bodies when no later request arrives.
            loop = asyncio.get_running_loop()
            handle = None
            def expire():
                self.cleanup_handles.discard(handle)
                self._expire(key, entry)
            handle = loop.call_later(self.ttl, expire)
            self.cleanup_handles.add(handle)

    async def cancel(self, owner, request_id):
        async with self.lock:
            self._prune()
            entry = self.entries.get((owner, request_id))
            if entry is None:
                raise Failure("NOT_FOUND", 404)
            entry.cancelled = True
            cancelled = (409, Failure("CANCELLED", 409).body(request_id))
            # Completed cached results must also be suppressed after a cancellation.
            if entry.future.done():
                entry.future = asyncio.get_running_loop().create_future()
            if not entry.future.done():
                entry.future.set_result(cancelled)
            # Do not cancel a not-yet-started Task: its cleanup must decrement active counts.
            # The provider may finish/bill; the cancelled flag always suppresses delivery.

    async def close(self):
        tasks = []
        async with self.lock:
            for entry in self.entries.values():
                entry.cancelled = True
                if entry.task and not entry.task.done():
                    tasks.append(entry.task)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        for handle in self.cleanup_handles:
            handle.cancel()
        self.cleanup_handles.clear()
        self.entries.clear()
        await self.provider.close()
