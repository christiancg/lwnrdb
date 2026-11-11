from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, time
from enum import Enum
from typing import Annotated

import pytest

from lwnrdb import AsVector, DataclassCodec, GeoPoint, LocalDateTime, LocalTime, MappingError, Vector


class Status(Enum):
    ACTIVE = "active"
    CLOSED = "closed"


@dataclass
class Line:
    sku: str
    qty: int


@dataclass
class Order:
    status: Status
    total: float
    lines: list[Line]
    tags: tuple[str, ...]
    attributes: dict[str, int]
    placed_at: datetime
    opens_at: time
    location: GeoPoint
    embedding: Annotated[list[float], AsVector]
    exact: LocalDateTime | None = None
    exact_time: LocalTime | None = None
    note: str | None = None
    id: str | None = None
    vector: Vector | None = None
    loose: object = None
    flags: list[bool] = field(default_factory=list)


@dataclass
class Node:
    name: str
    children: list[Node]


def _order(**changes: object) -> Order:
    values: dict[str, object] = {
        "status": Status.ACTIVE,
        "total": 12.5,
        "lines": [Line("a", 2)],
        "tags": ("x",),
        "attributes": {"k": 1},
        "placed_at": datetime(2024, 1, 1, 10, 0),
        "opens_at": time(9, 30),
        "location": GeoPoint(1, 2),
        "embedding": [0.5, 1.0],
        "id": "o-1",
    }
    values.update(changes)
    return Order(**values)


def test_a_dataclass_round_trips_through_its_document() -> None:
    codec = DataclassCodec(Order)
    order = _order(exact=LocalDateTime.of(2024, 1, 1, 0, 0, 0, 1), vector=Vector.of(1))
    document = codec.encode(order)
    assert document["_id"] == "o-1"
    assert "id" not in document
    assert document["status"] == Status.ACTIVE
    assert document["embedding"] == Vector.of(0.5, 1.0)
    assert document["lines"] == [{"sku": "a", "qty": 2}]
    assert codec.decode(document) == order
    assert codec.model is Order


def test_a_missing_id_is_left_out_so_the_server_generates_one() -> None:
    document = DataclassCodec(Order).encode(_order(id=None))
    assert "_id" not in document


def test_decoding_widens_and_narrows_numbers_and_ignores_unknown_keys() -> None:
    codec = DataclassCodec(Line)
    assert codec.decode({"sku": "a", "qty": 2.0, "extra": True}) == Line("a", 2)
    decoded = DataclassCodec(Order).decode({**DataclassCodec(Order).encode(_order()), "total": 3})
    assert decoded.total == 3.0
    assert isinstance(decoded.total, float)


def test_datetime_and_time_fields_accept_exact_values_that_fit() -> None:
    codec = DataclassCodec(Order)
    document = codec.encode(_order())
    document["placed_at"] = LocalDateTime.of(2024, 1, 1, 10, 0)
    document["opens_at"] = LocalTime.of(9, 30)
    document["exact"] = datetime(2024, 1, 1)
    document["exact_time"] = time(8, 0)
    decoded = codec.decode(document)
    assert decoded.placed_at == datetime(2024, 1, 1, 10, 0)
    assert decoded.opens_at == time(9, 30)
    assert decoded.exact == LocalDateTime.of(2024, 1, 1)
    assert decoded.exact_time == LocalTime.of(8, 0)


@pytest.mark.parametrize(
    ("change", "message"),
    [
        ({"placed_at": LocalDateTime.of(2024, 1, 1, 0, 0, 0, 1)}, "does not fit a datetime"),
        ({"opens_at": LocalTime.of(9, 0, 0, 1)}, "does not fit a time"),
        ({"total": "12"}, "field 'total' should be a float"),
        ({"total": True}, "should be a float"),
        ({"lines": [{"sku": "a", "qty": 1.5}]}, "field 'lines\\[0\\].qty' should be an int"),
        ({"lines": "x"}, "should be a list"),
        ({"attributes": []}, "should be an object"),
        ({"status": "unknown"}, "should be a Status"),
        ({"location": "x"}, "should be a GeoPoint"),
        ({"embedding": [1.0]}, "should be a vector"),
        ({"note": 3}, "should be a str"),
        ({"placed_at": 3}, "should be a datetime"),
        ({"exact": "x"}, "should be a LocalDateTime"),
        ({"lines": [3]}, "should be an object"),
    ],
)
def test_decoding_a_mismatched_value_names_its_path(change: dict[str, object], message: str) -> None:
    codec = DataclassCodec(Order)
    document = {**codec.encode(_order()), **change}
    with pytest.raises(MappingError, match=message):
        codec.decode(document)


def test_a_missing_required_field_is_reported() -> None:
    with pytest.raises(MappingError, match="field 'qty' is missing"):
        DataclassCodec(Line).decode({"sku": "a"})


def test_encoding_checks_the_shape_of_containers() -> None:
    codec = DataclassCodec(Order)
    with pytest.raises(MappingError, match="should be a list"):
        codec.encode(_order(lines="x"))
    with pytest.raises(MappingError, match="should be a mapping"):
        codec.encode(_order(attributes=[1]))
    with pytest.raises(MappingError, match="should be a list of numbers"):
        codec.encode(_order(embedding="x"))
    with pytest.raises(MappingError, match="should be a Line"):
        codec.encode(_order(lines=[{"sku": "a"}]))
    assert codec.encode(_order(embedding=None))["embedding"] is None


def test_a_field_named_underscore_id_is_refused() -> None:
    @dataclass
    class Bad:
        _id: str

    with pytest.raises(MappingError, match="name the field id"):
        DataclassCodec(Bad)


def test_only_dataclass_types_are_accepted() -> None:
    with pytest.raises(TypeError, match="needs a dataclass type"):
        DataclassCodec(dict)
    with pytest.raises(TypeError):
        DataclassCodec(_order())


def test_recursive_dataclasses_resolve() -> None:
    codec = DataclassCodec(Node)
    tree = Node("root", [Node("leaf", [])])
    assert codec.decode(codec.encode(tree)) == tree
