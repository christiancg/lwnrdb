package io.github.christiancg.lwnrdb.client;

final class IdentityCodec implements DocumentCodec<Document> {
    static final IdentityCodec INSTANCE = new IdentityCodec();

    private IdentityCodec() {
    }

    @Override
    public Document encode(Document value) {
        return value;
    }

    @Override
    public Document decode(Document document) {
        return document;
    }
}
