package io.github.christiancg.lwnrdb.client;

import java.util.List;

public record ListenUpdate<T>(String listenId, List<T> results, String resultHash, boolean initial) {
    public ListenUpdate {
        results = List.copyOf(results);
    }
}
