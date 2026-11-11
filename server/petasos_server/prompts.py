"""Prompt files under server/prompts/. Each file starts with one header comment that names its version and the
rule "내용을 바꾸면 version을 올린다".

Two prompt sets:
  * conversation (answer / summary / resume, and the legacy story prompt): rules({tasks} = story_evidence + answer +
    summary_resume). It must stay byte-identical to the pre-split constant (tests/test_pack.py) until a deliberate
    version bump; PROMPT_VERSION is what the server reports.
  * story guide (CONTENT_PIPELINE.md 9장): rules({tasks} = story_evidence) + guide_persona (말투) + story (구성) +
    examples/. guide_persona, story and every example share one version; that version is the story promptVersion.
"""
from functools import lru_cache
from pathlib import Path
import re

DIRECTORY = Path(__file__).resolve().parents[1] / "prompts"
_HEADER = re.compile(r"<!-- prompt: (?P<name>[a-z0-9_]+) \| version: (?P<version>[A-Za-z0-9._-]+) \|[^\n]*-->\n")


@lru_cache(maxsize=None)
def load(name):
    """Returns (version, body). The body is the file after its header line, unchanged. name may be 'examples/<file>'."""
    path = DIRECTORY / f"{name}.md"
    raw = path.read_text(encoding="utf-8")
    match = _HEADER.match(raw)
    if match is None or (match.group("name") != name and not name.startswith("examples/")):
        raise ValueError("INVALID_PROMPT_FILE")
    return match.group("version"), raw[match.end():]


def example_names():
    return tuple(f"examples/{path.stem}" for path in sorted((DIRECTORY / "examples").glob("[0-9]*.md")))


def system_prompt():
    version, rules = load("rules")
    names = ("story_evidence", "answer", "summary_resume")
    tasks = "".join(load(name)[1] for name in names)
    versions = {version, *(load(name)[0] for name in names)}
    if len(versions) != 1 or rules.count("{tasks}") != 1:
        raise ValueError("INVALID_PROMPT_FILE")
    return version, rules.replace("{tasks}", tasks)


def story_prompt():
    """-> (version, text) of the story guide prompt set."""
    rules_version, rules = load("rules")
    evidence_version, evidence = load("story_evidence")
    version, persona = load("guide_persona")
    story_version, story = load("story")
    examples = [load(name) for name in example_names()]
    if (rules_version != evidence_version or story_version != version or any(v != version for v, _ in examples)
            or rules.count("{tasks}") != 1):
        raise ValueError("INVALID_PROMPT_FILE")
    parts = [rules.replace("{tasks}", evidence).rstrip(), persona.strip(), story.strip()]
    if examples:
        parts.append("## 말투 예시 (사실은 예시의 근거 사실에만 기댄다. 문장을 그대로 베끼지 않는다)\n\n"
                     + "\n\n".join(body.strip() for _, body in examples))
    return version, "\n\n".join(parts) + "\n"


def story_output_typed():
    """True when the current story.md asks for sentence-level types (paragraphs[].sentences with fact/bridge)."""
    return "paragraphs[].sentences" in load("story")[1]


def landmark_intro_prompt():
    return load("landmark_intro")
