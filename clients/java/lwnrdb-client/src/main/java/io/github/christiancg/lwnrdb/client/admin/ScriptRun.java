package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.Optional;

public record ScriptRun(String runId, Optional<String> node, String kind, Optional<String> database,
        Optional<String> name, Optional<String> username, long ageMs, Document raw) {
}
