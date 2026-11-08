"""Pinned official Odii/TourAPI operation and query contracts.

The JSON files remain faithful Swagger snapshots. Semantic constraints written
in their descriptions are applied here without adding invented schema defaults
or enums. This module never reads a credential or performs a network request.
"""

from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation
from functools import lru_cache
import json
import math
from pathlib import Path
import re


_SOURCES = {
    "odii": "odii.openapi.json",
    "tourapi": "tourapi.openapi.json",
}
_ERROR_CODES = frozenset({
    "UNKNOWN_SERVICE", "UNKNOWN_OPERATION", "INVALID_PARAMETER",
    "MISSING_PARAMETER", "INVALID_CONTRACT",
})
# Only known public names may be attached to an error. Never echo an unknown
# caller-provided key, which may itself contain a credential or other input.
_SAFE_PARAMETER_NAMES = frozenset({
    "MobileOS", "MobileApp", "serviceKey", "_type", "numOfRows", "pageNo",
    "langCode", "mapX", "mapY", "radius", "keyword", "tid", "tlid",
    "syncStatus", "modifiedtime", "areaCode", "sigunguCode", "cat1", "cat2",
    "cat3", "contentId", "contentTypeId", "arrange", "eventStartDate",
    "eventEndDate", "imageYN", "lDongRegnCd", "lDongSignguCd", "lDongListYn",
    "lclsSystm1", "lclsSystm2", "lclsSystm3", "lclsSystmListYn", "showflag",
    "oldContentid",
})
_NUMERIC_QUERY_NAMES = frozenset({"numOfRows", "pageNo", "mapX", "mapY", "radius"})
_NUMBER = re.compile(r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?\Z")
_DATE = re.compile(r"[0-9]{8}\Z")
# This is a parsing bound, not a claimed provider limit. Row-count/network
# budgets belong to the calling client; no undocumented maximum rows is used.
_MAX_NUMERIC_TEXT = 128


class TourismContractError(ValueError):
    """Fixed code plus an optional known parameter name, never a raw value."""

    def __init__(self, code: str, parameter: str | None = None):
        self.code = code if isinstance(code, str) and code in _ERROR_CODES else "INVALID_PARAMETER"
        self.parameter = parameter if isinstance(parameter, str) and parameter in _SAFE_PARAMETER_NAMES else None
        super().__init__(self.code)


@dataclass(frozen=True)
class Operation:
    service: str
    name: str
    path: str
    parameters: tuple[dict, ...]
    item_schema: dict
    description: str
    deprecated: bool


def _resolve(schema, document, references=()):
    """Resolve local Swagger refs, including refs inside nested item fields."""
    if isinstance(schema, list):
        return [_resolve(value, document, references) for value in schema]
    if not isinstance(schema, dict):
        return schema
    if "$ref" in schema:
        ref = schema["$ref"]
        if not isinstance(ref, str) or not ref.startswith("#/definitions/") or ref in references:
            raise TourismContractError("INVALID_CONTRACT")
        target = document
        for component in ref[2:].split("/"):
            target = target[component.replace("~1", "/").replace("~0", "~")]
        resolved = _resolve(target, document, (*references, ref))
        # Current snapshots do not use meaningful $ref siblings. Preserve any
        # siblings nevertheless rather than throwing away official metadata.
        return {**resolved, **{key: _resolve(value, document, references)
                               for key, value in schema.items() if key != "$ref"}}
    return {key: _resolve(value, document, references) for key, value in schema.items()}


@lru_cache(maxsize=1)
def _registry() -> dict[tuple[str, str], Operation]:
    result = {}
    directory = Path(__file__).resolve().parents[2] / "docs" / "reference" / "public-data"
    try:
        for service, filename in _SOURCES.items():
            document = json.loads((directory / filename).read_text(encoding="utf-8"))
            for path, entry in document["paths"].items():
                get = entry["get"]
                name = get["operationId"]
                parameters = {}
                for parameter in [*entry.get("parameters", ()), *get.get("parameters", ())]:
                    if parameter.get("in") != "query" or parameter["name"] not in _SAFE_PARAMETER_NAMES:
                        raise TourismContractError("INVALID_CONTRACT")
                    parameters[parameter["name"]] = deepcopy(parameter)
                response_schema = _resolve(get["responses"]["200"]["schema"], document)
                item = response_schema["properties"]["body"]["properties"]["items"]["properties"]["item"]
                if item.get("type") != "object" or not isinstance(item.get("properties"), dict):
                    raise TourismContractError("INVALID_CONTRACT")
                description = get.get("description") or get.get("summary") or ""
                key = (service, name)
                if key in result:
                    raise TourismContractError("INVALID_CONTRACT")
                result[key] = Operation(service, name, path, tuple(parameters.values()), item,
                                        description, bool(get.get("deprecated")) or "미사용 기능" in description)
    except TourismContractError:
        raise
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError):
        raise TourismContractError("INVALID_CONTRACT") from None
    return result


def _lookup(service: str, name: str) -> Operation:
    if not isinstance(service, str) or service not in _SOURCES:
        raise TourismContractError("UNKNOWN_SERVICE")
    if not isinstance(name, str):
        raise TourismContractError("UNKNOWN_OPERATION")
    found = _registry().get((service, name))
    if found is None:
        raise TourismContractError("UNKNOWN_OPERATION")
    return found


def operation(service: str, name: str) -> Operation:
    """Return a detached frozen operation; nested raw schemas remain dicts."""
    # The raw-dict interface is useful to clients, but exposing a cached dict
    # would let an accidental caller mutation alter later validation rules.
    return deepcopy(_lookup(service, name))


def operations(service: str | None = None) -> tuple[Operation, ...]:
    if service is not None and (not isinstance(service, str) or service not in _SOURCES):
        raise TourismContractError("UNKNOWN_SERVICE")
    return tuple(deepcopy(value) for value in _registry().values()
                 if service is None or value.service == service)


def _numeric(value, name: str) -> tuple[str, Decimal]:
    if isinstance(value, bool) or not isinstance(value, (str, int, float)):
        raise TourismContractError("INVALID_PARAMETER", name)
    if isinstance(value, float) and not math.isfinite(value):
        raise TourismContractError("INVALID_PARAMETER", name)
    try:
        text = str(value)
        if len(text) > _MAX_NUMERIC_TEXT or not _NUMBER.fullmatch(text):
            raise ValueError()
        number = Decimal(text)
        if not number.is_finite():
            raise ValueError()
    except (ValueError, OverflowError, InvalidOperation):
        raise TourismContractError("INVALID_PARAMETER", name) from None
    return text, number


def _serialize(value, parameter: dict) -> str:
    name, kind = parameter["name"], parameter["type"]
    if kind in {"number", "integer"} or name in _NUMERIC_QUERY_NAMES:
        text, number = _numeric(value, name)
        if kind == "integer" and number != number.to_integral_value():
            raise TourismContractError("INVALID_PARAMETER", name)
        if name in {"numOfRows", "pageNo"}:
            if number <= 0 or number != number.to_integral_value() or number.adjusted() >= _MAX_NUMERIC_TEXT:
                raise TourismContractError("INVALID_PARAMETER", name)
            # Query paging parameters need whole decimal digits, even if a
            # JSON caller supplied 1.0 or a CLI caller supplied "1e0".
            return format(number.to_integral_value(), "f")
        if name == "mapX" and not -180 <= number <= 180:
            raise TourismContractError("INVALID_PARAMETER", name)
        if name == "mapY" and not -90 <= number <= 90:
            raise TourismContractError("INVALID_PARAMETER", name)
        if name == "radius" and not 0 < number <= 20000:
            raise TourismContractError("INVALID_PARAMETER", name)
        return text
    if kind != "string" or not isinstance(value, str):
        raise TourismContractError("INVALID_PARAMETER", name)
    if any(ord(char) < 32 or 0xD800 <= ord(char) <= 0xDFFF for char in value):
        raise TourismContractError("INVALID_PARAMETER", name)
    return value


def _described_choices(parameter: dict) -> set[str] | None:
    name = parameter["name"]
    description = parameter.get("description", "")
    if "enum" in parameter:
        return {str(value) for value in parameter["enum"]}
    patterns = {
        "MobileOS": r"\b(IOS|AND|WIN|WEB|ETC)\s*\(",
        "langCode": r"\b(ko|en|cn1|jp)\s*[:=]",
        "arrange": r"\b([A-Z])\s*=",
        "contentTypeId": r"\b([0-9]+)\s*:",
        "syncStatus": r"\b([A-Z])\s*=",
        "imageYN": r"\b([YN])\s*[:=]",
        "lDongListYn": r"\b([YN])\s*[:=]",
        "lclsSystmListYn": r"\b([YN])\s*[:=]",
        "showflag": r"\b([01])\s*[:=]",
    }
    if name == "_type":
        return {"json", "xml"}
    pattern = patterns.get(name)
    return (set(re.findall(pattern, description)) or None) if pattern else None


def _validate_semantics(prepared: dict[str, str], declared: dict[str, dict]):
    present = {name for name, value in prepared.items() if value.strip()}
    for name, value in prepared.items():
        if name not in present:
            continue
        parameter = declared[name]
        choices = _described_choices(parameter)
        if choices is not None and value not in choices:
            raise TourismContractError("INVALID_PARAMETER", name)
        if "YYYYMMDD" in parameter.get("description", ""):
            try:
                if not _DATE.fullmatch(value):
                    raise ValueError()
                date(int(value[:4]), int(value[4:6]), int(value[6:]))
            except ValueError:
                raise TourismContractError("INVALID_PARAMETER", name) from None
    if {"eventStartDate", "eventEndDate"} <= present and prepared["eventEndDate"] < prepared["eventStartDate"]:
        raise TourismContractError("INVALID_PARAMETER", "eventEndDate")
    if "tid" in declared and "tlid" in declared and (("tid" in present) != ("tlid" in present)):
        raise TourismContractError("MISSING_PARAMETER", "tlid" if "tid" in present else "tid")
    for name, dependencies in {
        "lDongSignguCd": ("lDongRegnCd",),
        "lclsSystm2": ("lclsSystm1",),
        "lclsSystm3": ("lclsSystm1", "lclsSystm2"),
    }.items():
        if name not in present:
            continue
        description = declared[name].get("description", "")
        for dependency in dependencies:
            if "필수" in description and dependency in description and dependency not in present:
                raise TourismContractError("MISSING_PARAMETER", dependency)


def prepare_parameters(service: str, name: str, parameters: Mapping | None = None,
                       mobile_os: str = "ETC", mobile_app: str = "Petasos") -> dict[str, str]:
    """Validate caller queries without receiving or injecting a service key.

    Only MobileOS/MobileApp receive application defaults. Required parameters
    such as Odii langCode still must be supplied. Optional blank strings remain
    explicit values; blank numeric parameters are invalid. Identifiers and
    codes must already be strings, preserving meaningful leading zeroes.
    """
    selected = _lookup(service, name)
    if parameters is not None and not isinstance(parameters, Mapping):
        raise TourismContractError("INVALID_PARAMETER")
    supplied = dict(parameters) if parameters is not None else {}
    declared = {parameter["name"]: parameter for parameter in selected.parameters}
    for key in supplied:
        if not isinstance(key, str) or key not in declared:
            raise TourismContractError("INVALID_PARAMETER")
        if key == "serviceKey":
            raise TourismContractError("INVALID_PARAMETER", "serviceKey")
    supplied.setdefault("MobileOS", mobile_os)
    supplied.setdefault("MobileApp", mobile_app)
    for parameter in selected.parameters:
        key = parameter["name"]
        if key == "serviceKey":
            continue
        if parameter.get("required") and (key not in supplied or
                                          isinstance(supplied[key], str) and not supplied[key].strip()):
            raise TourismContractError("MISSING_PARAMETER", key)
    prepared = {key: _serialize(value, declared[key]) for key, value in supplied.items()}
    _validate_semantics(prepared, declared)
    return prepared
