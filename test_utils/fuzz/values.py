import json


class RawJson:
    __slots__ = ("text",)

    def __init__(self, text: str):
        self.text = text

    def __repr__(self) -> str:
        return f"RawJson({self.text!r})"

    def __eq__(self, other) -> bool:
        return isinstance(other, RawJson) and other.text == self.text

    def __hash__(self) -> int:
        return hash(("RawJson", self.text))


def to_json_text(node) -> str:
    if isinstance(node, RawJson):
        return node.text
    if isinstance(node, dict):
        members = ",".join(f"{json.dumps(k)}:{to_json_text(v)}" for k, v in node.items())
        return "{" + members + "}"
    if isinstance(node, list):
        return "[" + ",".join(to_json_text(v) for v in node) + "]"
    return json.dumps(node)


INTERESTING_NUMBERS = [
    0,
    -0.0,
    1,
    -1,
    12,
    12.0,
    2 ** 53 - 1,
    2 ** 53,
    2 ** 53 + 1,
    2 ** 31 - 1,
    -(2 ** 31),
    1e21,
    1e-7,
    3e9,
    5e-324,
    1.7976931348623157e308,
]

RAW_ONLY_NUMBERS = [
    RawJson("1e400"),
    RawJson("-1e400"),
    RawJson("1" + "0" * 400),
]

INTERESTING_STRINGS = [
    "",
    "a",
    "A",
    "abc",
    "ABC",
    "",
    "ab",
    "a?b",
    "\n",
    "\r\n",
    "\\",
    "\\u001f",
    "\x00",
    "\x7f",
    "\ud800",
    "\udc00",
    "a\ud800b",
    "\U0001f600‍\U0001f4bb",
    "#geo(1,2)",
    "#datetime(2024-01-01T10:00)",
    "Infinity",
    "NaN",
    "null",
    "true",
    "a" * 64,
    "a" * 65,
]

INTERESTING_GEO = [
    "#geo(0,0)",
    "#geo(90,0)",
    "#geo(-90,0)",
    "#geo(0,180)",
    "#geo(0,-180)",
    "#geo(0.1,179.95)",
    "#geo(-0.1,-179.95)",
    "#geo(51.5,-0.12)",
]

INTERESTING_VECTORS = [
    "#vector(1,0,0)",
    "#vector(1.0,0.0,0.0)",
    "#vector(0,1,0)",
    "#vector(-1,-1,-1)",
    "#vector(0.5,0.5,0.7071)",
]

INTERESTING_TEMPORAL = [
    "#datetime(2024-01-01T10:00)",
    "#datetime(2024-01-01T10:00:00)",
    "#datetime(2024-01-01T10:00:00.000)",
    "#datetime(1970-01-01T00:00)",
    "#time(10:00)",
    "#time(10:00:00)",
    "#time(23:59:59)",
]

INTERESTING_CUSTOM = INTERESTING_GEO + INTERESTING_VECTORS + INTERESTING_TEMPORAL

RAW_ONLY_OBJECTS = [
    RawJson('{"dup":1,"dup":2}'),
    RawJson('{"":1}'),
]

SCALAR_POOL = (
    INTERESTING_NUMBERS
    + INTERESTING_STRINGS
    + INTERESTING_CUSTOM
    + [True, False, None]
)

RAW_POOL = RAW_ONLY_NUMBERS + RAW_ONLY_OBJECTS

MAX_DEPTH = 2


def scalar(rng):
    if rng.random() < 0.02:
        return rng.choice(RAW_POOL)
    return rng.choice(SCALAR_POOL)


def value(rng, depth: int = 0):
    if depth >= MAX_DEPTH or rng.random() < 0.7:
        return scalar(rng)
    if rng.random() < 0.5:
        size = rng.randint(0, 3)
        return [value(rng, depth + 1) for _ in range(size)]
    size = rng.randint(0, 3)
    return {f"k{i}": value(rng, depth + 1) for i in range(size)}


def operand(rng):
    return rng.choice(SCALAR_POOL)


def operand_list(rng):
    size = rng.randint(1, 3)
    return [rng.choice(SCALAR_POOL) for _ in range(size)]
