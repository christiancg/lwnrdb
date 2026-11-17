package io.github.christiancg.lwnrdb.client;

import java.util.List;
import java.util.Optional;

public record ScriptResult(Object value, List<String> logs, boolean logsTruncated, Optional<String> runId) {
}
