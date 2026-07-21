package org.techhouse.simplejs.internal;

import java.util.ArrayDeque;

public final class EventLoop {
    private final ArrayDeque<Runnable> microtasks = new ArrayDeque<>();

    public void queueMicrotask(Runnable task) {
        microtasks.add(task);
    }

    public void drain() {
        while (!microtasks.isEmpty()) {
            microtasks.poll().run();
        }
    }
}
