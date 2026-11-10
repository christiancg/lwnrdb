package io.github.christiancg.lwnrdb.client;

import java.util.List;
import java.util.OptionalLong;

public record ReindexResult(List<String> rebuiltFields, OptionalLong primaryKeyEntries,
        OptionalLong unversionedEntries) {
}
