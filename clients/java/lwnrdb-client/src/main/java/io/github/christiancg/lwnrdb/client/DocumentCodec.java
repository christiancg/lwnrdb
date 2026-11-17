package io.github.christiancg.lwnrdb.client;

public interface DocumentCodec<T> {
    Document encode(T value);

    T decode(Document document);

    static DocumentCodec<Document> identity() {
        return IdentityCodec.INSTANCE;
    }
}
