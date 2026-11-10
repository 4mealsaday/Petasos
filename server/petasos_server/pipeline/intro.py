"""Landmark intro: 1-2 spoken sentences read after "{방향} 창밖으로 {이름}이 있어요." (6장).

A model may paraphrase only facts whose license allows it (KOGL-1). Otherwise -- or when the model is
unavailable or its output fails the checks -- the intro quotes one source sentence verbatim.
Either way the intro names the exact fact ids it relies on, checked the same way the server checks answers.
"""
import asyncio
import json
import re

from ..contracts import Failure
from ..evidence import EvidenceContext
from ..prompts import landmark_intro_prompt

MAX_INTRO_CHARS = 130
# The app informs; it never tells the driver to look (6장).
INSTRUCTIONS = re.compile(r"(보세요|보시|돌아보|살펴|확인해|바라보|방문해|들러)")
# Operating state is seasonal / time-of-day dependent: never claim something is happening right now.
NOW = re.compile(r"(지금|오늘|매일|가동|운영 중|운영중|뿜어|솟아)")
SCHEMA = {"type": "object", "additionalProperties": False, "required": ["intro", "factRevisionIds"],
          "properties": {"intro": {"type": "string", "minLength": 1, "maxLength": 200},
                         "factRevisionIds": {"type": "array", "minItems": 1, "maxItems": 6, "items": {"type": "string"}}}}


def validate_intro(result, evidence):
    """Same rule as evidence.validate_semantics for answers: every cited id must be supplied evidence."""
    intro, ids = result.get("intro"), result.get("factRevisionIds")
    if (not isinstance(intro, str) or not intro.strip() or len(intro) > MAX_INTRO_CHARS or INSTRUCTIONS.search(intro) or NOW.search(intro)
            or not isinstance(ids, list) or not ids or len(set(ids)) != len(ids) or not set(ids) <= evidence.facts.keys()):
        raise Failure("INVALID_MODEL_OUTPUT", 422)
    return intro.strip(), ids


# A quoted sentence must stand alone: no leading connective that points at a sentence we did not read.
CONNECTIVES = ("다만", "그러나", "하지만", "또한", "그리고", "이후", "이때", "이 ", "그 ", "따라서", "한편")


def quote_intro(facts):
    """Verbatim: the first source sentence that fits; facts are rows {factRevisionId, statement}."""
    for fact in facts:
        if 15 <= len(fact["statement"]) <= MAX_INTRO_CHARS and not fact["statement"].startswith(CONNECTIVES):
            return fact["statement"], [fact["factRevisionId"]], "quote"
    return None


class IntroWriter:
    def __init__(self, provider=None):
        self.provider = provider
        self.version, self.prompt = landmark_intro_prompt()

    def write_many(self, requests):
        """requests: [(name, category, facts, allows_generation)] -> results in order. One event loop for all."""
        async def run():
            try:
                return [await self.write(*request) for request in requests]
            finally:
                if self.provider is not None:
                    await self.provider.close()
        return asyncio.run(run())

    async def write(self, name, category, facts, allows_generation, note=None):
        """facts: [{factRevisionId, factId, statement, sourceRevisionId}] -> (intro, factIds, method) or None."""
        if allows_generation and self.provider is not None:
            evidence = EvidenceContext(
                sources={f["sourceRevisionId"]: {"sourceRevisionId": f["sourceRevisionId"], "text": ""} for f in facts},
                facts={f["factRevisionId"]: {"factRevisionId": f["factRevisionId"], "factId": f["factId"], "kind": "fact",
                                             "text": f["statement"], "sourceRevisionIds": [f["sourceRevisionId"]]} for f in facts})
            payload = {"landmarkName": name, "category": category}
            if note:
                payload["editorNote"] = note
            for _ in range(2):
                try:
                    generated = await self.provider.generate("landmark_intro", payload, evidence, SCHEMA, system_prompt=self.prompt)
                    intro, ids = validate_intro(generated.result, evidence)
                    return intro, ids, "model"
                except Failure:
                    continue
        return quote_intro(facts)


def provider_from_config(config_path, environment):
    """The same OpenRouter route the server uses (.local/server/config.json + PETASOS_OPENROUTER_API_KEY)."""
    from ..provider import OpenRouterConfig, OpenRouterProvider
    config = json.loads(open(config_path, encoding="utf-8").read()).get("provider")
    if not config or not environment.get("PETASOS_OPENROUTER_API_KEY"):
        return None
    return OpenRouterProvider(OpenRouterConfig(
        api_key=environment["PETASOS_OPENROUTER_API_KEY"], model=config["model"], provider_slug=config["providerSlug"],
        accepted_provider_names=tuple(config["acceptedProviderNames"]), privacy_reviewed_until=config["privacyReviewedUntil"],
        account_content_logging_disabled=config["accountContentLoggingDisabled"],
        max_output_tokens=min(1024, config.get("maxOutputTokens", 1024)),
        accepted_model_ids=tuple(config.get("acceptedModelIds", []))))
