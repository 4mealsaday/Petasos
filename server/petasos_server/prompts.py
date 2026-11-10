"""Prompt files under server/prompts/. Each file starts with one header comment that names its version.

The runtime system prompt is the concatenation guide_persona({tasks} = story + answer + summary_resume).
It must stay byte-identical to the pre-split constant (see tests/test_prompts.py) until a deliberate
version bump; PROMPT_VERSION is what the server reports and what packs record.
"""
from functools import lru_cache
from pathlib import Path
import re

DIRECTORY = Path(__file__).resolve().parents[1] / "prompts"
_HEADER = re.compile(r"<!-- prompt: (?P<name>[a-z_]+) \| version: (?P<version>[A-Za-z0-9._-]+) \|[^\n]*-->\n")


@lru_cache(maxsize=None)
def load(name):
    """Returns (version, body). The body is the file after its header line, unchanged."""
    raw = (DIRECTORY / f"{name}.md").read_text(encoding="utf-8")
    match = _HEADER.match(raw)
    if match is None or match.group("name") != name:
        raise ValueError("INVALID_PROMPT_FILE")
    return match.group("version"), raw[match.end():]


def system_prompt():
    version, persona = load("guide_persona")
    tasks = "".join(load(name)[1] for name in ("story", "answer", "summary_resume"))
    versions = {version, *(load(name)[0] for name in ("story", "answer", "summary_resume"))}
    if len(versions) != 1 or persona.count("{tasks}") != 1:
        raise ValueError("INVALID_PROMPT_FILE")
    return version, persona.replace("{tasks}", tasks)


def landmark_intro_prompt():
    return load("landmark_intro")
