#!/usr/bin/env python3
"""Inspect local key presence, or perform bounded auth/data GETs without revealing secrets."""
import argparse
import json
from pathlib import Path
import re
import ssl
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import unquote, urlencode
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
from petasos_server.local_env import load_environment

MAX_RESPONSE = 512 * 1024
PUBLIC_BASE = "https://apis.data.go.kr/B551011/"
PUBLIC_PATHS = {"odii": "Odii/themeBasedList", "tourapi": "KorService2/areaBasedList2"}
KEY_NAMES = {"openrouter": "PETASOS_OPENROUTER_API_KEY", "odii": "PETASOS_ODII_SERVICE_KEY", "tourapi": "PETASOS_TOURAPI_SERVICE_KEY"}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def get(url, headers=None):
    """Only callers below choose URLs. Never print URL, response text, or exception details."""
    opener = build_opener(ProxyHandler({}), HTTPSHandler(context=ssl.create_default_context()), NoRedirect())
    request = Request(url, headers={"User-Agent": "PetasosDevelopmentProbe/0.8", "Accept": "application/json", **(headers or {})})
    try:
        with opener.open(request, timeout=10) as response:
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                return response.status, None, "RESPONSE_TOO_LARGE"
            return response.status, raw, None
    except HTTPError as error:
        status = error.code
        error.close()
        return status, None, "HTTP_ERROR"
    except (URLError, TimeoutError, OSError, ValueError):
        return None, None, "NETWORK_ERROR"


def public_url(service, key):
    # Decode URL-encoded portal keys once; preserve literal '+' in a raw Base64 key.
    key = unquote(key)
    parameters = {"serviceKey": key, "MobileOS": "ETC", "MobileApp": "Petasos",
                  "numOfRows": 1, "pageNo": 1, "_type": "json"}
    if service == "odii":
        parameters["langCode"] = "ko"
    return PUBLIC_BASE + PUBLIC_PATHS[service] + "?" + urlencode(parameters)


def public_result(raw):
    """Return only result code and count. Never return titles, body, error messages or keys."""
    try:
        text = raw.decode("utf-8-sig")
        if text.lstrip().startswith("<"):
            if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
                raise ValueError()
            root = ElementTree.fromstring(text)
            code = root.findtext("./header/resultCode")
            if code is None:
                code = root.findtext("./cmmMsgHeader/returnReasonCode")
            items = root.findall("./body/items/item")
            count = len(items)
        else:
            data = json.loads(text)["response"]
            code = data["header"]["resultCode"]
            items = (data.get("body") or {}).get("items") or {}
            if not isinstance(items, dict):
                raise ValueError()
            rows = items.get("item") or []
            count = len(rows) if isinstance(rows, list) else 1 if isinstance(rows, dict) else 0
        # Upstream text is never an output field, including unexpected result codes.
        if not isinstance(code, str) or not re.fullmatch(r"(?:[0-9]{2}|[0-9]{4})", code):
            return {"status": "INVALID_RESPONSE"}
        # Current tourism gateway returns 0000; older official examples use 00.
        # Preserve the reported code rather than accepting arbitrary zero-like values.
        if code not in {"00", "0000"}:
            return {"status": "API_ERROR", "resultCode": code}
        return {"status": "PASS" if count > 0 else "NO_DATA", "resultCode": code, "receivedItems": count}
    except (UnicodeError, ValueError, TypeError, KeyError, AttributeError, ElementTree.ParseError):
        return {"status": "INVALID_RESPONSE"}


def openrouter_result(raw):
    try:
        data = json.loads(raw)["data"]
        if not isinstance(data, dict):
            raise ValueError()
        # Never output label, user/workspace IDs, key fragments, balance details or errors.
        if data.get("is_management_key") is True or data.get("is_provisioning_key") is True:
            return {"status": "GENERATION_KEY_REQUIRED"}
        return {"status": "PASS", "scope": "authentication_only_not_model_or_privacy_validation"}
    except (ValueError, TypeError, KeyError):
        return {"status": "INVALID_RESPONSE"}


def inspect(environment, services, check=False, fetch=get):
    results = []
    for service in services:
        name = KEY_NAMES[service]
        value = environment.get(name, "")
        if not value and service != "openrouter":
            name = "PETASOS_DATA_GO_KR_SERVICE_KEY"
            value = environment.get(name, "")
        result = {"service": service, "keySource": name, "status": "SET" if value else "MISSING"}
        if value and check:
            if service == "openrouter":
                status, raw, error = fetch("https://openrouter.ai/api/v1/key", {"Authorization": "Bearer " + value})
            else:
                status, raw, error = fetch(public_url(service, value))
            result["httpStatus"] = status
            result.update({"status": error} if error else openrouter_result(raw) if service == "openrouter" else public_result(raw))
        results.append(result)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    parser.add_argument("--check", action="store_true", help="Perform auth GET + one-row public data GETs; never call a generation model")
    parser.add_argument("--service", choices=["all", *KEY_NAMES], default="all")
    args = parser.parse_args()
    try:
        environment = load_environment(args.env_file)
    except (OSError, ValueError):
        print("LOCAL_ENV_INVALID: .env 파일 형식·소유자·권한(600)을 확인하세요. 값은 출력하지 않았습니다.")
        return 2
    services = list(KEY_NAMES) if args.service == "all" else [args.service]
    results = inspect(environment, services, args.check)
    print(json.dumps({"mode": "live_get" if args.check else "presence_only_no_network", "results": results}, indent=2))
    return 0 if all(r["status"] == ("PASS" if args.check else "SET") for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
