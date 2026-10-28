package org.techhouse.data;

import java.util.function.Predicate;
import java.util.function.ToLongFunction;

public interface SortedChunks<E> {
    int partitionPoint(Predicate<? super E> before);

    long sumOf(int fromIndex, int toIndex, ToLongFunction<? super E> weight);
}
