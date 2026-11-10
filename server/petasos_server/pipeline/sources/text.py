"""Shared text cleaning and sentence splitting for official descriptions."""
import html
import re

_TAG = re.compile(r"<[^>]+>")
_SPACE = re.compile(r"[ \t\r\f\v 　]+")
# Korean descriptions end sentences with 다. / 요. / 음. etc.; split after . ! ? followed by space/newline.
_SENTENCE = re.compile(r"(?<=[.!?。])\s+")


def clean(value):
    value = html.unescape(_TAG.sub(" ", value or ""))
    value = value.replace("​", "").replace("­", "")
    lines = [_SPACE.sub(" ", line).strip() for line in value.splitlines()]
    return "\n".join(line for line in lines if line)


def sentences(value, *, minimum=12, maximum=300):
    """Whole sentences only: fragments, headings and over-long run-ons are dropped, never cut."""
    out = []
    for paragraph in clean(value).split("\n"):
        for sentence in _SENTENCE.split(paragraph):
            sentence = sentence.strip()
            if minimum <= len(sentence) <= maximum and sentence[-1] in ".!?。" and not sentence.startswith(("※", "*", "-", "Ο", "○")):
                out.append(sentence)
    return out


def spoken_name(name):
    """Display names carry parenthetical notes ("백범광장(백범 김구선생 동상)"); speech drops them."""
    return re.sub(r"\s*[\(（][^)）]*[\)）]", "", name).strip() or name.strip()
