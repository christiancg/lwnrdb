package org.techhouse.fs;

sealed interface WriteIntent permits WriteIntent.Vacuum {
    record Vacuum(long page) implements WriteIntent {
    }
}
