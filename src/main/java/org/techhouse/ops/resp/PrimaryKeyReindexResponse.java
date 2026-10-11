package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.fs.PkRebuild;

public class PrimaryKeyReindexResponse extends ReindexResponse {
    public int primaryKeyEntries;
    public int unversionedEntries;

    public PrimaryKeyReindexResponse(String message, List<String> rebuiltFields, PkRebuild rebuild) {
        super(message, rebuiltFields);
        this.primaryKeyEntries = rebuild.entries();
        this.unversionedEntries = rebuild.unversioned();
    }

    public int getPrimaryKeyEntries() {
        return primaryKeyEntries;
    }

    public void setPrimaryKeyEntries(int primaryKeyEntries) {
        this.primaryKeyEntries = primaryKeyEntries;
    }

    public int getUnversionedEntries() {
        return unversionedEntries;
    }

    public void setUnversionedEntries(int unversionedEntries) {
        this.unversionedEntries = unversionedEntries;
    }
}
