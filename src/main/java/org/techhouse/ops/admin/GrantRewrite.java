package org.techhouse.ops.admin;

import org.techhouse.ops.AdminOperationHelper.RewriteVersion;

public sealed interface GrantRewrite permits GrantRewrite.Keep, GrantRewrite.Restamp {
    GrantRewrite KEEP = new Keep();

    RewriteVersion version();

    record Keep() implements GrantRewrite {
        @Override
        public RewriteVersion version() {
            return RewriteVersion.KEEP;
        }
    }

    record Restamp(RestampedRecords into) implements GrantRewrite {
        @Override
        public RewriteVersion version() {
            return RewriteVersion.RESTAMP;
        }
    }
}
