"""Content pipeline (docs/CONTENT_PIPELINE.md): source adapters -> Place/Fact -> generation -> Content Pack.

The demo builds a pack once on a Mac (server/tools/build_pack.py); the service calls the same functions from
GET /v1/packs/region/{code}. Every row keeps its source and license.
"""
