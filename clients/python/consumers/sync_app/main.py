from __future__ import annotations

import sys

from lwnrdb import (
    ClientSettings,
    GeoPoint,
    LwnrdbClient,
    LwnrdbError,
    MapOps,
    MidOps,
    Order,
    ServerAddress,
    Vector,
    field,
)


def main() -> int:
    condition = (field("status").eq("active")) & (field("location").eq(GeoPoint(40.71, -74)))
    operation = MapOps.set("total", MidOps.multiply("price", "qty"))
    if Order.DESC != "DESC" or operation.field != "total" or Vector.of(1).dimensions != 1:
        raise SystemExit("the package exports the wrong values")
    if len(getattr(condition, "operands", ())) != 2:
        raise SystemExit("the & operator built the wrong filter")
    try:
        LwnrdbClient.connect(ClientSettings(hosts=(ServerAddress("127.0.0.1", 1),), connect_timeout=2))
    except LwnrdbError:
        sys.stdout.write("sync consumer ok\n")
        return 0
    raise SystemExit("connecting to a closed port should fail with an LwnrdbError")


if __name__ == "__main__":
    sys.exit(main())
