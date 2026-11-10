package io.github.christiancg.lwnrdb.client.scripting;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.List;
import java.util.Optional;

public record TriggerInfo(String name, Optional<String> collection, List<String> events, String procedureName,
        String timing, String mode, boolean enabled, long version, Optional<String> definer, Document raw) {
}
