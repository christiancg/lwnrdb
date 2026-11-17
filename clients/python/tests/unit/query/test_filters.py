from __future__ import annotations

import pytest

from lwnrdb import (
    CastType,
    Conjunction,
    ConjunctionFilter,
    CustomType,
    FieldFilter,
    FieldOperator,
    GeoDistanceComparator,
    GeoPoint,
    MapOps,
    MidOps,
    Vector,
    and_,
    distance,
    field,
    nearest,
    nor,
    or_,
    within,
    xor,
)


def test_the_operator_aliases_build_the_method_filters() -> None:
    total = field("total")
    assert (total > 1) == total.gt(1)
    assert (total >= 1) == total.gte(1)
    assert (total < 1) == total.lt(1)
    assert (total <= 1) == total.lte(1)
    assert (1 < total) == total.gt(1)
    assert total.ne(1) == FieldFilter(FieldOperator.NOT_EQUALS, "total", 1)
    assert total.in_(1, 2) == FieldFilter(FieldOperator.IN, "total", (1, 2))
    assert total.not_in() == FieldFilter(FieldOperator.NOT_IN, "total", ())
    assert total.contains("a") == FieldFilter(FieldOperator.CONTAINS, "total", "a")
    assert total.eq(None) == FieldFilter(FieldOperator.EQUALS, "total", None)
    assert total.field == "total"
    assert repr(total) == "field('total')"


def test_and_and_or_flatten_their_left_side() -> None:
    a, b, c = field("a").eq(1), field("b").eq(2), field("c").eq(3)
    assert a & b & c == and_(a, b, c)
    assert a | b | c == or_(a, b, c)
    assert (a & b) | c == or_(and_(a, b), c)


def test_xor_nests_and_invert_is_a_single_nor() -> None:
    a, b, c = field("a").eq(1), field("b").eq(2), field("c").eq(3)
    assert a ^ b ^ c == xor(xor(a, b), c)
    assert ~a == nor(a)
    assert ~a == ConjunctionFilter(Conjunction.NOR, (a,))


def test_conjunctions_need_filters() -> None:
    with pytest.raises(ValueError, match="at least one operand"):
        and_()
    with pytest.raises(TypeError, match="eq\\(value\\)"):
        and_(field("a") == 1)


def test_geo_and_vector_filters_validate_their_arguments() -> None:
    point = GeoPoint(1, 2)
    assert distance("loc", point, GeoDistanceComparator.EQUALS, 0).metres == 0
    with pytest.raises(ValueError, match="non-negative"):
        distance("loc", point, GeoDistanceComparator.EQUALS, -1)
    with pytest.raises(ValueError):
        distance("loc", point, GeoDistanceComparator.EQUALS, True)
    with pytest.raises(TypeError, match="GeoPoint"):
        distance("loc", (1, 2), GeoDistanceComparator.EQUALS, 1)
    with pytest.raises(ValueError, match="at least 3 points"):
        within("loc", point, point)
    with pytest.raises(TypeError, match="polygon point"):
        within("loc", point, point, (1, 2))
    with pytest.raises(ValueError, match="k > 0"):
        nearest("emb", Vector.of(1), 0)
    with pytest.raises(TypeError, match="Vector"):
        nearest("emb", [1.0], 1)
    assert nearest("emb", Vector.of(1), 2).exact().is_exact
    with pytest.raises(TypeError, match="field must be a string"):
        field(3)


def test_mid_and_map_operations_validate() -> None:
    with pytest.raises(ValueError, match="at least one operand"):
        MidOps.sum()
    with pytest.raises(TypeError, match="JSON_CUSTOM"):
        MidOps.cast("raw", CastType.JSON_CUSTOM)
    with pytest.raises(TypeError):
        MidOps.cast("raw", "geo")
    assert MidOps.cast("raw", CustomType.GEO).custom_type is CustomType.GEO
    with pytest.raises(TypeError, match="MidOps"):
        MapOps.set("x", 3)
    removal = MapOps.remove("secret")
    assert removal.removes
    assert not MapOps.set("x", MidOps.abs("y")).removes
    with pytest.raises(TypeError, match="needs a Filter"):
        removal.when("status")
