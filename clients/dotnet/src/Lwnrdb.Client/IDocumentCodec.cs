namespace Lwnrdb.Client;

public interface IDocumentCodec<T>
{
    Document Encode(T value);

    T Decode(Document document);
}
