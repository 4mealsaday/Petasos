"""One bounded HTTPS GET against a pinned Odii/KorService2 operation.

The official schemas omit array cardinality and nullable information. This layer
normalizes envelope/cardinality and XML pagination, while preserving item values
and reporting field type drift. It never grants permission to publish the data.
"""
from dataclasses import dataclass, field
from http.client import HTTPException
import json
import math
import re
import ssl
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlencode
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener
from xml.etree import ElementTree

from .tourism_contract import TourismContractError, operation as get_operation, prepare_parameters

BASE_URLS = {
    "odii": "https://apis.data.go.kr/B551011/Odii",
    "tourapi": "https://apis.data.go.kr/B551011/KorService2",
}
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
CODE_PATTERN = re.compile(r"(?:[0-9]{2}|[0-9]{4})")


class TourismApiError(ValueError):
    """Never carries upstream text, URLs, credentials, or query values."""

    def __init__(self, code, result_code=None, http_status=None):
        self.code = code
        self.result_code = result_code if isinstance(result_code, str) and CODE_PATTERN.fullmatch(result_code) else None
        self.http_status = http_status if type(http_status) is int and 100 <= http_status <= 599 else None
        super().__init__(code)


@dataclass(frozen=True)
class ApiResponse:
    result_code: str
    result_message: str = field(repr=False)
    items: tuple[dict, ...] = field(repr=False)
    num_of_rows: int | None
    page_no: int | None
    total_count: int | None
    response_format: str
    schema_issues: tuple[str, ...] = ()
    extra_field_count: int = 0

    def safe_metadata(self):
        return {
            "resultCode": self.result_code, "responseFormat": self.response_format,
            "itemCount": len(self.items), "numOfRows": self.num_of_rows,
            "pageNo": self.page_no, "totalCount": self.total_count,
            "schemaIssueCount": len(self.schema_issues), "extraFieldCount": self.extra_field_count,
        }

    def to_document(self):
        """Data for an explicitly private sink; never a logging representation."""
        return {"response": {
            "header": {"resultCode": self.result_code, "resultMsg": self.result_message},
            "body": {"items": {"item": list(self.items)}, "numOfRows": self.num_of_rows,
                     "pageNo": self.page_no, "totalCount": self.total_count},
        }}


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError()
        result[key] = value
    return result


def _reject_constant(_):
    raise ValueError()


def _bounded_value(value, depth=0):
    if depth > 48:
        raise ValueError()
    if isinstance(value, dict):
        for key, child in value.items():
            _bounded_value(key, depth + 1)
            _bounded_value(child, depth + 1)
    elif isinstance(value, list):
        for child in value:
            _bounded_value(child, depth + 1)
    elif isinstance(value, str):
        if any(ord(c) < 32 and c not in "\t\r\n" or 0xD800 <= ord(c) <= 0xDFFF for c in value):
            raise ValueError()
    elif isinstance(value, float) and not math.isfinite(value):
        raise ValueError()


def _xml_value(node, depth=0):
    if depth > 48 or node.attrib:
        raise ValueError()
    children = list(node)
    if not children:
        return node.text or ""
    if node.text and node.text.strip():
        raise ValueError()
    result = {}
    for child in children:
        if child.tail and child.tail.strip():
            raise ValueError()
        value = _xml_value(child, depth + 1)
        # Only the documented repeated item is converted to a list. Duplicate
        # headers/pagination/record fields must not silently override values.
        if child.tag == "item" and node.tag == "items":
            result.setdefault("item", []).append(value)
        elif child.tag in result:
            raise ValueError()
        else:
            result[child.tag] = value
    return result


def _parse(raw, maximum):
    if not isinstance(raw, bytes):
        raise TourismApiError("INVALID_RESPONSE")
    if len(raw) > maximum:
        raise TourismApiError("RESPONSE_TOO_LARGE")
    try:
        text = raw.decode("utf-8-sig")
        if text.lstrip().startswith("<"):
            if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
                raise ValueError()
            root = ElementTree.fromstring(text)
            if root.tag not in {"response", "OpenAPI_ServiceResponse"}:
                raise ValueError()
            document, kind = {root.tag: _xml_value(root)}, "xml"
        else:
            document = json.loads(text, object_pairs_hook=_unique_object, parse_constant=_reject_constant)
            kind = "json"
        _bounded_value(document)
        if not isinstance(document, dict):
            raise ValueError()
        return document, kind
    except (ValueError, TypeError, RecursionError, ElementTree.ParseError):
        raise TourismApiError("INVALID_RESPONSE") from None


def _pagination(value):
    if value is None:
        return None
    if isinstance(value, str) and re.fullmatch(r"[0-9]{1,19}", value) and int(value) <= 10**18:
        return int(value)
    if type(value) is int and 0 <= value <= 10**18:
        return value
    raise TourismApiError("INVALID_RESPONSE")


def decode_response(raw, service=None, operation=None, *, max_response_bytes=MAX_RESPONSE_BYTES):
    document, kind = _parse(raw, max_response_bytes)
    # Gateways can return XML even when _type=json was requested, including
    # HTTP 200 failures. Neither the error message nor request URL escapes.
    gateway = document.get("OpenAPI_ServiceResponse")
    if gateway is not None:
        header = gateway.get("cmmMsgHeader") if isinstance(gateway, dict) else None
        code = header.get("returnReasonCode") if isinstance(header, dict) else None
        raise TourismApiError("API_ERROR", code)
    envelope = document.get("response", document)
    if not isinstance(envelope, dict) or not isinstance(envelope.get("header"), dict):
        raise TourismApiError("INVALID_RESPONSE")
    header = envelope["header"]
    code = header.get("resultCode")
    if not isinstance(code, str) or not CODE_PATTERN.fullmatch(code):
        raise TourismApiError("INVALID_RESPONSE")
    if code not in {"00", "0000"}:
        raise TourismApiError("API_ERROR", code)
    message = header.get("resultMsg", "")
    body = envelope.get("body")
    if not isinstance(message, str) or not isinstance(body, dict):
        raise TourismApiError("INVALID_RESPONSE")
    pagination = [_pagination(body.get(name)) for name in ("numOfRows", "pageNo", "totalCount")]
    container = body.get("items")
    if container is None or container == "":
        rows = []
    elif isinstance(container, dict):
        rows = container.get("item")
        if rows is None or rows == "":
            rows = []
        elif isinstance(rows, dict):
            rows = [rows] if rows else []
    else:
        raise TourismApiError("INVALID_RESPONSE")
    if not isinstance(rows, list) or any(not isinstance(row, dict) or not row for row in rows):
        raise TourismApiError("INVALID_RESPONSE")
    if not rows and pagination[2] and container is None:
        raise TourismApiError("INVALID_RESPONSE")
    issues, extras = set(), 0
    if service is not None or operation is not None:
        try:
            fields = get_operation(service, operation).item_schema.get("properties", {})
        except TourismContractError as error:
            raise TourismApiError(error.code) from None
        for row in rows:
            extras += len(row.keys() - fields.keys())
            for name, value in row.items():
                if name in fields and not isinstance(value, str):
                    # Current 23 contracts declare every item field as string.
                    # Preserve null/type drift and make it visible to consumers;
                    # never coerce numeric IDs and lose leading-zero semantics.
                    issues.add("item." + name + ":TYPE_MISMATCH")
    return ApiResponse(code, message, tuple(rows), *pagination, kind, tuple(sorted(issues)), extras)


class TourismClient:
    """Credentials stay in memory; no retries, redirects, or implicit paging.

    max_rows and max_response_bytes are local resource limits, not claims about
    limits in the official schemas. Caller parameters and item data are not logs.
    """

    def __init__(self, service, key, *, timeout=10, max_rows=1000,
                 max_response_bytes=MAX_RESPONSE_BYTES, opener=None):
        if not isinstance(service, str) or service not in BASE_URLS:
            raise TourismApiError("INVALID_SERVICE")
        if not isinstance(key, str) or not key or len(key) > 4096:
            raise TourismApiError("INVALID_SERVICE_KEY")
        decoded_key = unquote(key)
        if not decoded_key or any(ord(c) < 33 or ord(c) == 127 for c in decoded_key):
            raise TourismApiError("INVALID_SERVICE_KEY")
        if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= 60:
            raise TourismApiError("INVALID_CLIENT_LIMIT")
        if type(max_rows) is not int or not 1 <= max_rows <= 10000:
            raise TourismApiError("INVALID_CLIENT_LIMIT")
        if type(max_response_bytes) is not int or not 1 <= max_response_bytes <= 16 * 1024 * 1024:
            raise TourismApiError("INVALID_CLIENT_LIMIT")
        self.service, self._key = service, decoded_key
        self.timeout, self.max_rows, self.max_response_bytes = timeout, max_rows, max_response_bytes
        self._opener = opener or build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()), _NoRedirect())

    def prepare_request(self, operation, parameters=None, response_format="json"):
        if not isinstance(response_format, str) or response_format not in {"json", "xml"}:
            raise TourismApiError("INVALID_FORMAT")
        if parameters is not None and not isinstance(parameters, dict):
            raise TourismApiError("INVALID_PARAMETER")
        supplied = dict(parameters or {})
        if "_type" in supplied and supplied["_type"] != response_format:
            raise TourismApiError("INVALID_FORMAT")
        supplied["_type"] = response_format
        if self.service == "odii":
            supplied.setdefault("langCode", "ko")
        try:
            definition = get_operation(self.service, operation)
            query = prepare_parameters(self.service, operation, supplied)
        except TourismContractError as error:
            raise TourismApiError(error.code) from None
        if "numOfRows" in query and int(query["numOfRows"]) > self.max_rows:
            raise TourismApiError("CLIENT_ROW_LIMIT")
        query["serviceKey"] = self._key
        url = BASE_URLS[self.service] + definition.path + "?" + urlencode(query)
        return Request(url, headers={"Accept": "application/" + response_format,
                                     "User-Agent": "PetasosPublicData/0.8"}, method="GET")

    def request(self, operation, parameters=None, response_format="json"):
        request = self.prepare_request(operation, parameters, response_format)
        try:
            with self._opener.open(request, timeout=self.timeout) as response:
                status = response.status
                raw = response.read(self.max_response_bytes + 1)
                if status != 200:
                    raise TourismApiError("HTTP_ERROR", http_status=status)
        except HTTPError as error:
            try:
                raw = error.read(self.max_response_bytes + 1)
                try:
                    decode_response(raw, max_response_bytes=self.max_response_bytes)
                except TourismApiError as parsed:
                    raise TourismApiError("HTTP_ERROR", parsed.result_code, error.code) from None
                raise TourismApiError("HTTP_ERROR", http_status=error.code) from None
            except (OSError, HTTPException):
                raise TourismApiError("HTTP_ERROR", http_status=error.code) from None
            finally:
                error.close()
        except TourismApiError:
            raise
        except (URLError, TimeoutError, OSError, ValueError, HTTPException):
            raise TourismApiError("NETWORK_ERROR") from None
        result = decode_response(raw, self.service, operation, max_response_bytes=self.max_response_bytes)
        if len(result.items) > self.max_rows:
            raise TourismApiError("CLIENT_ROW_LIMIT")
        return result
