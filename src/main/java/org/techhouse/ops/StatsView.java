package org.techhouse.ops;

import org.techhouse.cache.Cache;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.admin.DatabaseVisibility;
import org.techhouse.ops.auth.AuthorizationChecker;

public record StatsView(AdminUserEntry user) {
    private static final Cache cache = IocContainer.get(Cache.class);

    public static StatsView of(AdminUserEntry user) {
        return new StatsView(user);
    }

    public boolean includesNodeSections() {
        return user() != null && user().isAdmin();
    }

    public boolean includesDatabase(String name) {
        return includesNodeSections() || DatabaseVisibility.isVisible(user(), cache.getAdminDbEntry(name));
    }

    public boolean includesCollection(String database, String collection) {
        return AuthorizationChecker.canReadCollection(user(), database, collection);
    }
}
