from __future__ import annotations

import asyncio
import sys

from lwnrdb import AsyncLwnrdbClient, ClientSettings, ConnectionLostError, ServerAddress, and_, field, nearest
from lwnrdb import Vector as Embedding


async def main() -> int:
    condition = and_(field("total") > 100, nearest("embedding", Embedding.of(0.1, 0.2), 5).exact())
    if len(getattr(condition, "operands", ())) != 2:
        raise SystemExit("and_ built the wrong filter")
    try:
        await AsyncLwnrdbClient.connect(ClientSettings(hosts=(ServerAddress("127.0.0.1", 1),), connect_timeout=2))
    except ConnectionLostError:
        sys.stdout.write("async consumer ok\n")
        return 0
    raise SystemExit("connecting to a closed port should fail with a ConnectionLostError")


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
