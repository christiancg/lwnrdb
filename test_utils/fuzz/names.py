import string

NAME_ALPHABET = string.ascii_letters + string.digits + "_-"
FIELD_ALPHABET = string.ascii_letters + string.digits + "_.-"

COLLISION_PAIRS = [
    ("MyColl", "mycoll"),
    ("Docs", "docs"),
]

RESERVED_NEAR_MISSES = [
    "Admin",
    "ADMIN",
    "Script_Runs",
    "SCRIPT_RUNS",
    "admin_Pages",
]

FIELD_COLLISION_PAIRS = [
    ("first", "first-name"),
    ("Foo", "foo"),
    ("a", "a.b"),
]

AMBIGUOUS_FOLDER_PAIRS = [
    (("foo_bar", "baz"), ("foo", "bar_baz")),
]

RESERVED_FIELD_NAMES = ["_id", "tombstones"]


def plain_name(rng, minimum: int = 3, maximum: int = 12) -> str:
    size = rng.randint(minimum, maximum)
    return "".join(rng.choice(NAME_ALPHABET) for _ in range(size))


def plain_field(rng) -> str:
    size = rng.randint(1, 10)
    return "".join(rng.choice(FIELD_ALPHABET) for _ in range(size))


def database_pool(rng, count: int = 2) -> list:
    pool = [f"fz{plain_name(rng, 3, 6)}" for _ in range(count)]
    if rng.random() < 0.25:
        pool.append(rng.choice(RESERVED_NEAR_MISSES))
    return pool


def collection_pool(rng, count: int = 3) -> list:
    pool = [f"c{plain_name(rng, 3, 6)}" for _ in range(count)]
    if rng.random() < 0.35:
        pool.extend(rng.choice(COLLISION_PAIRS))
    if rng.random() < 0.2:
        pool.append(rng.choice(RESERVED_NEAR_MISSES))
    return pool


def field_pool(rng, count: int = 4) -> list:
    pool = [f"f{plain_field(rng)}" for _ in range(count)]
    if rng.random() < 0.4:
        pool.extend(rng.choice(FIELD_COLLISION_PAIRS))
    if rng.random() < 0.15:
        pool.append(rng.choice(RESERVED_FIELD_NAMES))
    return [name for name in pool if len(name) <= 64]


def id_pool(rng, count: int = 8) -> list:
    pool = []
    for _ in range(count):
        base = plain_name(rng, 1, 8)
        pool.append(base)
        if rng.random() < 0.3:
            flipped = base.swapcase()
            if flipped != base:
                pool.append(flipped)
    return pool
