package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.Optional;

public record ServerInfo(Optional<String> state, Optional<String> version, Document raw) {
}
