package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.Optional;

public record TriggerRun(String runId, String status, Optional<String> database, Optional<String> collection,
        Optional<String> trigger, Optional<String> procedure, Optional<String> event, long attempts,
        Optional<String> lastError, Document raw) {
}
