package io.github.christiancg.lwnrdb.client.scripting;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.Optional;

public record ProcedureInfo(String name, long version, boolean enabled, Optional<String> description,
        Optional<String> source, Document raw) {
}
