"""Region packs for GET /v1/packs/region/{code} (서비스, 2차 앱 연결 전 뼈대).

Calls the same build_region_pack as the offline tools. Built packs stay in memory per region code;
source responses stay in the file cache, so a rebuild never refetches a source id.
"""
import asyncio

from .pack import build_region_pack


class PackService:
    def __init__(self, adapters, regions, intro_writer):
        self.adapters, self.regions, self.intro_writer = adapters, regions, intro_writer
        self.packs = {}
        self.lock = asyncio.Lock()

    async def region(self, code):
        """-> pack dict. KeyError: region outside the bundled boundaries; LookupError: nothing to announce."""
        async with self.lock:
            if code not in self.packs:
                pack, _report = await asyncio.to_thread(
                    build_region_pack, code, adapters=self.adapters, regions=self.regions,
                    intro_writer=self.intro_writer, log=lambda *_: None)
                self.packs[code] = pack
            return self.packs[code]

    async def close(self):
        pass
