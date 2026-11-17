package io.github.christiancg.lwnrdb.client;

import java.util.List;

public record AnalyzeResult(List<Document> results, Document analysis) {
    public AnalyzeResult {
        results = List.copyOf(results);
    }
}
