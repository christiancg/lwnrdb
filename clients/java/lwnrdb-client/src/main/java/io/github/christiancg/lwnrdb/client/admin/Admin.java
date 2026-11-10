package io.github.christiancg.lwnrdb.client.admin;

import io.github.christiancg.lwnrdb.client.internal.ClientContext;

public final class Admin {
    private final ClientContext context;

    public Admin(ClientContext context) {
        this.context = context;
    }

    public Users users() {
        return new Users(context);
    }

    public DistributedTransactions transactions() {
        return new DistributedTransactions(context);
    }

    public Scripts scripts() {
        return new Scripts(context);
    }

    public TriggerRuns triggerRuns() {
        return new TriggerRuns(context);
    }
}
