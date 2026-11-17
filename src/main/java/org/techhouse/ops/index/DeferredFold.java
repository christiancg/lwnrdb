package org.techhouse.ops.index;

import org.techhouse.fs.PendingFold;

public record DeferredFold(String identifier, PendingFold pending) {
}
