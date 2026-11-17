# DataclassCodec

Maps a dataclass to documents by its type hints. A field named `id` maps to `_id`; nested dataclasses,
lists, tuples, dicts, enums, optionals, `datetime`, `time`, `GeoPoint`, `Vector`, `LocalDateTime`, `LocalTime`
and `Annotated[list[float], AsVector]` are supported. Unknown keys are ignored when reading.

## model

The dataclass the codec maps.

## encode

Turns a dataclass instance into a document. A `None` id is left out, so the server generates one.

## decode

Builds a dataclass instance from a document.

Raises:
    MappingError: a required field is missing or a value does not fit its field's type
