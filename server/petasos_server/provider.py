"""One explicitly configured OpenRouter route; no automatic provider/model fallback."""
import json
import math
import time
from dataclasses import dataclass
from datetime import timezone
from email.utils import parsedate_to_datetime

import httpx

from .contracts import Failure, strict_json
from .prompts import system_prompt
from .provider_schema import provider_schema

# The prompt text lives in server/prompts/ (see prompts.py); the composed string is unchanged.
PROMPT_VERSION, SYSTEM_PROMPT = system_prompt()


def retry_after_ms(value):
    """Preserve a provider's actual delay; absent/invalid Retry-After remains unknown."""
    if value is None:
        return None
    try:
        if value.isdigit():
            seconds = int(value)
        else:
            date = parsedate_to_datetime(value)
            if date.tzinfo is None:
                date = date.replace(tzinfo=timezone.utc)
            seconds = max(0, date.timestamp() - time.time())
        return min(2**63 - 1, max(0, int(seconds * 1000)))
    except (ValueError, TypeError, OverflowError):
        return None


@dataclass(frozen=True, repr=False)
class ProviderResult:
    result: dict
    model_id: str
    provider: str
    input_tokens: int | None = None
    output_tokens: int | None = None


class UnavailableProvider:
    async def generate(self, task, payload, evidence, schema):
        raise Failure("UPSTREAM_UNAVAILABLE", 503, True)

    async def close(self):
        pass


@dataclass(frozen=True, repr=False)
class OpenRouterConfig:
    api_key: str
    model: str
    provider_slug: str
    accepted_provider_names: tuple[str, ...]
    privacy_reviewed_until: float
    account_content_logging_disabled: bool
    max_output_tokens: int = 4096
    accepted_model_ids: tuple[str, ...] = ()
    temperature: float | None = None   # None = not sent (the server's requests stay as they were)

    def validate(self):
        if (not isinstance(self.api_key, str) or not self.api_key
            or not isinstance(self.model, str) or not self.model or len(self.model) > 256
            or not isinstance(self.provider_slug, str) or not self.provider_slug or len(self.provider_slug) > 256
            or not isinstance(self.accepted_provider_names, tuple) or not self.accepted_provider_names
            or any(not isinstance(x, str) or not x or len(x) > 256 for x in self.accepted_provider_names)
            or not isinstance(self.accepted_model_ids, tuple)
            or len(self.accepted_model_ids) > 16
            or any(not isinstance(x, str) or not x or len(x) > 256 or x != x.strip()
                   or any(character.isspace() or ord(character) < 32 for character in x)
                   for x in self.accepted_model_ids)
            or len(set(self.accepted_model_ids)) != len(self.accepted_model_ids)
            or self.account_content_logging_disabled is not True
            or type(self.privacy_reviewed_until) not in (float, int) or not math.isfinite(self.privacy_reviewed_until)
            or type(self.max_output_tokens) is not int or not 1 <= self.max_output_tokens <= 8192):
            raise Failure("UPSTREAM_UNAVAILABLE", 503, False)


class OpenRouterProvider:
    def __init__(self, config, transport=None):
        self.config = config
        self.client = httpx.AsyncClient(timeout=httpx.Timeout(12.0), follow_redirects=False,
                                        trust_env=False, transport=transport)

    async def close(self):
        await self.client.aclose()

    async def generate(self, task, payload, evidence, schema, system_prompt=None, temperature=None):
        """system_prompt / temperature are only for offline tools (pack builds, story lab); the server sends neither."""
        self.config.validate()  # Structural config checks fail before any outbound request.
        c = self.config
        body = {"model": c.model, "stream": False, "max_tokens": c.max_output_tokens,
                "provider": {"only": [c.provider_slug], "order": [c.provider_slug], "allow_fallbacks": False,
                             "require_parameters": True, "data_collection": "deny", "zdr": True},
                "messages": [{"role": "system", "content": system_prompt or SYSTEM_PROMPT},
                             {"role": "user", "content": json.dumps({"task": task, "payload": payload,
                                "evidence": evidence.model_input()}, ensure_ascii=False, allow_nan=False)}],
                "response_format": {"type": "json_schema", "json_schema": {
                    "name": "petasos_" + task, "strict": True, "schema": provider_schema(c.model, schema)}}}
        if temperature is None:
            temperature = c.temperature
        if temperature is not None:
            body["temperature"] = float(temperature)
        try:
            # Fixed HTTPS origin, no redirects, environment proxies, tools, or URL retrieval.
            async with self.client.stream("POST", "https://openrouter.ai/api/v1/chat/completions",
                    headers={"Authorization": "Bearer " + c.api_key, "Content-Type": "application/json"}, json=body) as response:
                if response.status_code != 200:
                    if response.status_code == 429:
                        raise Failure("RATE_LIMITED", 429, True, retry_after_ms(response.headers.get("retry-after")))
                    raise Failure("UPSTREAM_UNAVAILABLE", 503, response.status_code >= 500)
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > 512 * 1024:
                        raise Failure("INVALID_MODEL_OUTPUT", 422)
                data = strict_json(bytes(raw))
            if (data.get("model") not in (c.model, *c.accepted_model_ids)
                or data.get("provider") not in c.accepted_provider_names):
                raise Failure("INVALID_MODEL_OUTPUT", 422)
            choice = data["choices"][0]
            message = choice["message"]
            if choice.get("finish_reason") != "stop" or message.get("tool_calls") or message.get("refusal"):
                raise Failure("INVALID_MODEL_OUTPUT", 422)
            result = strict_json(message["content"])
            usage = data.get("usage") or {}
            counts = [usage.get("prompt_tokens"), usage.get("completion_tokens")]
            if any(x is not None and (type(x) is not int or x < 0) for x in counts):
                raise Failure("INVALID_MODEL_OUTPUT", 422)
            return ProviderResult(result, data["model"], data["provider"], *counts)
        except httpx.TimeoutException:
            raise Failure("GENERATION_TIMEOUT", 504, True) from None
        except httpx.HTTPError:
            raise Failure("UPSTREAM_UNAVAILABLE", 503, True) from None
        except (KeyError, IndexError, TypeError, AttributeError, ValueError):
            raise Failure("INVALID_MODEL_OUTPUT", 422) from None
        except Failure as error:
            if error.code == "INVALID_REQUEST":
                raise Failure("INVALID_MODEL_OUTPUT", 422) from None
            raise
