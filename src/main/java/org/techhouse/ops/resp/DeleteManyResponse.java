package org.techhouse.ops.resp;

public class DeleteManyResponse extends DeleteResponse {
    public long deletedCount;

    public DeleteManyResponse(long deletedCount) {
        super("Deleted " + deletedCount + " document(s)");
        this.deletedCount = deletedCount;
    }

    public long getDeletedCount() {
        return deletedCount;
    }
}
