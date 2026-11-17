namespace Lwnrdb.Client.Internal;

internal sealed class IdentityCodec : IDocumentCodec<Document>
{
    public static IdentityCodec Instance { get; } = new();

    public Document Encode(Document value) => value;

    public Document Decode(Document document) => document;
}
