package io.github.christiancg.lwnrdb.client;

import java.util.List;

public record BulkSaveResult(List<String> inserted, List<String> updated) {
}
