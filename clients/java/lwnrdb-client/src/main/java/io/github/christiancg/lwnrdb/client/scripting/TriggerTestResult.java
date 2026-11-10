package io.github.christiancg.lwnrdb.client.scripting;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.List;
import java.util.Optional;

public record TriggerTestResult(String decision, Optional<Document> document, List<String> logs,
        boolean logsTruncated) {
}
