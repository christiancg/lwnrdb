package org.techhouse.ex;

public class DurableReplayIncompleteException extends Exception {
    public DurableReplayIncompleteException(String txId, String reason) {
        super("Transaction " + txId + " did not finish its durable commit: " + reason);
    }
}
