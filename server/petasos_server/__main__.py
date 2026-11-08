"""Direct TLS, one worker, no access/body/credential logging, loopback by default."""
import argparse
import json
import logging
import os
import ssl
from collections.abc import Mapping
from pathlib import Path

import uvicorn

from .asgi import GenerationApp
from .auth import TokenRegistry
from .evidence import EvidenceRegistry, FileEvidenceRegistry
from .local_env import load_environment
from .provider import OpenRouterConfig, OpenRouterProvider, UnavailableProvider
from .service import GenerationService
from .auxiliary_routes import AuxiliaryRouteService, NaverDirectionsProvider


def build_app(config_path, environment: Mapping[str, str] | None = None):
    # Operator-owned config only; never print its contents or underlying parse errors.
    config = json.loads(Path(config_path).read_text())
    key = Path(config["fingerprintKeyFile"]).read_bytes()
    evidence = FileEvidenceRegistry(config["evidenceFile"]) if config.get("evidenceFile") else EvidenceRegistry()
    provider = UnavailableProvider()
    environment = os.environ if environment is None else environment
    if config.get("provider"):
        p = config["provider"]
        if p["type"] != "openrouter":
            raise ValueError("INVALID_PROVIDER_CONFIGURATION")
        accepted_model_ids = p.get("acceptedModelIds", [])
        if not isinstance(accepted_model_ids, list):
            raise ValueError("INVALID_PROVIDER_CONFIGURATION")
        provider = OpenRouterProvider(OpenRouterConfig(
            api_key=environment.get("PETASOS_OPENROUTER_API_KEY", ""), model=p["model"], provider_slug=p["providerSlug"],
            accepted_provider_names=tuple(p["acceptedProviderNames"]), privacy_reviewed_until=p["privacyReviewedUntil"],
            account_content_logging_disabled=p["accountContentLoggingDisabled"],
            max_output_tokens=p.get("maxOutputTokens", 4096),
            accepted_model_ids=tuple(accepted_model_ids)))
    route_service = None
    if "auxiliaryRoutes" in config:
        route_config = config["auxiliaryRoutes"]
        if (type(route_config) is not dict or set(route_config) != {"type"} or
                route_config["type"] != "naver_maps"):
            raise ValueError("INVALID_ROUTE_CONFIGURATION")
        route_service = AuxiliaryRouteService(NaverDirectionsProvider(
            environment.get("PETASOS_NAVER_MAPS_CLIENT_ID", ""),
            environment.get("PETASOS_NAVER_MAPS_CLIENT_SECRET", "")))
    return GenerationApp(TokenRegistry(path=config["tokensFile"]), GenerationService(evidence, provider, key), route_service)


def main():
    parser = argparse.ArgumentParser(description="Petasos HTTPS generation server")
    parser.add_argument("--config", required=True)
    parser.add_argument("--env-file", help="Private literal credentials file; nonempty process environment values take precedence")
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8443)
    args = parser.parse_args()
    try:
        app = build_app(args.config, environment=load_environment(args.env_file))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(args.cert, args.key)
    except Exception:
        parser.exit(2, "Invalid server configuration or TLS files.\n")
    # Disable dependency payload/trace logging as well as Uvicorn request-path logging.
    for name in ("httpx", "httpcore", "uvicorn.access", "uvicorn.error"):
        logging.getLogger(name).disabled = True
    uvicorn.run(app, host=args.host, port=args.port, workers=1, access_log=False, log_config=None,
                proxy_headers=False, server_header=False, ssl_context_factory=lambda config, default_factory: context,
                limit_concurrency=128, timeout_keep_alive=5)


if __name__ == "__main__":
    main()
