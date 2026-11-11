from __future__ import annotations

from typing import Any

from lwnrdb._internal.collection_ops import DOCUMENT_ROWS
from lwnrdb._internal.dataclass_mapping import DataclassCodec
from lwnrdb.document import DocumentCodec


def codec_for(model: type[Any] | None, codec: DocumentCodec[Any, Any] | None) -> DocumentCodec[Any, Any]:
    if model is not None and codec is not None:
        raise TypeError("pass a dataclass model or a codec, not both")
    if codec is not None:
        return codec
    if model is not None:
        return DataclassCodec(model)
    return DOCUMENT_ROWS
