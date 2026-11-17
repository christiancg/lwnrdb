package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.Document;
import java.util.List;
import java.util.Optional;

public record TransactionInfo(String dtxId, Optional<String> coordinator, boolean coordinatorReachable,
        List<String> participants, long ageMs, Document raw) {
}
