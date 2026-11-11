from __future__ import annotations

import dataclasses
import types
import typing
from collections.abc import Callable, Mapping
from datetime import datetime, time
from enum import Enum
from typing import Any, Generic, TypeVar

from lwnrdb.document import AsVector, Document
from lwnrdb.errors import MappingError
from lwnrdb.values.geo_point import GeoPoint
from lwnrdb.values.local_date_time import LocalDateTime
from lwnrdb.values.local_time import LocalTime
from lwnrdb.values.vector import Vector

M = TypeVar("M")
Convert = Callable[[Any, str], Any]
_ID_FIELD = "id"
_WIRE_ID = "_id"
_PLAIN_TYPES: tuple[type, ...] = (str, bool, GeoPoint, Vector)


def _at(path: str) -> str:
    return "the document" if path == "" else f"field '{path}'"


def _mismatch(path: str, expected: str, value: object) -> MappingError:
    return MappingError(f"{_at(path)} should be {expected}, got {type(value).__name__}: {value!r}")


def _same(value: object, path: str) -> object:
    del path
    return value


class _Pair:
    __slots__ = ("decode", "encode")

    def __init__(self, encode: Convert, decode: Convert) -> None:
        self.encode = encode
        self.decode = decode


def _instance_of(kind: type) -> _Pair:
    def decode(value: object, path: str) -> object:
        if not isinstance(value, kind) or (kind is not bool and isinstance(value, bool)):
            raise _mismatch(path, f"a {kind.__name__}", value)
        return value

    return _Pair(_same, decode)


def _number(kind: type[int] | type[float]) -> _Pair:
    def decode(value: object, path: str) -> object:
        if isinstance(value, bool) or not isinstance(value, int | float):
            raise _mismatch(path, f"a {kind.__name__}", value)
        if kind is int:
            if isinstance(value, float) and not value.is_integer():
                raise _mismatch(path, "an int", value)
            return int(value)
        return float(value)

    return _Pair(_same, decode)


def _temporal(kind: type, exact: type, fits: Callable[[Any], bool], to_native: Callable[[Any], object]) -> _Pair:
    def decode(value: object, path: str) -> object:
        if isinstance(value, kind):
            return value
        if isinstance(value, exact):
            if not fits(value):
                raise MappingError(f"{_at(path)} holds {value}, which does not fit a {kind.__name__}")
            return to_native(value)
        raise _mismatch(path, f"a {kind.__name__}", value)

    return _Pair(_same, decode)


def _exact_temporal(exact: type, native: type, from_native: Callable[[Any], object]) -> _Pair:
    def decode(value: object, path: str) -> object:
        if isinstance(value, exact):
            return value
        if isinstance(value, native):
            return from_native(value)
        raise _mismatch(path, f"a {exact.__name__}", value)

    return _Pair(_same, decode)


def _vector_list() -> _Pair:
    def encode(value: object, path: str) -> object:
        if value is None:
            return None
        if not isinstance(value, list | tuple):
            raise _mismatch(path, "a list of numbers", value)
        return Vector(value)

    def decode(value: object, path: str) -> object:
        if not isinstance(value, Vector):
            raise _mismatch(path, "a vector", value)
        return value.to_list()

    return _Pair(encode, decode)


def _enum(kind: type[Enum]) -> _Pair:
    def decode(value: object, path: str) -> object:
        try:
            return kind(value)
        except ValueError as error:
            raise _mismatch(path, f"a {kind.__name__}", value) from error

    return _Pair(_same, decode)


def _optional(inner: _Pair) -> _Pair:
    def encode(value: object, path: str) -> object:
        return None if value is None else inner.encode(value, path)

    def decode(value: object, path: str) -> object:
        return None if value is None else inner.decode(value, path)

    return _Pair(encode, decode)


def _sequence(item: _Pair, as_tuple: bool) -> _Pair:
    def encode(value: object, path: str) -> object:
        if not isinstance(value, list | tuple):
            raise _mismatch(path, "a list", value)
        return [item.encode(element, f"{path}[{index}]") for index, element in enumerate(value)]

    def decode(value: object, path: str) -> object:
        if not isinstance(value, list):
            raise _mismatch(path, "a list", value)
        items = [item.decode(element, f"{path}[{index}]") for index, element in enumerate(value)]
        return tuple(items) if as_tuple else items

    return _Pair(encode, decode)


def _child(path: str, key: str) -> str:
    return key if path == "" else f"{path}.{key}"


def _mapping(item: _Pair) -> _Pair:
    def encode(value: object, path: str) -> object:
        if not isinstance(value, Mapping):
            raise _mismatch(path, "a mapping", value)
        return {key: item.encode(element, _child(path, str(key))) for key, element in value.items()}

    def decode(value: object, path: str) -> object:
        if not isinstance(value, dict):
            raise _mismatch(path, "an object", value)
        return {key: item.decode(element, _child(path, key)) for key, element in value.items()}

    return _Pair(encode, decode)


class _Field:
    __slots__ = ("name", "pair", "required", "wire")

    def __init__(self, name: str, wire: str, pair: _Pair, required: bool) -> None:
        self.name = name
        self.wire = wire
        self.pair = pair
        self.required = required


class _Mapper:
    def __init__(self, model: type) -> None:
        self._model = model
        self._fields: list[_Field] = []

    def resolve(self, registry: dict[type, _Mapper]) -> None:
        hints = typing.get_type_hints(self._model, include_extras=True)
        for field in dataclasses.fields(self._model):
            if not field.init:
                continue
            if field.name == _WIRE_ID:
                raise MappingError(f"{self._model.__name__} declares _id; name the field id, which maps to _id")
            has_default = field.default is not dataclasses.MISSING or field.default_factory is not dataclasses.MISSING
            wire = _WIRE_ID if field.name == _ID_FIELD else field.name
            self._fields.append(_Field(field.name, wire, _pair_for(hints[field.name], registry), not has_default))

    def encode(self, value: object, path: str) -> object:
        if not isinstance(value, self._model):
            raise _mismatch(path, f"a {self._model.__name__}", value)
        encoded: dict[str, object] = {}
        for field in self._fields:
            item = getattr(value, field.name)
            if field.wire == _WIRE_ID and item is None:
                continue
            encoded[field.wire] = field.pair.encode(item, _child(path, field.wire))
        return encoded

    def decode(self, value: object, path: str) -> object:
        if not isinstance(value, dict):
            raise _mismatch(path, "an object", value)
        arguments: dict[str, object] = {}
        for field in self._fields:
            if field.wire in value:
                arguments[field.name] = field.pair.decode(value[field.wire], _child(path, field.wire))
            elif field.required:
                raise MappingError(f"{_at(_child(path, field.wire))} is missing")
        return self._model(**arguments)


def _dataclass(model: type, registry: dict[type, _Mapper]) -> _Pair:
    mapper = registry.get(model)
    if mapper is None:
        mapper = _Mapper(model)
        registry[model] = mapper
        mapper.resolve(registry)
    return _Pair(mapper.encode, mapper.decode)


def _annotated(hint: Any, registry: dict[type, _Mapper]) -> _Pair:
    base, *extras = typing.get_args(hint)
    if any(extra is AsVector or isinstance(extra, AsVector) for extra in extras):
        return _vector_list()
    return _pair_for(base, registry)


def _union(hint: Any, registry: dict[type, _Mapper]) -> _Pair:
    members = [member for member in typing.get_args(hint) if member is not type(None)]
    if len(members) == 1:
        return _optional(_pair_for(members[0], registry))
    return _Pair(_same, _same)


def _generic(origin: Any, hint: Any, registry: dict[type, _Mapper]) -> _Pair:
    arguments = typing.get_args(hint)
    if origin in (list, tuple):
        item = arguments[0] if arguments else Any
        return _sequence(_pair_for(item, registry), as_tuple=origin is tuple)
    if origin is dict:
        return _mapping(_pair_for(arguments[1] if len(arguments) == 2 else Any, registry))
    return _Pair(_same, _same)


_SIMPLE: dict[type, Callable[[], _Pair]] = {
    int: lambda: _number(int),
    float: lambda: _number(float),
    datetime: lambda: _temporal(
        datetime, LocalDateTime, lambda value: value.fits_datetime, lambda value: value.to_datetime()
    ),
    time: lambda: _temporal(time, LocalTime, lambda value: value.fits_time, lambda value: value.to_time()),
    LocalDateTime: lambda: _exact_temporal(LocalDateTime, datetime, LocalDateTime.from_datetime),
    LocalTime: lambda: _exact_temporal(LocalTime, time, LocalTime.from_time),
}


def _pair_for(hint: Any, registry: dict[type, _Mapper]) -> _Pair:
    origin = typing.get_origin(hint)
    if origin is typing.Annotated:
        return _annotated(hint, registry)
    if origin in (typing.Union, types.UnionType):
        return _union(hint, registry)
    if origin is not None:
        return _generic(origin, hint, registry)
    if isinstance(hint, type):
        return _class_pair(hint, registry)
    return _Pair(_same, _same)


def _class_pair(hint: type, registry: dict[type, _Mapper]) -> _Pair:
    if hint in _SIMPLE:
        return _SIMPLE[hint]()
    if issubclass(hint, _PLAIN_TYPES):
        return _instance_of(hint)
    if dataclasses.is_dataclass(hint):
        return _dataclass(hint, registry)
    if issubclass(hint, Enum):
        return _enum(hint)
    return _Pair(_same, _same)


class DataclassCodec(Generic[M]):
    def __init__(self, model: type[M]) -> None:
        if not (isinstance(model, type) and dataclasses.is_dataclass(model)):
            raise TypeError(f"DataclassCodec needs a dataclass type, got {model!r}")
        self._model = model
        self._mapper = _dataclass(model, {})

    @property
    def model(self) -> type[M]:
        return self._model

    def encode(self, value: M) -> Document:
        encoded: Document = self._mapper.encode(value, "")
        return encoded

    def decode(self, document: Document) -> M:
        decoded: M = self._mapper.decode(document, "")
        return decoded
