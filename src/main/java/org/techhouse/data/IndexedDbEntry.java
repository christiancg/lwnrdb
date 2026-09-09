package org.techhouse.data;

import java.util.Objects;

public class IndexedDbEntry extends JsonDocumentEntry {
    private PkIndexEntry index;

    public DbEntry toDbEntry() {
        final var entry = new DbEntry();
        entry.set_id(_id);
        entry.setDatabaseName(databaseName);
        entry.setCollectionName(collectionName);
        entry.setData(data);
        if (index != null) {
            entry.setPage(index.getPage());
        }
        entry.setPreviousByteSize(previousByteSize);
        return entry;
    }

    public PkIndexEntry getIndex() {
        return index;
    }

    public void setIndex(PkIndexEntry index) {
        this.index = index;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof IndexedDbEntry that))
            return false;
        return previousByteSize == that.previousByteSize && Objects.equals(_id, that._id)
                && Objects.equals(databaseName, that.databaseName)
                && Objects.equals(collectionName, that.collectionName) && Objects.equals(data, that.data)
                && Objects.equals(index, that.index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(_id, databaseName, collectionName, data, index, previousByteSize);
    }

    @Override
    public String toString() {
        return "IndexedDbEntry(id=" + _id + ", databaseName=" + databaseName + ", collectionName=" + collectionName
                + ", data=" + data + ", index=" + index + ", previousByteSize=" + previousByteSize + ")";
    }
}
