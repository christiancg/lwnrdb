package org.techhouse.data.admin;

/**
 * The state of a pending trigger run. {@code PENDING} is replayed by startup recovery; {@code DEAD} is not -
 * it has exhausted its attempts and waits for an operator to replay or discard it.
 */
public enum TriggerRunStatus {
    PENDING, DEAD
}
