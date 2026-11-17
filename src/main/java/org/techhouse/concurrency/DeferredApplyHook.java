package org.techhouse.concurrency;

public interface DeferredApplyHook {
    DeferredApplyHook NONE = new DeferredApplyHook() {
        @Override
        public boolean pending(String lockName) {
            return false;
        }

        @Override
        public void apply(String lockName) {
        }
    };

    boolean pending(String lockName);

    void apply(String lockName);
}
