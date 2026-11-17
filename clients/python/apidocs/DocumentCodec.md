# DocumentCodec

Maps your type to and from a document. Implement `encode` and `decode` and pass it as `codec=` to
`collection`.

```python
class PydanticCodec:
    def __init__(self, model):
        self.model = model

    def encode(self, value):
        return value.model_dump(by_alias=True, exclude_none=True)

    def decode(self, document):
        return self.model.model_validate(document)
```
