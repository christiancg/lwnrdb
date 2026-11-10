package io.github.christiancg.lwnrdb.client.internal;

import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.node.ObjectNode;

record PendingReply(CompletableFuture<ObjectNode> future, boolean awaitsClose) {
}
