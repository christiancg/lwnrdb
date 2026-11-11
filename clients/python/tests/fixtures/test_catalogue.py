from __future__ import annotations

from lwnrdb import (
    ERROR_CODES,
    OPERATIONS,
    CustomType,
    ErrorCode,
    GeoDistanceComparator,
    GeoPoint,
    Operation,
    Vector,
    distance,
    nearest,
    within,
)
from lwnrdb._internal.custom_values import DETECTION
from lwnrdb._internal.pipeline_encoder import PipelineEncoder
from tests.support.protocol_fixtures import read


def test_operations_equal_operations_json() -> None:
    expected = [
        (entry["name"], entry["scope"], entry["adminOnly"], entry["allowedInTransaction"])
        for entry in read("operations.json")["operations"]
    ]
    actual = [
        (details.name, details.scope, details.admin_only, details.allowed_in_transaction) for details in OPERATIONS
    ]
    assert actual == expected
    assert [operation.value for operation in Operation] == [name for name, *_ in expected]


def test_error_codes_equal_error_codes_json() -> None:
    expected = [
        {
            "code": entry["code"],
            "name": entry["name"],
            "status": entry["status"],
            "message": entry["message"],
            "retryable": entry["retryable"],
        }
        for entry in read("error-codes.json")["errorCodes"]
    ]
    actual = [
        {
            "code": details.code,
            "name": details.name,
            "status": details.status.value,
            "message": details.message,
            "retryable": details.retryable,
        }
        for details in ERROR_CODES
    ]
    assert actual == expected
    assert {member.name: member.value for member in ErrorCode} == {entry["name"]: entry["code"] for entry in expected}


def test_custom_types_operators_and_comparators_equal_custom_types_json() -> None:
    catalogue = read("custom-types.json")
    assert DETECTION.pattern == catalogue["detectionRegex"]
    assert [comparator.value for comparator in GeoDistanceComparator] == catalogue["geoDistanceComparators"]
    types = catalogue["types"]
    assert sorted(member.value for member in CustomType) == sorted(entry["name"] for entry in types)
    operators = sorted(
        operator for entry in types for operator in [*entry["predicateOperators"], *entry["rankingOperators"]]
    )
    encoder = PipelineEncoder()
    produced = sorted(
        str(encoder.filter(condition)["customOperatorName"])
        for condition in (
            distance("f", GeoPoint(0, 0), GeoDistanceComparator.EQUALS, 1),
            within("f", GeoPoint(0, 0), GeoPoint(1, 0), GeoPoint(0, 1)),
            nearest("f", Vector.of(1), 1),
        )
    )
    assert produced == operators
