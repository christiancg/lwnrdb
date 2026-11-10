package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.node.ObjectNode;

public final class Documents {
    private static final AtomicReference<Access> ACCESS = new AtomicReference<>();

    private Documents() {
    }

    public interface Access {
        ObjectNode node(Document document);

        Document wrap(ObjectNode node);
    }

    public static void install(Access access) {
        if (!ACCESS.compareAndSet(null, access)) {
            throw new IllegalStateException("document access is already installed");
        }
    }

    public static ObjectNode node(Document document) {
        return access().node(document);
    }

    public static Document wrap(ObjectNode node) {
        return access().wrap(node);
    }

    private static Access access() {
        if (ACCESS.get() == null) {
            Document.empty();
        }
        return ACCESS.get();
    }
}
