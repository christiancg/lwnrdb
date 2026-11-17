package io.github.christiancg.lwnrdb.client.internal;

import io.github.christiancg.lwnrdb.client.AnalyzeResult;
import io.github.christiancg.lwnrdb.client.Document;
import io.github.christiancg.lwnrdb.client.DocumentCodec;
import java.util.List;

public interface QueryTarget<T> {
    DocumentCodec<T> codec();

    List<Document> aggregate(List<PipelineStep> steps, boolean dirtyRead);

    AnalyzeResult analyze(List<PipelineStep> steps, boolean dirtyRead);
}
