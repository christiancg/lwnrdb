package org.techhouse.ops.tx;

import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.Transaction;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.auth.AuthorizationChecker;

public final class CommitAuthorization {
    private static final Cache cache = IocContainer.get(Cache.class);

    private CommitAuthorization() {
    }

    public static boolean allows(Transaction transaction, String username) {
        if (username == null) {
            return true;
        }
        final var user = cache.getAdminUserEntry(username);
        for (final var collectionId : transaction.getHeldLocks()) {
            final var separator = collectionId.indexOf(Globals.COLL_IDENTIFIER_SEPARATOR);
            final var dbName = collectionId.substring(0, separator);
            final var collName = collectionId.substring(separator + 1);
            if (!AuthorizationChecker.mayWrite(user, dbName, collName)) {
                return false;
            }
        }
        return true;
    }
}
