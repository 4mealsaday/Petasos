import asyncio
import json
import re

from .contracts import Failure, ID, strict_json, validate_request

MAX_BODY = 256 * 1024
CANCEL = re.compile(r"/v1/generations/([A-Za-z0-9][A-Za-z0-9._:-]{0,127})/cancel\Z")


class GenerationApp:
    def __init__(self, tokens, service):
        self.tokens, self.service = tokens, service

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            while True:
                message = await receive()
                if message["type"] == "lifespan.startup":
                    await send({"type": "lifespan.startup.complete"})
                elif message["type"] == "lifespan.shutdown":
                    await self.service.close()
                    await send({"type": "lifespan.shutdown.complete"})
                    return
        if scope["type"] != "http":
            return
        request_id = None
        try:
            if scope.get("scheme") != "https":
                raise Failure("FORBIDDEN", 403)
            path, method = scope["path"], scope["method"]
            if path == "/v1/health" and method == "GET":
                await self.respond(send, 200, {"status": "ok", "schemaVersion": "0.8"})
                return
            headers = {}
            for name, value in scope.get("headers", []):
                name = name.lower()
                if name in (b"authorization", b"content-length", b"content-type") and name in headers:
                    raise Failure("INVALID_REQUEST")
                headers[name] = value
            try:
                authorization = headers.get(b"authorization", b"").decode("ascii")
            except UnicodeError:
                raise Failure("UNAUTHORIZED", 401) from None
            owner = self.tokens.authenticate(authorization)
            cancel = CANCEL.fullmatch(path)
            if method != "POST" or (path != "/v1/generations" and not cancel):
                raise Failure("NOT_FOUND", 404)
            if b"content-length" in headers:
                try:
                    length = int(headers[b"content-length"])
                    if length < 0 or length > MAX_BODY:
                        raise ValueError()
                except ValueError:
                    raise Failure("INVALID_REQUEST") from None
            async with asyncio.timeout(10):
                raw = await self.read_body(receive)
            if cancel:
                request_id = cancel.group(1)
                if raw:
                    raise Failure("INVALID_REQUEST")
                await self.service.cancel(owner, request_id)
                await self.respond(send, 204, None)
                return
            content_type = headers.get(b"content-type", b"").split(b";", 1)[0].strip().lower()
            if content_type != b"application/json":
                raise Failure("INVALID_REQUEST")
            value = strict_json(raw)
            if isinstance(value, dict) and isinstance(value.get("requestId"), str) and ID.fullmatch(value["requestId"]):
                request_id = value["requestId"]
            validate_request(value)
            try:
                json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
            except (ValueError, UnicodeError):
                raise Failure("INVALID_REQUEST") from None
            status, body = await self.service.generate(owner, value)
            await self.respond(send, status, body)
        except Failure as error:
            await self.respond(send, error.status, error.body(request_id))
        except (TimeoutError, RecursionError):
            await self.respond(send, 400, Failure("INVALID_REQUEST").body(request_id))
        except ConnectionError:
            return
        except Exception:
            await self.respond(send, 503, Failure("UPSTREAM_UNAVAILABLE", 503, True).body(request_id))

    @staticmethod
    async def read_body(receive):
        raw = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                raise ConnectionError()
            raw.extend(message.get("body", b""))
            if len(raw) > MAX_BODY:
                raise Failure("INVALID_REQUEST")
            if not message.get("more_body", False):
                return bytes(raw)

    @staticmethod
    async def respond(send, status, body):
        raw = b"" if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
        headers = [(b"content-type", b"application/json; charset=utf-8"), (b"cache-control", b"no-store"),
                   (b"content-length", str(len(raw)).encode()), (b"x-content-type-options", b"nosniff")]
        if body and body.get("retryAfterMs") is not None:
            headers.append((b"retry-after", str(max(1, (body["retryAfterMs"] + 999) // 1000)).encode()))
        await send({"type": "http.response.start", "status": status, "headers": headers})
        await send({"type": "http.response.body", "body": raw})
